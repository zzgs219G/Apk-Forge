package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SmaliCallParser

/**
 * 增量注入的门面：**先试增量，不行就如实说为什么**。
 *
 * ## 它在整体架构里的位置
 *
 * ```
 * ApkInjectPipeline
 *     └─ FastInjector.tryInject()      ← 本文件
 *           ├─ 能增量 → DexPatcher.replaceCode()      80ms
 *           └─ 不能   → 返回 NotApplicable(原因)
 *                          └─ 调用方退回 DexCallInjector.inject()  9000ms
 * ```
 *
 * ## 为什么是「试了再说」而不是「先判可行再选」
 *
 * 可行性有五个条件：方法存在、有方法体、无 try/catch、调用引用在池里、
 * 寄存器能编码。逐个预判要在 [DexPatcher] 之外再复制一遍判定逻辑，
 * 两处判定迟早漂移（这正是 `踩坑记录` 坑 26 的教训）。
 *
 * 所以这里只做**最外层**的粗筛（新建方法 / 替换方法体这两种模式注定不行），
 * 其余交给 [DexPatcher] 自己跑：它内部遇到不支持的情况会返回 `Failed`，
 * 那时带着原因退回全量——比在门面里猜准确。
 */
object FastInjector {

    /** 增量路径的结论。 */
    sealed interface Result {
        /** 成功，[dex] 是新字节 */
        data class Injected(
            val dex: ByteArray,
            val injectedCount: Int,
            val warnings: List<String>,
            /** 增量耗时的事实，供日志说明「快在哪」 */
            val note: String,
        ) : Result {
            /** 同 [DexPatcher.Outcome.Ok]：`dex` 是几十 MB 的数组，不能让默认 toString 打出来。 */
            override fun toString(): String =
                "Injected(dex=${dex.size}B, injectedCount=$injectedCount, warnings=${warnings.size})"
        }

        /**
         * 增量不适用。**这不是失败**——调用方应当退回全量路径，
         * 并把 [reason] 作为「为什么这次慢」的解释。
         */
        data class NotApplicable(val reason: String) : Result
    }

    /**
     * 尝试用增量方式完成一次注入。
     *
     * @param dexBytes 目标 dex 的原始字节
     * @param spec 与 [DexCallInjector.inject] 完全相同的规格
     */
    fun tryInject(dexBytes: ByteArray, spec: DexCallInjector.Spec): Result {
        // ── 粗筛一：替换方法体会丢弃原指令 ──
        // 增量写入的做法是「新 code_item = 前缀 + 原指令」，天然保留原指令。
        // 实现「丢弃原指令」还得把 try/debug 一并处理干净，属于另一条路径。
        if (spec.replaceBody) {
            return Result.NotApplicable("替换方法体模式需要丢弃原指令，增量路径暂不支持")
        }

        // ── 粗筛二：方法不存在时要新建（要改 method_ids 池）──
        val header = DexPatcher.codeHeader(
            dexBytes, spec.ownerType, spec.methodName, spec.methodSignature
        ) ?: return Result.NotApplicable(
            "${spec.methodName}${spec.methodSignature} 不存在或无方法体，需要全量写入来新建"
        )

        // ── 粗筛三：带 try/catch 的方法需要重算 try_item 偏移 ──
        if (DexPatcher.hasTryBlocks(dexBytes, header.codeOff)) {
            return Result.NotApplicable("${spec.methodName} 带 try/catch 块，增量路径暂不支持")
        }

        // ── 编码所有调用 ──
        if (spec.calls.isEmpty()) {
            return Result.NotApplicable("没有要插入的调用")
        }
        val encoded = mutableListOf<Short>()
        val warnings = mutableListOf<String>()
        var usedRange = false

        for (call in spec.calls) {
            val methodIdx = DexPatcher.resolveMethodIndex(
                dexBytes, call.owner, call.name,
                "(" + call.paramTypes.joinToString("") + ")" + call.returnType,
            ) ?: return Result.NotApplicable(
                "被调用的 ${call.owner}->${call.name} 不在本 dex 的方法池里，" +
                    "增量写入不能新增池条目（用全量写入才能引用外部方法）"
            )

            if (call.registers.any { it.startsWith("v") }) {
                warnings += "引用了 v 寄存器（${call.registers.joinToString()}）：" +
                    "方法开头它可能尚未赋值，运行时可能异常；建议改用 p0/p1 等参数寄存器"
            }

            when (val r = InstructionEncoder.encode(
                call, methodIdx, header.registersSize, header.insSize
            )) {
                is InstructionEncoder.Result.Ok -> {
                    encoded += r.insns.toList()
                    usedRange = usedRange || r.usedRange
                }
                is InstructionEncoder.Result.Failed ->
                    return Result.NotApplicable("指令编码失败：${r.reason}")
            }
        }

        if (usedRange && spec.calls.none { it.rangeForm }) {
            warnings += "寄存器超出 v15 或超过 5 个，已自动改用 /range 形态"
        }

        // ── 真正落笔 ──
        val outcome = DexPatcher.replaceCode(
            dexBytes,
            DexPatcher.ReplaceCodeRequest(
                classType = spec.ownerType,
                methodName = spec.methodName,
                methodSignature = spec.methodSignature,
                prefixInsns = encoded.toShortArray(),
            )
        )
        return when (outcome) {
            is DexPatcher.Outcome.Ok -> Result.Injected(
                dex = outcome.dex,
                injectedCount = spec.calls.size,
                warnings = warnings,
                note = "增量写入：${outcome.originalSize} → ${outcome.newSize} 字节" +
                    "（+${outcome.newSize - outcome.originalSize}）",
            )
            is DexPatcher.Outcome.Failed ->
                Result.NotApplicable("增量写入失败：${outcome.reason}")
        }
    }
}

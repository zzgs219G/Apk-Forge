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
     * 两条路径，按顺序尝试：
     *
     * 1. **纯增量**：方法池里已有该引用时，只改 code_item（[DexPatcher]）。最快。
     * 2. **池注入 + 增量**：引用不在池里时，先往池里加条目（[PoolInjector]），
     *    再改 code_item。这是本工具的核心场景——载荷类是外部类，
     *    永远不在宿主池里。
     *
     * 两者都不适用才返回 [Result.NotApplicable]，让调用方退回全量。
     *
     * @param dexBytes 目标 dex 的原始字节
     * @param spec 与 [DexCallInjector.inject] 完全相同的规格
     */
    fun tryInject(dexBytes: ByteArray, spec: DexCallInjector.Spec): Result {
        // ── 粗筛一：替换方法体会丢弃原指令 ──
        if (spec.replaceBody) {
            return Result.NotApplicable("替换方法体模式需要丢弃原指令，增量路径暂不支持")
        }

        // ── 粗筛二：方法不存在时要新建（要改 class_data_item，池注入不覆盖）──
        val header = DexPatcher.codeHeader(
            dexBytes, spec.ownerType, spec.methodName, spec.methodSignature
        ) ?: return Result.NotApplicable(
            "${spec.methodName}${spec.methodSignature} 不存在或无方法体，需要全量写入来新建"
        )

        // ── 粗筛三：带 try/catch 的方法需要重算 try_item 偏移 ──
        if (DexPatcher.hasTryBlocks(dexBytes, header.codeOff)) {
            return Result.NotApplicable("${spec.methodName} 带 try/catch 块，增量路径暂不支持")
        }

        if (spec.calls.isEmpty()) {
            return Result.NotApplicable("没有要插入的调用")
        }

        val warnings = mutableListOf<String>()

        // ── 第一步：确保所有被引用的方法都在池里 ──
        var workDex = dexBytes
        var poolInjected = false
        var reHeader = header

        for (call in spec.calls) {
            val sig = "(" + call.paramTypes.joinToString("") + ")" + call.returnType
            val existing = DexPatcher.resolveMethodIndex(workDex, call.owner, call.name, sig)
            if (existing != null) continue

            val poolResult = PoolInjector.addMethodRef(
                workDex,
                PoolInjector.MethodRefRequest(
                    owner = call.owner,
                    name = call.name,
                    paramTypes = call.paramTypes,
                    returnType = call.returnType,
                )
            )
            when (poolResult) {
                is PoolInjector.Result.Ok -> {
                    workDex = poolResult.dex
                    poolInjected = true
                    warnings += "已向池注入 ${call.owner}->${call.name}（methodIdx=${poolResult.methodIdx}）"
                }
                is PoolInjector.Result.Failed ->
                    return Result.NotApplicable("池注入失败：${poolResult.reason}")
            }
            reHeader = DexPatcher.codeHeader(
                workDex, spec.ownerType, spec.methodName, spec.methodSignature
            ) ?: return Result.NotApplicable("池注入后找不到目标方法")
        }

        // ── 第二步：编码所有调用 ──
        val encoded = mutableListOf<Short>()
        var usedRange = false

        for (call in spec.calls) {
            val sig = "(" + call.paramTypes.joinToString("") + ")" + call.returnType
            val methodIdx = DexPatcher.resolveMethodIndex(workDex, call.owner, call.name, sig)
                ?: return Result.NotApplicable("池注入后仍找不到 ${call.owner}->${call.name}")

            if (call.registers.any { it.startsWith("v") }) {
                warnings += "引用了 v 寄存器（${call.registers.joinToString()}）：" +
                    "方法开头它可能尚未赋值，运行时可能异常；建议改用 p0/p1 等参数寄存器"
            }

            when (val r = InstructionEncoder.encode(
                call, methodIdx, reHeader.registersSize, reHeader.insSize
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

        // ── 第三步：把 invoke 插进目标方法 ──
        //
        // 【为什么复用 PoolInjector 而不是 DexPatcher】
        // DexPatcher 的做法是「原文件 + 末尾追加新 code_item」，这会同时违反
        // ART 的四条布局约束（map 递增 / 物理顺序 / 段间零填充 / 同 type 连续），
        // 而且让 code_item 之后所有段的绝对偏移失效 —— 那些偏移散落在
        // string_ids、proto_ids、class_defs、annotation 等六七个结构里，
        // 逐个补丁漏一处就是「找不到类」或闪退。
        //
        // PoolInjector 本来就要把**每个** code_item 重写一遍（debug_info_off 变了），
        // 顺手换掉目标方法的指令，不增加任何额外的段移动。
        //
        // 代价是要多跑一遍池重排：第一次先确保新增方法进了池（才能编码出 method_idx），
        // 第二次带上指令一起落盘。第二次池里已经有那个方法，不会重复添加。
        val patched = PoolInjector.addMethodRef(
            workDex,
            PoolInjector.MethodRefRequest(
                owner = spec.calls.first().owner,
                name = spec.calls.first().name,
                paramTypes = spec.calls.first().paramTypes,
                returnType = spec.calls.first().returnType,
            ),
            PoolInjector.InsnPatch(
                classType = spec.ownerType,
                methodName = spec.methodName,
                methodSig = spec.methodSignature,
                newInsns = encoded.toShortArray(),
            ),
        )
        return when (patched) {
            is PoolInjector.Result.Ok -> Result.Injected(
                dex = patched.dex,
                injectedCount = spec.calls.size,
                warnings = warnings,
                note = buildString {
                    if (poolInjected) append("池注入 + ")
                    append("指令注入：${dexBytes.size} → ${patched.dex.size} 字节")
                },
            )
            is PoolInjector.Result.Failed ->
                Result.NotApplicable("增量写入失败：${patched.reason}")
        }
    }
}

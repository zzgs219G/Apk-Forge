package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import com.xixin.apkforge.core.apk.ApkRepackager

/**
 * 幂等保护：目标方法开头**已经是我们那条调用**时，不要重复插。
 *
 * 【为什么需要】`DexCallInjector` 把调用插在方法的第一条指令之前。
 * 对同一个包连点两次「开始注入」（或批量队列里同一个包被收录两次），
 * 产物里就会变成：
 *
 * ```
 * invoke-static {p0}, Lpayload;->inject(...)   ← 第二次注入加进来的
 * invoke-static {p0}, Lpayload;->inject(...)   ← 第一次注入的产物
 * ... 原有指令
 * ```
 *
 * 载荷被调用两次：弹窗弹两遍、初始化跑两遍。这不是崩溃，是**行为翻倍**，
 * 用户只会觉得「这工具有毛病」。而 UI 的 `running` 状态只是界面保证，
 * 挡不住「换一套配置重跑」或「从历史记录复用再跑」。
 *
 * 【判据为什么不看「有没有调用过载荷类」】那太宽：同一个载荷类完全可以
 * 有 `inject` 和 `init` 两个方法都要插，或者用户故意想插两次（打点计数）。
 * 这里钉的是**精确重复**：方法名、参数类型、返回类型、opcode 全部一致。
 *
 * 【`REPLACE_BODY` 为什么也走这条检查】替换方法体的产物是
 * 「super + 注入 + return」，没有原指令。再跑一次同样会叠加。
 * 判据相同，所以不需要为它开特例——需要多插几次的用户改调用行即可
 * （比如换个方法名），而不是让工具在「静默翻倍」和「静默拒绝」之间猜。
 */
object InjectIdempotency {

    /** 一次检查的结论。 */
    sealed interface Verdict {
        /** 方法开头还不是这条调用，可以注入 */
        data object Clean : Verdict

        /** 方法开头已经是这条调用（可能连续多条），重复注入会叠加 */
        data class AlreadyInjected(
            /** 已有几条连续匹配的调用 */
            val existingCount: Int,
            /** 已有的第一条的原始形态，用于说明 */
            val sample: String,
        ) : Verdict

        /** 目标方法或类读不到（不存在 / 不可解析）。交给下游按正常流程处理 */
        data object Unknown : Verdict
    }

    /**
     * 检查 [spec] 里的调用是否已经全部出现在目标方法开头。
     *
     * 「全部出现在开头」才算重复：用户可能第一次插了 A，这次想在 A 之前再插 B，
     * 那是合法的新增，不该拦。
     */
    fun check(apk: File, spec: DexCallInjector.Spec): Verdict {
        val dexNames = try {
            ApkRepackager.listDexNames(apk)
        } catch (_: Exception) {
            return Verdict.Unknown
        }

        for (name in dexNames) {
            val bytes = try {
                ApkRepackager.readEntry(apk, name) ?: continue
            } catch (_: Exception) {
                continue
            }
            val df = try {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            } catch (_: Exception) {
                continue
            }
            val cls = df.classes.firstOrNull { it.type == spec.ownerType } ?: continue
            val impl = cls.methods
                .firstOrNull {
                    it.name == spec.methodName && signatureOf(it) == spec.methodSignature
                }?.implementation ?: return Verdict.Unknown

            // 只比方法**开头**连续的那几条：注入点就在最前面，
            // 深处的相同指令是宿主自己的代码，与重复注入无关。
            val leading = impl.instructions.take(spec.calls.size).toList()
            val matched = leading.count { insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@count false
                spec.calls.any { call ->
                    ref.definingClass == call.owner &&
                        ref.name == call.name &&
                        ref.parameterTypes.map { it.toString() } == call.paramTypes &&
                        ref.returnType == call.returnType
                }
            }
            return if (spec.calls.isNotEmpty() && matched == spec.calls.size) {
                Verdict.AlreadyInjected(
                    existingCount = matched,
                    sample = describe(spec),
                )
            } else {
                Verdict.Clean
            }
        }
        return Verdict.Unknown
    }

    /** 面向用户的一句话，说清「已注过」和下一步。 */
    fun message(v: Verdict.AlreadyInjected, methodName: String): String = when {
        v.existingCount == 1 ->
            "$methodName 开头已经是这条调用（上次注入的产物）。再插一次会让载荷执行两次，" +
                "已跳过这一个落点。想强制重插，请先把包还原成注入前的版本。"
        else ->
            "$methodName 开头已有 ${v.existingCount} 条相同调用（多次注入叠加）。" +
                "再插一次会让载荷执行 ${v.existingCount + 1} 次，已跳过这一个落点。" +
                "建议从原始包重新注入。"
    }

    private fun describe(spec: DexCallInjector.Spec): String {
        val c = spec.calls.firstOrNull() ?: return spec.methodName
        // 用**被调用方法**的签名，不是 spec.methodSignature（那是注入点的签名）
        return "${c.owner}->${c.name}(" + c.paramTypes.joinToString("") + ")" + c.returnType
    }

    private fun signatureOf(m: com.android.tools.smali.dexlib2.iface.Method): String =
        "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
}

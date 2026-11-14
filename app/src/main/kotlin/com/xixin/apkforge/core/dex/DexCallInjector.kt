package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.xixin.apkforge.core.dex.SmaliCallParser

/**
 * 在目标方法**开头**插入若干条 invoke 指令；目标方法不存在时按需创建。
 *
 * 四个必须处理的硬约束：
 *
 * 1. **寄存器宽度**：`invoke-*` 的 35c 格式寄存器列表仅 4 bit（v0–v15）。
 *    超出必须换 `invoke-` + `/range` 形态（3rc，16 bit）。按实际物理寄存器号自动决定，
 *    不照抄用户写的形态——用户复制的那行来自别的方法，寄存器布局与目标方法不同。
 *
 * 2. **p/v 换算**：`p0` 是相对参数区的，物理号 = registerCount - paramRegCount + i。
 *    插到方法头部时**只有参数寄存器（p 族）的值是确定的**，v 族未初始化。
 *    v 族引用只换算、不阻止，但必须在 warnings 里报出来。
 *
 * 3. **range 必须连续**：3rc 的寄存器区间是 `[start, start+count)`，
 *    如果用户的寄存器不连续（如 {v0, v5}），必须回退成 35c 或直接报错。
 *
 * 4. **方法不存在时创建**：Activity/Application 的 `onCreate` 覆写缺失是合法且常见的。
 *    新建时方法体 = 父类调用（组件类必须调，否则生命周期断裂）+ 注入指令 + return-void。
 */
object DexCallInjector {

    data class Spec(
        val ownerType: String,
        val methodName: String,
        val methodSignature: String = "(Landroid/os/Bundle;)V",
        val calls: List<SmaliCallParser.Call>,
    )

    sealed interface Outcome {
        data class Injected(
            val dex: ByteArray,
            val createdMethod: Boolean,
            val injectedCount: Int,
            val warnings: List<String>,
        ) : Outcome

        data class Skipped(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun containsClass(dexBytes: ByteArray, ownerType: String): Boolean = try {
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), dexBytes.inputStream())
            .classes.any { it.type == ownerType }
    } catch (_: Exception) {
        false
    }

    fun inject(dexBytes: ByteArray, spec: Spec): Outcome {
        val srcDex: DexFile = try {
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), dexBytes.inputStream())
        } catch (e: Exception) {
            return Outcome.Failed("dex 解析失败: ${e.message}")
        }

        val targetClass = srcDex.classes.firstOrNull { it.type == spec.ownerType }
            ?: return Outcome.Skipped("dex 内无类 ${spec.ownerType}")

        // dexlib2 的 methods 每次访问都新建对象，必须先物化再定位/替换，
        // 否则 `it === target` 永远 false，改写会静默失效
        val classMethods = targetClass.methods.toList()
        val existing = classMethods.firstOrNull {
            it.name == spec.methodName && signatureOf(it) == spec.methodSignature
        }

        val warnings = mutableListOf<String>()

        val paramTypes: List<String>
        val returnType: String
        val registerCount: Int
        val created: Boolean
        var existingAnnotations: Set<com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation>? = null

        if (existing != null) {
            if (existing.accessFlags and ACCESS_NATIVE != 0) {
                return Outcome.Skipped("${spec.methodName} 是 native，无方法体可插")
            }
            val impl = existing.implementation
                ?: return Outcome.Skipped("${spec.methodName} 无方法体（abstract）")
            created = false
            paramTypes = existing.parameterTypes.map { it.toString() }
            returnType = existing.returnType
            registerCount = impl.registerCount
            existingAnnotations = existing.annotations
                .map { com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation.of(it) }
                .toSet()
        } else {
            created = true
            paramTypes = parseSignatureParams(spec.methodSignature)
                ?: return Outcome.Failed("注入点签名无法解析: ${spec.methodSignature}")
            returnType = returnTypeOf(spec.methodSignature)
            // 新建方法：原有参数寄存器已够用（p 区），不额外申请，
            // 因为注入的调用只引用 p 族寄存器
            registerCount = paramTypes.size + 1
        }

        val paramRegCount = paramTypes.size + 1   // 实例方法 + this
        if (registerCount < paramRegCount) {
            return Outcome.Failed(
                "方法寄存器数($registerCount) 少于参数寄存器数($paramRegCount)，无法注入"
            )
        }

        // ── 逐条换算寄存器、生成指令 ──
        val injectedInsns = mutableListOf<Instruction>()
        for (call in spec.calls) {
            val phys = mutableListOf<Int>()
            for (r in call.registers) {
                val n = resolveRegister(r, registerCount, paramRegCount)
                    ?: return Outcome.Failed(
                        "寄存器 $r 超出范围（${spec.methodName} 共 $registerCount 个寄存器，" +
                            "参数区 $paramRegCount 个）：${call.raw}"
                    )
                phys += n
            }

            if (call.registers.any { it.startsWith("v") }) {
                warnings += "引用了 v 寄存器（${call.registers.joinToString()}）：" +
                    "方法开头它可能尚未赋值，运行时可能异常；建议改用 p0/p1 等参数寄存器"
            }

            val ref = ImmutableMethodReference(
                call.owner, call.name, call.paramTypes, call.returnType
            )

            // 35c 最多 5 个寄存器且编号 <= v15
            val fits35c = phys.size <= 5 && phys.all { it <= 15 }
            val contiguous = phys.isEmpty() || (phys.max() - phys.min() + 1 == phys.size)

            injectedInsns += if (fits35c) {
                ImmutableInstruction35c(
                    opcodeFor(call.kind, false),
                    phys.size,
                    phys.getOrElse(0) { 0 },
                    phys.getOrElse(1) { 0 },
                    phys.getOrElse(2) { 0 },
                    phys.getOrElse(3) { 0 },
                    phys.getOrElse(4) { 0 },
                    ref,
                )
            } else {
                if (!contiguous) {
                    return Outcome.Failed(
                        "寄存器需要 /range 形态但编号不连续（${phys.joinToString()}）：" +
                            "dex 的 range 指令要求寄存器连续，请调整调用：${call.raw}"
                    )
                }
                if (!call.rangeForm) {
                    warnings += "寄存器超出 v15 或超过 5 个，已自动改用 /range 形态：${call.raw}"
                }
                ImmutableInstruction3rc(
                    opcodeFor(call.kind, true), phys.min(), phys.size, ref
                )
            }
        }

        // ── 组装方法体 ──
        val body = mutableListOf<Instruction>()

        // 新建方法时先调父类同名方法，否则 Activity 生命周期断裂直接崩
        if (created) {
            val superInsn = buildSuperCall(targetClass, spec.methodName, spec.methodSignature, registerCount)
            if (superInsn != null) {
                body += superInsn
            } else {
                warnings += "未能确定父类调用（父类=${targetClass.superclass ?: "无"}），" +
                    "新建的方法不会调用 super，若父类有必须逻辑需自行处理"
            }
        }

        body += injectedInsns

        if (!created) {
            existing!!.implementation!!.instructions.forEach { body += it }
        } else {
            // 新建方法的返回值：非 void 时给个 0/null 兜底，避免验证器拒绝
            body += defaultReturnFor(returnType)
        }

        val newImpl = ImmutableMethodImplementation(
            registerCount,
            body,
            if (created) null else existing!!.implementation!!.tryBlocks,
            if (created) null else existing!!.implementation!!.debugItems,
        )

        val accessFlags = if (created) ACCESS_PUBLIC else existing!!.accessFlags
        val newMethod = ImmutableMethod(
            spec.ownerType,
            spec.methodName,
            paramTypes.map {
                com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(
                    it, null, null
                )
            },
            returnType,
            accessFlags,
            existingAnnotations,
            existing?.hiddenApiRestrictions,
            newImpl,
        )

        val newMethods = if (created) {
            classMethods + newMethod
        } else {
            classMethods.map { m ->
                if (m.name == spec.methodName && signatureOf(m) == spec.methodSignature) {
                    newMethod
                } else {
                    m
                }
            }
        }

        val newClass = ImmutableClassDef(
            targetClass.type,
            targetClass.accessFlags,
            targetClass.superclass,
            targetClass.interfaces,
            targetClass.sourceFile,
            targetClass.annotations,
            targetClass.fields,
            newMethods,
        )

        val newDexFile = SimpleDexFile(
            srcDex.classes.map { if (it.type == spec.ownerType) newClass else it }
        )

        return try {
            val store = MemoryDataStore()
            DexPool.writeTo(store, newDexFile)
            Outcome.Injected(
                dex = store.data,
                createdMethod = created,
                injectedCount = injectedInsns.size,
                warnings = warnings,
            )
        } catch (e: Exception) {
            Outcome.Failed("dex 写出失败: ${e.message}")
        }
    }

    // ────────────────────────── 辅助 ──────────────────────────

    private const val ACCESS_PUBLIC = 0x1
    private const val ACCESS_NATIVE = 0x100

    private fun signatureOf(m: Method): String =
        "(" + m.parameterTypes.joinToString("") + ")" + m.returnType

    private fun opcodeFor(kind: SmaliCallParser.Kind, range: Boolean): Opcode =
        when (kind) {
            SmaliCallParser.Kind.STATIC ->
                if (range) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC
            SmaliCallParser.Kind.VIRTUAL ->
                if (range) Opcode.INVOKE_VIRTUAL_RANGE else Opcode.INVOKE_VIRTUAL
            SmaliCallParser.Kind.DIRECT ->
                if (range) Opcode.INVOKE_DIRECT_RANGE else Opcode.INVOKE_DIRECT
            SmaliCallParser.Kind.SUPER ->
                if (range) Opcode.INVOKE_SUPER_RANGE else Opcode.INVOKE_SUPER
            SmaliCallParser.Kind.INTERFACE ->
                if (range) Opcode.INVOKE_INTERFACE_RANGE else Opcode.INVOKE_INTERFACE
        }

    private fun resolveRegister(text: String, registerCount: Int, paramRegCount: Int): Int? {
        Regex("""^p(\d+)$""").matchEntire(text.trim())?.let {
            val i = it.groupValues[1].toInt()
            return if (i < paramRegCount) registerCount - paramRegCount + i else null
        }
        Regex("""^v(\d+)$""").matchEntire(text.trim())?.let {
            val i = it.groupValues[1].toInt()
            return if (i < registerCount) i else null
        }
        return null
    }

    private fun parseSignatureParams(sig: String): List<String>? =
        SmaliCallParser.parseAll("invoke-static {}, Lx;->y$sig").first.firstOrNull()?.paramTypes

    private fun returnTypeOf(sig: String): String {
        val end = sig.indexOf(')')
        return if (end >= 0) sig.substring(end + 1) else "V"
    }

    /**
     * 父类调用。Activity 系必须调 super.onCreate，否则生命周期状态机断裂。
     *
     * 判定依据是「父类确实声明了同名同签名的方法」，而不是硬编码方法名白名单——
     * 白名单会漏掉 onNewIntent/onPostCreate 等同样需要 super 的生命周期方法，
     * 也会让用户在自定义方法上观察不到行为差异。
     */
    private fun buildSuperCall(
        cls: ClassDef,
        methodName: String,
        signature: String,
        registerCount: Int,
    ): Instruction? {
        val superType = cls.superclass ?: return null
        if (superType == "Landroid/app/Object;") return null

        val params = parseSignatureParams(signature) ?: return null
        val ret = returnTypeOf(signature)
        val paramRegCount = params.size + 1
        if (registerCount < paramRegCount) return null

        // p 区起点（= p0 物理号）；range 形态要求连续，正好覆盖整个参数区
        val pStart = registerCount - paramRegCount
        val ref = ImmutableMethodReference(superType, methodName, params, ret)
        return ImmutableInstruction3rc(Opcode.INVOKE_SUPER_RANGE, pStart, paramRegCount, ref)
    }

    /** 新建方法若不是 void 返回，需要给兜底返回指令，否则验证器拒绝。 */
    private fun defaultReturnFor(returnType: String): Instruction = when (returnType) {
        "V" -> ImmutableInstruction10x(Opcode.RETURN_VOID)
        "Z", "B", "S", "C", "I" -> ImmutableInstruction10x(Opcode.RETURN)
        "J", "D" -> ImmutableInstruction10x(Opcode.RETURN_WIDE)
        else -> ImmutableInstruction10x(Opcode.RETURN_OBJECT)
    }
}

/** 最小 DexFile 实现。 */
private class SimpleDexFile(
    private val classDefs: List<ClassDef>,
) : DexFile {
    override fun getClasses(): Set<ClassDef> = LinkedHashSet(classDefs)
    override fun getOpcodes(): Opcodes = Opcodes.getDefault()
}

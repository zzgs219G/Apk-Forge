package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * 重写 `code_item` 指令里**所有**池下标。
 *
 * ## 定位方式：用 dexlib2 的 `instructionStart`
 *
 * `DexBackedInstruction` 有 `public final int instructionStart`，
 * 它是该指令在**dex 文件里的绝对偏移**。这比自己维护「宽度表 + code_off 累加」
 * 可靠得多——第一版用 `codeOffset`（接口里不存在）和手写宽度表，
 * 结果所有 patch 都静默跳过（索引全部错位，jadx 报 248 个 decode 失败）。
 *
 * ## 各格式里索引所在的字
 *
 * | 格式 | 布局 | 索引位置（相对指令起点） |
 * |---|---|---|
 * | 21c  | `op AA \| bbbb` | +2 字节 |
 * | 22c  | `op A\|B \| bbbb` | +2 |
 * | 23x  | `op AA \| BB \| CC` | 无池引用 |
 * | 31c  | `op AA \| bbbb \| bbbb` | +2（u32，占 4 字节） |
 * | 35c  | `op AG \| bbbb \| FEDC` | +2 |
 * | 3rc  | `op AA \| bbbb \| bbbb` | +4（第 2 字是起始寄存器） |
 * | 45cc | `op AG \| bbbb \| FEDC \| bbbb` | +2，proto 在 +6 |
 * | 4rcc | `op AA \| bbbb \| bbbb \| bbbb` | +4，proto 在 +6 |
 */
internal object InstructionRefRemapper {

    data class Patch(
        /** 索引在**文件**里的绝对偏移 */
        val absOffset: Int,
        val oldIndex: Int,
        val kind: Kind,
        /** 索引占 2 个字（31c jumbo 的 u32） */
        val wide: Boolean = false,
    )

    enum class Kind { STRING, TYPE, FIELD, METHOD, PROTO }

    /**
     * 收集一个 dex 里所有需要改的索引。
     *
     * @return 空列表表示没有需要改的（也说明定位失败，调用方应视为异常）
     */
    fun collect(
        dexFile: com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile,
        stringIdx: (String) -> Int,
        typeIdx: (String) -> Int,
        fieldIdx: (String, String, String) -> Int,
        methodIdx: (String, String, List<String>, String) -> Int,
        protoIdx: (List<String>, String) -> Int,
    ): List<Patch> {
        val out = ArrayList<Patch>()

        for (cls in dexFile.classes) {
            for (method in cls.methods) {
                val impl = method.implementation ?: continue
                for (insn in impl.instructions) {
                    val start = (insn as? DexBackedInstruction)?.instructionStart ?: continue
                    val ref = (insn as? ReferenceInstruction)?.reference ?: continue

                    // 索引所在位置由**指令格式**决定，不能用 `is Instruction3rc` 判断：
                    // dexlib2 的 3rc 实现类未必实现该接口，判断失败就会把 +2 当成索引，
                    // 实际那是**起始寄存器** —— 结果寄存器号被改成索引值（实测出现 r471），
                    // jadx 报 `Incorrect register number`。
                    //
                    // 格式与「索引字」的对应：
                    //   21c/22c/35c/45cc → 第 1 字（+2）
                    //   3rc/4rcc         → 第 2 字（+4），第 1 字是起始寄存器
                    val wordCount = insn.codeUnits
                    val fmt = insn.opcode.format
                    val isRangeFmt = fmt != null && (
                        fmt.name.startsWith("Format3rc") || fmt.name.startsWith("Format4rcc")
                        )
                    val idxOffset = start + if (isRangeFmt) 4 else 2

                    when (ref) {
                        is StringReference -> {
                            val i = stringIdx(ref.string)
                            // 31c（const-string/jumbo）是 3 字、索引占 u32；
                            // 3rc 也是 3 字但不是字符串指令，用 isRangeFmt 排除。
                            if (i >= 0) out += Patch(
                                idxOffset, i, Kind.STRING,
                                wide = insn.codeUnits == 3 && !isRangeFmt,
                            )
                        }
                        is TypeReference -> {
                            val i = typeIdx(ref.type)
                            if (i >= 0) out += Patch(idxOffset, i, Kind.TYPE)
                        }
                        is FieldReference -> {
                            val i = fieldIdx(ref.definingClass, ref.name, ref.type)
                            if (i >= 0) out += Patch(idxOffset, i, Kind.FIELD)
                        }
                        is MethodReference -> {
                            val i = methodIdx(
                                ref.definingClass, ref.name,
                                ref.parameterTypes.map { it.toString() }, ref.returnType,
                            )
                            if (i >= 0) out += Patch(idxOffset, i, Kind.METHOD)
                        }
                        is MethodProtoReference -> {
                            val i = protoIdx(
                                ref.parameterTypes.map { it.toString() }, ref.returnType,
                            )
                            // 45cc/4rcc 的 proto 索引固定在最后一字（+6）
                            if (i >= 0) out += Patch(start + 6, i, Kind.PROTO)
                        }
                        else -> Unit
                    }
                }
            }
        }
        return out
    }

    /**
     * 写入重映射后的索引。
     *
     * @return 错误说明；null 表示成功
     */
    fun patch(
        out: GrowableBuffer,
        patches: List<Patch>,
        maps: EncodedValueRemapper.Maps,
    ): String? {
        for (p in patches) {
            val table = when (p.kind) {
                Kind.STRING -> maps.stringMap
                Kind.TYPE -> maps.typeMap
                Kind.FIELD -> maps.fieldMap
                Kind.METHOD -> maps.methodMap
                Kind.PROTO -> maps.protoMap
            }
            if (p.oldIndex >= table.size) {
                return "${p.kind} 旧索引 ${p.oldIndex} 超出映射表长度 ${table.size}"
            }
            val newIdx = table[p.oldIndex]
            if (p.wide) {
                out.patchU16(p.absOffset, newIdx and 0xFFFF)
                out.patchU16(p.absOffset + 2, (newIdx ushr 16) and 0xFFFF)
            } else {
                if (newIdx > 0xFFFF) {
                    return "${p.kind} 索引重映射后变成 $newIdx（超过 u16），需要换指令形态"
                }
                out.patchU16(p.absOffset, newIdx and 0xFFFF)
            }
        }
        return null
    }
}

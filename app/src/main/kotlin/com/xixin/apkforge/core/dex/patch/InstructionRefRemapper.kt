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
        /** 索引在**文件**里的绝对偏移（原 dex 中的位置） */
        val absOffset: Int,
        val oldIndex: Int,
        val kind: Kind,
        /** 索引占 2 个字（31c jumbo 的 u32） */
        val wide: Boolean = false,
        /**
         * 该指令所属 code_item 的**原** code_off。
         *
         * 带 try/catch 的方法，其 code_item 会被整体重写并搬到文件末尾
         * （因为 handler 的 type_idx 是 uleb128，改后长度可能变）。
         * 那时 `absOffset` 在原位置已经无效，要按「新 code_off + 区内偏移」重算。
         */
        val ownerCodeOff: Int = 0,
    )

    enum class Kind { STRING, TYPE, FIELD, METHOD, PROTO }

    /**
     * 收集一个 dex 里所有需要改的索引。
     *
     * @param codeOffMap 旧 code_off → 新 code_off。带 try/catch 的方法其 code_item
     *                   被搬到了新位置，`instructionStart` 在原位置已无效，
     *                   必须按新位置重算。空表示没有搬移。
     * @return 空列表表示没有需要改的（也说明定位失败，调用方应视为异常）
     */
    fun collect(
        dexFile: com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile,
        stringIdx: (String) -> Int,
        typeIdx: (String) -> Int,
        fieldIdx: (String, String, String) -> Int,
        methodIdx: (String, String, List<String>, String) -> Int,
        protoIdx: (List<String>, String) -> Int,
        codeOffMap: Map<Int, Int> = emptyMap(),
    ): List<Patch> {
        val out = ArrayList<Patch>()

        for (cls in dexFile.classes) {
            for (method in cls.methods) {
                val impl = method.implementation ?: continue
                // 该方法的 code_item 原位置 = 首条指令的 instructionStart - 16
                val firstInsn = impl.instructions.firstOrNull() as?
                    DexBackedInstruction ?: continue
                val oldCodeOff = firstInsn.instructionStart - 16
                val newCodeOff = codeOffMap[oldCodeOff]
                // 搬到新位置后，区内偏移不变，只是基址变了
                val shift = if (newCodeOff != null) newCodeOff - oldCodeOff else 0

                for (insn in impl.instructions) {
                    val start = (insn as? DexBackedInstruction)?.instructionStart ?: continue
                    val ref = (insn as? ReferenceInstruction)?.reference ?: continue
                    val base = start + shift

                    // ⚠️ 3rc/4rcc 的布局与直觉相反（实测 + dexlib2 验证）：
                    //   [0] op | AA          AA = 寄存器个数
                    //   [1] BBBB = **方法索引**
                    //   [2] BBBB = **起始寄存器**   ← 在最后，不是第二字！
                    //
                    // 第一版按「起始寄存器在前、索引在后」处理，把 method_idx 写进了
                    // 起始寄存器字段 —— 产物里出现 r471 这类非法寄存器号，
                    // jadx 报 `Incorrect register number in instruction: INVOKE`。
                    //
                    // 所以 3rc/4rcc 与 35c 一样，索引都在 +2；4rcc 的 proto 在 +6。
                    val fmt = insn.opcode.format
                    val isRangeFmt = fmt != null && (
                        fmt.name.startsWith("Format3rc") || fmt.name.startsWith("Format4rcc")
                        )
                    // ⚠️ 用 base（已按搬移量修正）而不是 start
                    val idxOffset = base + 2

                    when (ref) {
                        is StringReference -> {
                            val i = stringIdx(ref.string)
                            // 31c（const-string/jumbo）是 3 字、索引占 u32；
                            // 3rc 也是 3 字但不是字符串指令，用 isRangeFmt 排除。
                            if (i >= 0) out += Patch(
                                idxOffset, i, Kind.STRING,
                                wide = insn.codeUnits == 3 && !isRangeFmt,
                                ownerCodeOff = oldCodeOff,
                            )
                        }
                        is TypeReference -> {
                            val i = typeIdx(ref.type)
                            if (i >= 0) out += Patch(idxOffset, i, Kind.TYPE, ownerCodeOff = oldCodeOff)
                        }
                        is FieldReference -> {
                            val i = fieldIdx(ref.definingClass, ref.name, ref.type)
                            if (i >= 0) out += Patch(idxOffset, i, Kind.FIELD, ownerCodeOff = oldCodeOff)
                        }
                        is MethodReference -> {
                            val i = methodIdx(
                                ref.definingClass, ref.name,
                                ref.parameterTypes.map { it.toString() }, ref.returnType,
                            )
                            if (i >= 0) out += Patch(idxOffset, i, Kind.METHOD, ownerCodeOff = oldCodeOff)
                        }
                        is MethodProtoReference -> {
                            val i = protoIdx(
                                ref.parameterTypes.map { it.toString() }, ref.returnType,
                            )
                            // 45cc/4rcc 的 proto 索引固定在最后一字（+6）
                            if (i >= 0) out += Patch(base + 6, i, Kind.PROTO, ownerCodeOff = oldCodeOff)
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
     * @param codeRange 允许写入的区间 `[start, end)`（code_item 段的范围）。
     *                  越界的 patch 会直接报错——写到别的段上会把
     *                  `type_list` 之类的数据覆盖成随机值，而所有段级自检都通过，
     *                  只能靠 ART 报 `Bad index for type_list.type` 才发现。
     * @return 错误说明；null 表示成功
     */
    fun patch(
        out: GrowableBuffer,
        patches: List<Patch>,
        maps: EncodedValueRemapper.Maps,
        codeRange: IntRange? = null,
    ): String? {
        for (p in patches) {
            if (codeRange != null && p.absOffset !in codeRange) {
                return "指令 patch 偏移 ${p.absOffset} 落在 code_item 段" +
                    "[${codeRange.first}, ${codeRange.last}] 之外；" +
                    "继续写会覆盖其它段的数据"
            }
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

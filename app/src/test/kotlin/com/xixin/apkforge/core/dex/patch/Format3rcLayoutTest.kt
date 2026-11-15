package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import com.xixin.apkforge.core.dex.DexOpcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 钉住 3rc 格式的**实际字布局**。
 *
 * 【这条测试的存在理由】3rc 的布局与直觉相反：
 *
 * ```
 * [0] op | AA           AA = 寄存器个数
 * [1] BBBB = 方法索引      ← 索引在第二个字
 * [2] BBBB = 起始寄存器     ← 起始寄存器在最后一个字
 * ```
 *
 * 第一版按「起始寄存器在前、索引在后」处理，把 `method_idx` 写进了起始寄存器字段，
 * 产物里出现 `r471` 这类非法寄存器号，jadx 报
 * `Incorrect register number in instruction: INVOKE`。
 *
 * 判据用 dexlib2 的 `getStartRegister()`（权威值）去核对原始字。
 */
class Format3rcLayoutTest {

    @Test
    fun `起始寄存器在第三个字`() {
        val apk = File(System.getProperty("user.home"), "orig.apk")
        org.junit.Assume.assumeTrue("没有 orig.apk 样本", apk.isFile)
        val dex = ZipFile(apk).use { z ->
            z.getInputStream(z.getEntry("classes7.dex")).use { it.readBytes() }
        }
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(dex), dex.inputStream())

        fun w(at: Int): Int =
            if (at + 1 < dex.size) (dex[at].toInt() and 0xFF) or ((dex[at + 1].toInt() and 0xFF) shl 8)
            else -1

        var checked = 0
        outer@ for (cls in df.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn.opcode.format?.name != "Format3rc") continue
                    val start = (insn as? DexBackedInstruction)?.instructionStart ?: continue
                    val r = insn as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc

                    val word0 = w(start)
                    val word1 = w(start + 2)
                    val word2 = w(start + 4)

                    // [0] 的低 8 位是 opcode，高 8 位是寄存器个数
                    assertEquals(
                        "AA（寄存器个数）应在第 0 字的高 8 位",
                        r.registerCount, (word0 ushr 8) and 0xFF,
                    )
                    // [2] 是起始寄存器
                    assertEquals(
                        "起始寄存器应在第 2 个字（不是第 1 个）", r.startRegister, word2,
                    )
                    // [1] 是方法索引，必然非负且在 method_ids 范围内
                    assertTrue("方法索引应为非负", word1 >= 0)

                    checked++
                    if (checked >= 20) break@outer
                }
            }
        }
        assertTrue("没有找到 3rc 指令，测试无效", checked > 0)
        println("F3RC 校验 $checked 条 invoke-*/range：起始寄存器在第 2 字，索引在第 1 字")
    }
}

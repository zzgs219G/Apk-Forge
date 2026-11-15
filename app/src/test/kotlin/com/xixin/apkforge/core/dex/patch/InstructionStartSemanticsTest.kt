package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import com.xixin.apkforge.core.dex.DexOpcodes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 校验 `DexBackedInstruction.instructionStart` 的语义。
 *
 * 这是指令重映射的基础：如果它不是「文件绝对偏移」，那所有 patch 都会写错位置。
 * 判据：某条指令的 `instructionStart` 处的 16 位字，其低 8 位必须等于该指令的 opcode。
 */
class InstructionStartSemanticsTest {

    @Test
    fun `instructionStart 指向指令的 opcode 字`() {
        val apk = File(System.getProperty("user.home"), "sample-release.apk")
        org.junit.Assume.assumeTrue("没有样本", apk.isFile)
        val dex = ZipFile(apk).use { z ->
            z.getInputStream(z.getEntry("classes.dex")).use { it.readBytes() }
        }
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(dex), dex.inputStream())
        val opcodes = DexOpcodes.forBytes(dex)

        var checked = 0
        var ok = 0
        val bad = mutableListOf<String>()

        outer@ for (cls in df.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    val backed = insn as? DexBackedInstruction ?: continue
                    val start = backed.instructionStart
                    if (start <= 0 || start + 1 >= dex.size) continue
                    val word = (dex[start].toInt() and 0xFF) or
                        ((dex[start + 1].toInt() and 0xFF) shl 8)
                    val opInFile = word and 0xFF
                    val expected = opcodes.getOpcodeValue(insn.opcode)?.toInt() ?: continue
                    checked++
                    if (opInFile == expected) {
                        ok++
                    } else {
                        if (bad.size < 5) {
                            bad += "start=0x${start.toString(16)} 期望 op=0x${expected.toString(16)} " +
                                "实际=0x${opInFile.toString(16)}（${insn.opcode.name}）"
                        }
                    }
                    if (checked >= 20000) break@outer
                }
            }
        }

        println("START 检查 $checked 条指令，匹配 $ok 条")
        assertTrue("instructionStart 不是文件绝对偏移（匹配率 $ok/$checked）：\n" +
            bad.joinToString("\n"), ok == checked)
    }
}

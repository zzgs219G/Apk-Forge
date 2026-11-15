package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.dex.DexOpcodes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 对比注入前后**每个方法的指令 opcode 序列与 debug 信息**。
 *
 * 用 dexlib2 读（它读得对），比对「内容」而非「下标」。
 * 这是判断「结构是否被改坏」最可靠的方式：
 * 池重排会让下标合法地变化，但**方法体的语义不该变**。
 */
class MethodBodyIntegrityTest {

    private fun load(apk: File, entry: String): ByteArray =
        ZipFile(apk).use { z -> z.getInputStream(z.getEntry(entry)).use { it.readBytes() } }

    /** 「类|方法|签名」→ 指令 opcode 列表 + debug 项文本 */
    private fun collect(dex: ByteArray): Map<String, String> {
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(dex), dex.inputStream())
        val out = HashMap<String, String>(60000)
        for (cls in df.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                val sb = StringBuilder()
                for (i in impl.instructions) {
                    sb.append(i.opcode.name).append('/').append(i.codeUnits).append(' ')
                }
                // debug 项：只记类型与名字，不记偏移
                impl.debugItems?.forEach { d ->
                    sb.append("|D:").append(d.debugItemType)
                    when (d) {
                        is com.android.tools.smali.dexlib2.iface.debug.StartLocal ->
                            sb.append(':').append(d.name).append(':').append(d.type)
                        is com.android.tools.smali.dexlib2.iface.debug.SetSourceFile ->
                            sb.append(':').append(d.sourceFile)
                        else -> Unit
                    }
                }
                val sig = "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
                out["${cls.type}|${m.name}|$sig"] = sb.toString()
            }
        }
        return out
    }

    @Test
    fun `注入前后除目标方法外，所有方法体与 debug 信息一致`() {
        val orig = File(System.getProperty("user.home"), "o.apk")
        val inj = File(System.getProperty("user.home"), "i.apk")
        org.junit.Assume.assumeTrue("缺少对比样本", orig.isFile && inj.isFile)

        val before = collect(load(orig, "classes.dex"))
        val after = collect(load(inj, "classes.dex"))
        println("BODY 原版 ${before.size} 个方法，注入后 ${after.size} 个")

        val target = "Lcom/xixin/apkforge/MainActivity;|onCreate|(Landroid/os/Bundle;)V"
        val diffs = mutableListOf<String>()
        before.forEach { (k, v) ->
            if (k == target) return@forEach
            val other = after[k]
            if (other == null) {
                if (diffs.size < 8) diffs += "$k 消失了"
                return@forEach
            }
            if (other != v) {
                if (diffs.size < 8) {
                    val i = v.zip(other).indexOfFirst { (a, b) -> a != b }
                    diffs += "$k 变化于第 $i 个字符\n  原=${v.take(160)}\n  新=${other.take(160)}"
                }
            }
        }
        assertEquals(
            "有 ${diffs.size} 个方法体/调试信息变了：\n" + diffs.joinToString("\n\n"),
            0,
            diffs.size,
        )
        println("BODY 全部 ${before.size} 个方法一致")
    }
}

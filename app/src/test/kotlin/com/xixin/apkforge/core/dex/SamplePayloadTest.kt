package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验随 App 打包的示例载荷 dex。
 *
 * 这里刻意断言「UI 里预填的那行调用」与「dex 里真实存在的方法」一致：
 * 示例如果和预填文本对不上，用户点了「填入示例」注入进去必然运行时 NoSuchMethodError，
 * 而那种错误在真机上表现为静默失败（载荷类已被 catch 吞掉），极难排查。
 */
class SamplePayloadTest {

    private fun sample(): File {
        val p = listOf(
            File("src/main/assets/sample/DemoDialog.dex"),
            File("app/src/main/assets/sample/DemoDialog.dex"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到示例 dex")
        return p
    }

    @Test
    fun `示例 dex 结构合法且含 inject(Activity)V`() {
        val raw = sample().readBytes()
        assertEquals("魔数错误", "dex\n", String(raw.copyOfRange(0, 4)))
        assertTrue("dex 过小，可能未正确生成", raw.size > 512)

        val df = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), raw.inputStream())
        val cls = df.classes.firstOrNull { it.type == "Lcom/apkforge/demo/DemoDialog;" }
        assertNotNull("缺少 DemoDialog 类，实际: ${df.classes.map { it.type }}", cls)

        val inject = cls!!.methods.firstOrNull {
            it.name == "inject" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/app/Activity;") &&
                it.returnType == "V"
        }
        assertNotNull(
            "缺少 inject(Activity)V，实际方法: " +
                cls.methods.map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" },
            inject
        )
        assertTrue("inject 必须有方法体", inject!!.implementation != null)

        println("示例 dex ${raw.size} B")
        cls.methods.forEach {
            println("  ${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}")
        }
    }

    @Test
    fun `UI 预填的调用行能解析且指向 dex 里真实存在的方法`() {
        val line = "invoke-static {p0}, " +
            "Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        val (calls, errors) = SmaliCallParser.parseAll(line)
        assertTrue("解析报错: ${errors.messages}", errors.isEmpty)
        assertEquals(1, calls.size)

        val c = calls.first()
        assertEquals("Lcom/apkforge/demo/DemoDialog;", c.owner)
        assertEquals("inject", c.name)
        assertEquals(listOf("Landroid/app/Activity;"), c.paramTypes)
        assertEquals("V", c.returnType)
        assertEquals(listOf("p0"), c.registers)
        assertEquals(SmaliCallParser.Kind.STATIC, c.kind)

        val df = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), sample().readBytes().inputStream()
        )
        val cls = df.classes.first { it.type == c.owner }
        val hit = cls.methods.any {
            it.name == c.name &&
                it.parameterTypes.map { p -> p.toString() } == c.paramTypes &&
                it.returnType == c.returnType
        }
        assertTrue("预填的调用行指向的方法在示例 dex 里不存在", hit)
    }
}

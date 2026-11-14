package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.data.PluginPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 插件包载荷的**契约**验证。
 *
 * 防的是本工程最难查的一类失败：注入「成功」、产物正常、运行期什么都不发生。
 * 弹窗载荷要求三件事严格对齐，任何一处错位都是静默失效：
 *   1. 调用行里的类名/方法名/签名，必须与 dex 里真实存在的那个方法逐字一致；
 *   2. 那个方法必须是 `static`——不是 static 的话 `invoke-static` 会在运行时
 *      抛 IncompatibleClassChangeError，而注入阶段看不出来；
 *   3. 配置里的 assets 路径必须与载荷读取的常量一致。
 *
 * 这些都不需要真机，也不需要 Android 运行时，所以钉在 JVM 测试里。
 */
class PluginPayloadTest {

    private val dex: File
        get() = listOf(
            File("src/main/assets/plugin/PayloadDialog.dex"),
            File("app/src/main/assets/plugin/PayloadDialog.dex"),
        ).firstOrNull { it.isFile } ?: File("src/main/assets/plugin/PayloadDialog.dex")

    private val payloadClass = "Lcom/apkforge/payload/dialog/PayloadDialog;"

    @Test
    fun `调用行与载荷 dex 里的方法逐字一致`() {
        assumeTrue("内置载荷 dex 不存在，先跑 payload/dialog/build.sh", dex.isFile)

        val (calls, errs) = SmaliCallParser.parseAll(PluginPack.CALL)
        assertTrue("调用行解析失败: ${errs.messages}", errs.isEmpty)
        val call = calls.single()
        assertEquals("调用行指向的类变了？", payloadClass, call.owner)
        assertEquals("方法名必须与 dex 一致", "inject", call.name)
        assertEquals(
            "参数必须正好是一个 Activity",
            listOf("Landroid/app/Activity;"), call.paramTypes,
        )
        assertEquals("返回值必须是 V", "V", call.returnType)
        assertEquals("必须用 invoke-static", SmaliCallParser.Kind.STATIC, call.kind)

        // 必须包一层 BufferedInputStream：dexlib2 校验头部要用 mark/reset，
        // 直接喂 FileInputStream 会抛「InputStream must support mark」。
        val df = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), java.io.BufferedInputStream(dex.inputStream())
        )
        val cls = df.classes.firstOrNull { it.type == payloadClass }
        assertNotNull("载荷 dex 里没有 $payloadClass", cls)

        val sig = "(" + call.paramTypes.joinToString("") + ")" + call.returnType
        // dexlib2 的 methods 是 lazy 视图，先物化再找（本工程踩过的坑）
        val method = cls!!.methods.toList().firstOrNull { it.name == call.name && signatureOf(it) == sig }
        assertNotNull("dex 里没有 ${call.name}$sig —— 调用行会指向不存在的方法", method)

        assertTrue(
            "inject 必须是 static，否则 invoke-static 在运行时崩",
            method!!.accessFlags and 0x8 != 0,
        )
        assertTrue(
            "inject 必须是 public，否则注入到别的包后不可达",
            method.accessFlags and 0x1 != 0,
        )
        assertNotNull("载荷方法必须有方法体", method.implementation)
        println("PLUGIN: ${call.owner}->${call.name}$sig 校验通过")
    }

    @Test
    fun `载荷 dex 魔数合法且体积可控`() {
        assumeTrue(dex.isFile)
        val bytes = dex.readBytes()
        assertTrue("dex 魔数不对：${bytes.take(4)}", PluginPack.isDex(bytes))
        // 载荷会被塞进别人的 APK，体积必须克制。失真膨胀通常是误引了大依赖。
        assertTrue(
            "载荷 dex 超过 200KB（实际 ${bytes.size}），检查是否引入了额外依赖",
            bytes.size < 200 * 1024,
        )
    }

    @Test
    fun `宿主资产路径与载荷读取的常量一致`() {
        // 两处字符串分居 data 与 payload 两个模块，靠人记住一定会漂
        assertEquals("assets/" + PluginPack.PAYLOAD_ASSET_NAME, PluginPack.HOST_ASSET_PATH)
        assertEquals(
            "宿主资产文件名必须与载荷里的 ASSET_NAME 一致（大小写敏感，Android 上是区分大小写的）",
            "apkforge_dialog.json",
            PluginPack.PAYLOAD_ASSET_NAME,
        )
    }

    @Test
    fun `JSONC 去注释不吞字符串里的斜杠`() {
        val src = """
            {
              // 这是注释
              "标题": "https://example.com/a", /* 块注释 */
              "信息": "含 // 与 /* 的正文"
            }
        """.trimIndent()
        val out = PluginPack.stripComments(src)
        assertTrue("行注释没被去掉", !out.contains("这是注释"))
        assertTrue("块注释没被去掉", !out.contains("块注释"))
        assertTrue("字符串里的 // 被吃了：$out", out.contains("\"https://example.com/a\""))
        assertTrue("字符串里的 /* 被吃了：$out", out.contains("含 // 与 /* 的正文"))
    }

    private fun signatureOf(m: com.android.tools.smali.dexlib2.iface.Method): String =
        "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
}

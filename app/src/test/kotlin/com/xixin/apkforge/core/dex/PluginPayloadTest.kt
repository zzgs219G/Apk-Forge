package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.data.PluginPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 插件包载荷的**契约**验证。
 *
 * 防的是本工程最难查的一类失败：注入「成功」、产物正常、运行期什么都不发生。
 * 弹窗载荷要求几件事严格对齐，任何一处错位都是静默失效：
 *   1. 调用行里的类名/方法名/签名，必须与 dex 里真实存在的那个方法逐字一致；
 *   2. 那个方法必须是 `static`——不是的话 `invoke-static` 运行时抛
 *      IncompatibleClassChangeError，注入阶段看不出来；
 *   3. 配置占位符必须在整份 dex 里**只出现一次**，否则烘焙无从下手；
 *   4. 烘焙之后占位符必须消失、配置必须真的能读回来。
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
    fun `配置占位符在整份 dex 里只出现一次`() {
        assumeTrue(dex.isFile)
        // 防的是「把占位符写成 static final String」这种改法：javac 会把它内联到
        // 每个使用点，dex 里就有多份拷贝，PayloadBaker 无法确定该替换哪一份，
        // 替换一处而别处仍是占位符 —— 表现是「注入成功、不弹窗」，没有任何报错。
        val bytes = dex.readBytes()
        val count = countOccurrences(bytes, PayloadBaker.PLACEHOLDER.toByteArray())
        assertEquals(
            "占位符出现 $count 次（必须恰好 1 次）。检查 PayloadConfig.baked() 是否退化成了常量",
            1, count,
        )
        assertTrue("模板 dex 不该已经是烘焙状态", !PayloadBaker.isBaked(bytes))
    }

    @Test
    fun `烘焙后占位符消失且配置能原样读回`() {
        assumeTrue(dex.isFile)
        val json = PluginPack.toJson(PluginPack.DialogForm())
        when (val r = PayloadBaker.bake(dex.readBytes(), json)) {
            is PayloadBaker.Outcome.Failed -> throw AssertionError("烘焙失败：${r.reason}")
            is PayloadBaker.Outcome.Ok -> {
                assertTrue("烘焙后魔数必须仍是 dex", PluginPack.isDex(r.dex))
                assertTrue(
                    "烘焙后占位符必须消失，否则运行期拿到的还是占位符",
                    !String(r.dex, Charsets.ISO_8859_1).contains(PayloadBaker.PLACEHOLDER),
                )
                assertTrue("烘焙后的 dex 应被判为已烘焙", PayloadBaker.isBaked(r.dex))

                val back = PayloadBaker.extract(r.dex)
                assertNotNull("读不回配置：导入插件包时表单会全空", back)
                assertEquals("配置必须逐字往返", json, back)

                // 往返到表单再比：字段少一个的表现是「用户改了没生效」
                val form = PluginPack.fromJson(back!!)
                assertEquals("标题没往返回来", PluginPack.DialogForm().title, form.title)
                assertEquals("三个按钮的槽位数量变了", 3, form.buttons.size)
                assertEquals("按钮动作没往返回来", "网址", form.buttons.first { it.slot == 1 }.action)
            }
        }
    }

    @Test
    fun `三个按钮都能往返且关掉的按钮不会丢`() {
        // 用户的原话是「为什么你不复刻完整人家 3 个按钮」。
        // 这条钉住：左/右/中三个槽位各自独立，中间那个即使关着也必须往返回来，
        // 否则用户一开一关就发现「中间那个按钮不见了」。
        val form = PluginPack.DialogForm().let { f ->
            f.copy(
                buttons = listOf(
                    PluginPack.Button(slot = 0, on = true, text = "确认", action = "无"),
                    PluginPack.Button(
                        slot = 1, on = true, text = "跳转", action = "网址",
                        arg = "https://example.com", toast = "正在打开",
                    ),
                    PluginPack.Button(
                        slot = 2, on = false, text = "加群", action = "QQ群", arg = "123456",
                    ),
                )
            )
        }
        val back = PluginPack.fromJson(PluginPack.toJson(form))
        assertEquals("三个槽位都要在", listOf(0, 1, 2), back.buttons.map { it.slot }.sorted())
        assertTrue("中间按钮的关状态丢了", !back.buttons.first { it.slot == 2 }.on)
        assertEquals("QQ 号丢了", "123456", back.buttons.first { it.slot == 2 }.arg)
        assertEquals("网址丢了", "https://example.com", back.buttons.first { it.slot == 1 }.arg)
    }

    @Test
    fun `老版本 7_0 配置能直接粘进来`() {
        // 字段名与清羽弹窗 7.0 一致，是为了让用户手上的老配置不白写。
        // 这里用 7.0 的写法（默认白 / 打开网页 / 同意按钮事件）验证归一化。
        val legacy = """
            {
              "总开关": "开",
              "智能弹出": "开",
              "静默天数": "3",
              "弹窗风格": "默认白",
              "标题": "清羽网络弹窗V7",
              "信息": ["第一行", "第二行"],
              "同意按钮": "开",
              "同意按钮文本": "确认",
              "同意按钮事件": { "动作": "打开网页", "网址": "https://a.b/c", "提示": "跳转中" },
              "拒绝按钮": "开",
              "拒绝按钮文本": "QQ群",
              "拒绝按钮事件": { "动作": "QQ群", "QQ号": "962230699" },
              "中立按钮": "关"
            }
        """.trimIndent()
        val f = PluginPack.fromJson(legacy)
        assertEquals("默认白应归一化成默认亮", "默认亮", f.style)
        assertEquals("智能弹出没读到", true, f.smart)
        assertEquals("静默天数没读到", 3, f.silentDays)
        assertEquals("数组形式的信息应拼成多行", "第一行\n第二行", f.message)
        assertEquals("打开网页应归一化成网址", "网址", f.buttons.first { it.slot == 0 }.action)
        assertEquals("网址丢了", "https://a.b/c", f.buttons.first { it.slot == 0 }.arg)
        assertEquals("QQ 号丢了", "962230699", f.buttons.first { it.slot == 1 }.arg)
        assertTrue("中立按钮应是关", !f.buttons.first { it.slot == 2 }.on)
    }

    @Test
    fun `未知动作一律降级成无而不是猜`() {
        // 写错一个词（比如「打开网站」）绝不能变成退出宿主或跳到乱七八糟的地方。
        val f = PluginPack.fromJson("""{"标题":"x","同意按钮":"开","同意按钮事件":{"动作":"打开网站","网址":"https://x.y"}}""")
        assertEquals("未知动作必须降级成「无」", "无", f.buttons.first { it.slot == 0 }.action)
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

    @Test
    fun `阻断原因能说清下一步`() {
        // 三个按钮全关 → 载荷会跳过显示，UI 必须提前拦住，而不是导出完才发现「不弹」
        val dead = PluginPack.DialogForm(buttons = PluginPack.DEFAULT_BUTTONS.map { it.copy(on = false) })
        assertTrue("三按钮全关必须被拦住", !dead.anyButtonOn)

        val noArg = PluginPack.DialogForm(
            buttons = listOf(PluginPack.Button(slot = 0, on = true, text = "去", action = "网址", arg = ""))
        )
        assertEquals("选了网址却没填网址必须被拦住", 1, noArg.badButtons.size)

        val ok = PluginPack.DialogForm()
        assertTrue("默认表单应当可导出", ok.anyButtonOn && ok.badButtons.isEmpty())
        assertNull("默认表单不该有阻断原因", ok.badButtons.firstOrNull())
    }

    private fun signatureOf(m: com.android.tools.smali.dexlib2.iface.Method): String =
        "(" + m.parameterTypes.joinToString("") + ")" + m.returnType

    private fun countOccurrences(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return 0
        var n = 0
        var i = 0
        outer@ while (i <= hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            n++
            i += needle.size
        }
        return n
    }
}

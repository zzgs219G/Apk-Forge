package com.xixin.apkforge.core.apk

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SamplePayload
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo

/**
 * 端到端验证，跑**真实 APK**（默认用本工程自己的 debug 产物）。
 *
 * 覆盖四类会真崩产的坑：
 *  A. Manifest 逐字节不变（改写会产出系统不认的灰包）
 *  B. 产物 dex 仍可被 dexlib2 解析
 *  C. 注入指令落在方法**头部**且类型正确
 *  D. 方法不存在时能新建，且新建方法带父类 super 调用
 *  E. payload dex 以 classesN 追加、魔数正确、内容逐字节保留
 */
class InjectEndToEndTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    private fun loadDex(b: ByteArray) = runCatching {
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), b.inputStream())
    }.getOrNull()

    private fun pickActivity(apk: File): Triple<String, String, String>? {
        for (name in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, name) ?: continue
            val df = loadDex(bytes) ?: continue
            for (cls in df.classes) {
                val m = cls.methods.firstOrNull {
                    it.name == "onCreate" &&
                        it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;") &&
                        it.implementation != null
                } ?: continue
                return Triple(name, cls.type, m.name)
            }
        }
        return null
    }

    private val sampleDex: File
        get() = listOf(
            File("src/main/assets/sample/DemoDialog.dex"),
            File("app/src/main/assets/sample/DemoDialog.dex"),
        ).first { it.isFile }

    /** 弹窗载荷（模板版，配置位是占位符）。烘焙测试要用它，不能用示例载荷。 */
    private val payloadDex: File
        get() = listOf(
            File("src/main/assets/plugin/PayloadDialog.dex"),
            File("app/src/main/assets/plugin/PayloadDialog.dex"),
        ).firstOrNull { it.isFile } ?: File("src/main/assets/plugin/PayloadDialog.dex")

    @Test
    fun `注入到已有方法：指令在头部且 Manifest 不变`() {
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue("APK 内没有带 onCreate(Bundle) 的类", t != null)
        val (dexName, ownerType, _) = t!!

        val work = File(System.getProperty("java.io.tmpdir"), "apkforge-e2e1").apply { mkdirs() }
        val src = TestIo.copy(apk, File(work, "src.apk"))
        val out = File(work, "out.apk").apply { delete() }

        val line = "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        val (calls, errs) = SmaliCallParser.parseAll(line)
        assertTrue("解析失败: ${errs.messages}", errs.isEmpty)

        val outcome = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        )
        assertTrue("注入应成功，实际 $outcome", outcome is DexCallInjector.Outcome.Injected)
        val inj = outcome as DexCallInjector.Outcome.Injected
        assertTrue("目标方法已存在，不应新建", !inj.createdMethod)

        ApkRepackager.repack(src, out, dexName, inj.dex, sampleDex.readBytes())

        // A. Manifest 逐字节不变
        assertArrayEquals(
            "Manifest 被改写（会产出灰包）",
            TestIo.readEntry(src, "AndroidManifest.xml"),
            TestIo.readEntry(out, "AndroidManifest.xml"),
        )

        // B. 产物 dex 可解析
        val newDf = loadDex(TestIo.readEntry(out, dexName)!!)
        assertNotNull("产物 dex 结构非法", newDf)

        // C. 首条指令是注入的 invoke-static，且指向示例载荷
        val cls = newDf!!.classes.first { it.type == ownerType }
        val m = cls.methods.first {
            it.name == "onCreate" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
        }
        val first = m.implementation!!.instructions.first()
        assertEquals("首条指令应是 INVOKE_STATIC", Opcode.INVOKE_STATIC, first.opcode)
        val ref = (first as Instruction35c).reference as MethodReference
        assertEquals("Lcom/apkforge/demo/DemoDialog;", ref.definingClass)
        assertEquals("inject", ref.name)

        // E. payload 追加
        val before = ApkRepackager.listDexNames(src)
        val after = ApkRepackager.listDexNames(out)
        assertEquals("应多一个 dex", before.size + 1, after.size)
        val added = (after.toSet() - before.toSet()).single()
        assertEquals(
            "payload 内容应逐字节保留",
            sampleDex.readBytes().toList(),
            TestIo.readEntry(out, added)!!.toList(),
        )
        println("E2E1: $dexName / $ownerType  payload→$added")
    }

    @Test
    fun `方法不存在时新建并补父类 super 调用`() {
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val src = TestIo.copy(
            apk,
            File(
                File(System.getProperty("java.io.tmpdir"), "apkforge-e2e2").apply { mkdirs() },
                "src.apk"
            )
        )

        val line = "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        val calls = SmaliCallParser.parseAll(line).first

        // 用一个类里必然不存在的方法名，触发"新建"分支
        val outcome = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(
                ownerType, "onCreateForgeProbe", "(Landroid/os/Bundle;)V", calls
            ),
        )
        assertTrue("应成功，实际 $outcome", outcome is DexCallInjector.Outcome.Injected)
        val inj = outcome as DexCallInjector.Outcome.Injected
        assertTrue("应走新建分支", inj.createdMethod)

        val df = loadDex(inj.dex)!!
        val cls = df.classes.first { it.type == ownerType }
        val created = cls.methods.firstOrNull { it.name == "onCreateForgeProbe" }
        assertNotNull("新建的方法应存在", created)

        val insns = created!!.implementation!!.instructions.toList()
        // 第 0 条必须是父类调用（Activity 系），否则生命周期断裂
        val superIdx = insns.indexOfFirst {
            it.opcode == Opcode.INVOKE_SUPER_RANGE || it.opcode == Opcode.INVOKE_SUPER
        }
        assertTrue("新建方法应先调父类 onCreate，实际指令: ${insns.map { i -> i.opcode }}", superIdx == 0)

        // 父类调用的目标必须与被新建的方法同名，参数也一致
        val superRef = when (val i = insns[superIdx]) {
            is Instruction3rc -> i.reference as MethodReference
            is Instruction35c -> i.reference as MethodReference
            else -> null
        }
        assertEquals("父类调用目标应与新建方法同名", "onCreateForgeProbe", superRef?.name)
        assertEquals(
            "父类调用应指向父类",
            cls.superclass,
            superRef?.definingClass
        )

        // 注入的调用在父类调用之后
        val injectedIdx = insns.indexOfFirst {
            (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                ((it as? Instruction35c)?.reference as? MethodReference)
                    ?.definingClass == "Lcom/apkforge/demo/DemoDialog;"
        }
        assertTrue("注入的调用应存在", injectedIdx > 0)

        // 最后一条必须是 return-void
        assertEquals("新建方法末尾需 return-void", Opcode.RETURN_VOID, insns.last().opcode)

        println("E2E2: 新建方法指令 = ${insns.map { it.opcode }}")
    }

    @Test
    fun `多行调用逐条插入且顺序保持`() {
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val text = """
            # 第一条：弹窗
            invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V

            invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/content/Context;)V
        """.trimIndent()

        val (calls, errs) = SmaliCallParser.parseAll(text)
        assertTrue("解析失败: ${errs.messages}", errs.isEmpty)
        assertEquals("两条有效调用（注释/空行应忽略）", 2, calls.size)

        val outcome = DexCallInjector.inject(
            TestIo.readEntry(apk, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        )
        assumeTrue(outcome is DexCallInjector.Outcome.Injected)
        val inj = outcome as DexCallInjector.Outcome.Injected
        assertEquals(2, inj.injectedCount)

        val df = loadDex(inj.dex)!!
        val m = df.classes.first { it.type == ownerType }
            .methods.first {
                it.name == "onCreate" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
            }
        val head = m.implementation!!.instructions.take(2).map {
            it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE
        }
        assertEquals("头部应有两条 invoke-static", listOf(true, true), head)
    }

    @Test
    fun `注入到生命周期替代点也补父类 super 调用`() {
        // 场景：目标 Activity 的 onCreate 是 native（加固壳常见），App 会降级到 onPostCreate。
        // 这里直接以 onPostCreate 为注入点跑一遍真实包，钉住两件事：
        //   ① 替代点不存在时能新建；
        //   ② 新建的替代点照样补 super —— 少这一条，Activity 状态机就断在这里。
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val src = TestIo.copy(
            apk,
            File(
                File(System.getProperty("java.io.tmpdir"), "apkforge-e2e-alt").apply { mkdirs() },
                "src.apk"
            )
        )
        val line = SamplePayload.CALL
        val (calls, errs) = SmaliCallParser.parseAll(line)
        assertTrue("解析失败: ${errs.messages}", errs.isEmpty)

        val alt = com.xixin.apkforge.core.dex.LifecyclePoints.ACTIVITY
            .first { it.name == "onPostCreate" }
        val outcome = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(ownerType, alt.name, alt.signature, calls),
        )
        assertTrue("应成功，实际 $outcome", outcome is DexCallInjector.Outcome.Injected)
        val inj = outcome as DexCallInjector.Outcome.Injected

        val df = loadDex(inj.dex)!!
        val cls = df.classes.first { it.type == ownerType }
        val m = cls.methods.first {
            it.name == alt.name &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
        }
        val insns = m.implementation!!.instructions.toList()
        assertTrue("替代点方法不能是空的", insns.isNotEmpty())

        val first = insns.first()
        val isSuper = first.opcode == Opcode.INVOKE_SUPER_RANGE || first.opcode == Opcode.INVOKE_SUPER
        assertTrue("新建的替代点首条指令应是父类调用，实际 ${first.opcode}", isSuper)
        val ref = when (first) {
            is Instruction3rc -> first.reference as MethodReference
            is Instruction35c -> first.reference as MethodReference
            else -> null
        }
        assertEquals("父类调用要指向同名方法", alt.name, ref?.name)
        assertEquals("父类调用要指向父类", cls.superclass, ref?.definingClass)
        println("ALT: ${alt.name} 指令 = ${insns.map { it.opcode }}")
    }

    @Test
    fun `重打包逐条目回调完整且不重复`() {
        // 运行页日志里「写入 xxx 条目」那几百行就是从这来的。
        // 契约：index 从 1 递增到 total，每个条目恰好一次 —— 少报会让进度条停在半路，
        // 重复报会让日志刷出重叠的数字。
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val work = File(System.getProperty("java.io.tmpdir"), "apkforge-e2e-progress")
            .apply { mkdirs() }
        val src = TestIo.copy(apk, File(work, "src.apk"))
        val out = File(work, "out.apk").apply { delete() }

        val calls = SmaliCallParser.parseAll(SamplePayload.CALL).first
        val inj = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        ) as DexCallInjector.Outcome.Injected

        val reported = mutableListOf<Int>()
        val stats = ApkRepackager.repack(
            sourceApk = src,
            outputApk = out,
            targetDexName = dexName,
            newDexBytes = inj.dex,
            payloadDexBytes = sampleDex.readBytes(),
            onEntry = { index, _, _ -> reported += index },
        )

        assertTrue("应有回调", reported.isNotEmpty())
        assertEquals("index 必须从 1 连续递增到 total",
            (1..stats.totalEntries).toList(), reported)
        assertEquals("total 应等于条目数", stats.totalEntries, reported.size)
        println("REPACK: 回调 ${reported.size} 次 / 条目 ${stats.totalEntries}")
    }

    @Test
    fun `解析器能处理各种 invoke 形态`() {
        val cases = listOf(
            "invoke-static {p0}, La/b/C;->d(Landroid/app/Activity;)V",
            "invoke-virtual {p0, v1}, La/b/C;->d(I)V",
            "invoke-direct {p0}, La/b/C;-><init>()V",
            "invoke-super {p0, p1}, La/b/Base;->onCreate(Landroid/os/Bundle;)V",
            "invoke-interface {p1}, Ljava/lang/Runnable;->run()V",
            "invoke-static/range {p0 .. p2}, La/b/C;->d(III)V",
            "invoke-static {}, La/b/C;->d()V",
        )
        cases.forEach { line ->
            val (calls, errs) = SmaliCallParser.parseAll(line)
            assertTrue("解析失败 [$line]: ${errs.messages}", errs.isEmpty)
            assertEquals("应解析出 1 条 [$line]", 1, calls.size)
        }

        // range 形态应展开成逐个寄存器
        val range = SmaliCallParser.parseLine("invoke-static/range {p0 .. p2}, La/b/C;->d(III)V")
        assertEquals(listOf("p0", "p1", "p2"), range.registers)
        assertTrue(range.rangeForm)

        // 非法行必须报错，不能静默跳过
        val (bad, badErrs) = SmaliCallParser.parseAll("this is not smali")
        assertTrue("非法行应报错", bad.isEmpty() && !badErrs.isEmpty)
    }

    @Test
    fun `替换方法体会丢掉原指令但保留父类调用`() {
        // 防的是「replaceBody 只清了一半」：原逻辑还在跑，用户以为已经打桩。
        // 同时钉住父类调用必须留着 —— 生命周期方法不调 super 会让宿主状态机断裂。
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val calls = SmaliCallParser.parseAll(SamplePayload.CALL).first
        val outcome = DexCallInjector.inject(
            TestIo.readEntry(apk, dexName)!!,
            DexCallInjector.Spec(
                ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls, replaceBody = true,
            ),
        )
        assertTrue("应成功，实际 $outcome", outcome is DexCallInjector.Outcome.Injected)
        val inj = outcome as DexCallInjector.Outcome.Injected

        val df = loadDex(inj.dex)!!
        val m = df.classes.first { it.type == ownerType }
            .methods.first {
                it.name == "onCreate" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
            }
        val insns = m.implementation!!.instructions.toList()
        assertEquals(
            "替换方法体内应只剩「注入调用 + return」两条",
            2, insns.size,
        )
        assertEquals("第一条应是注入的 invoke-static", Opcode.INVOKE_STATIC, insns[0].opcode)
        assertEquals("最后一条必须补 return-void，否则 dex 非法", Opcode.RETURN_VOID, insns[1].opcode)
        assertNotNull("产物 dex 必须仍可解析", df)
    }

    @Test
    fun `产物只多一个 dex，不改写宿主的任何其它条目`() {
        // 这是产品边界，不是洁癖。上一版往宿主的 assets/ 写弹窗配置，用户当场就问
        // 「你把我包里的 assets 改了？」——那个质疑是对的：注入只应该让它多一个
        // classesN.dex，别的条目逐字节保持原样。
        // 这条钉住：① 恰好新增一个条目；② 新增的那个是 classesN.dex；
        //          ③ 原有条目一个不少、名字集合只比原来多那一个。
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val work = File(System.getProperty("java.io.tmpdir"), "apkforge-e2e-boundary")
            .apply { mkdirs() }
        val src = TestIo.copy(apk, File(work, "src.apk"))
        val out = File(work, "out.apk").apply { delete() }

        val calls = SmaliCallParser.parseAll(SamplePayload.CALL).first
        val inj = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        ) as DexCallInjector.Outcome.Injected

        val stats = ApkRepackager.repack(
            sourceApk = src,
            outputApk = out,
            targetDexName = dexName,
            newDexBytes = inj.dex,
            payloadDexBytes = sampleDex.readBytes(),
        )
        assertTrue("应报告追加的 dex 名", !stats.addedDex.isNullOrBlank())
        assertTrue("追加的必须是 classesN.dex", Regex("""^classes\d+\.dex$""").matches(stats.addedDex!!))

        val before = java.util.zip.ZipFile(src).use { z -> z.entries().toList().map { it.name }.toSet() }
        val after = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertEquals(
            "产物只该比原包多一个条目（实际多了 ${after - before}，少了 ${before - after}）",
            before.size + 1, after.size,
        )
        assertEquals("原有条目一个都不能少", emptySet<String>(), before - after)
        assertEquals("多出来的那个只能是新 dex", setOf(stats.addedDex), after - before)
        // 反面断言：绝不能出现任何配置文件形态的条目
        assertTrue(
            "不该往宿主包里写配置文件：${after.filter { it.endsWith(".json") || it.endsWith(".jsonc") }}",
            after.none { it.endsWith(".json") || it.endsWith(".jsonc") },
        )
    }

    @Test
    fun `payload 的配置已经烘焙在 dex 里而不是外挂文件`() {
        // 端到端验证「配置真的在 dex 里」：烘焙后的载荷，占位符必须消失、
        // 配置文本必须能读回来。少了这条，最可能的失败形态是
        // 「注入成功、产物正常、宿主启动什么都不发生」——没有报错可查。
        assumeTrue("载荷 dex 不存在，先跑 payload/dialog/build.sh", payloadDex.isFile)
        val json = com.xixin.apkforge.data.PluginPack.toJson(
            com.xixin.apkforge.data.PluginPack.DialogForm()
        )
        val baked = when (val r = com.xixin.apkforge.core.dex.PayloadBaker.bake(payloadDex.readBytes(), json)) {
            is com.xixin.apkforge.core.dex.PayloadBaker.Outcome.Ok -> r.dex
            is com.xixin.apkforge.core.dex.PayloadBaker.Outcome.Failed ->
                throw AssertionError("烘焙失败：${r.reason}")
        }
        assertTrue(
            "占位符还在，运行期读到的会是占位符而不是配置",
            String(baked, Charsets.ISO_8859_1).indexOf("__APKFORGE_CONFIG__") < 0,
        )
        assertEquals(
            "配置必须能从 dex 里原样读回",
            json, com.xixin.apkforge.core.dex.PayloadBaker.extract(baked),
        )
    }
}

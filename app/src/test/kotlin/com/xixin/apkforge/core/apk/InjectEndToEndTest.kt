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
    fun `额外资产写进包内且同名不重复`() {
        // 弹窗载荷靠 assets/apkforge_dialog.json 找配置。这条钉住两件事：
        //   ① 资产真的进包了（否则载荷静默不弹）；
        //   ② 包内原本有同名条目时是**替换**而不是写第二份
        //      （zip 里两个同路径条目，AssetManager 取哪个是未定义的）。
        val apk = locateApk()
        val t = pickActivity(apk)
        assumeTrue(t != null)
        val (dexName, ownerType, _) = t!!

        val work = File(System.getProperty("java.io.tmpdir"), "apkforge-e2e-assets")
            .apply { mkdirs() }
        val src = TestIo.copy(apk, File(work, "src.apk"))
        val out = File(work, "out.apk").apply { delete() }
        val out2 = File(work, "out2.apk").apply { delete() }

        val calls = SmaliCallParser.parseAll(SamplePayload.CALL).first
        val inj = DexCallInjector.inject(
            TestIo.readEntry(src, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        ) as DexCallInjector.Outcome.Injected

        val payload = sampleDex.readBytes()
        val assets = mapOf("assets/apkforge_dialog.json" to """{"标题":"测试"}""".toByteArray())
        val stats = ApkRepackager.repack(
            sourceApk = src,
            outputApk = out,
            targetDexName = dexName,
            newDexBytes = inj.dex,
            payloadDexBytes = payload,
            extraEntries = assets,
        )
        assertTrue("应报告写入的资产", stats.addedAssets.contains("assets/apkforge_dialog.json"))
        val landed = TestIo.readEntry(out, "assets/apkforge_dialog.json")
        assertNotNull("资产没进包", landed)
        assertEquals("资产内容应逐字节保留", """{"标题":"测试"}""", String(landed!!))

        // 再跑一次：同名资产必须是替换
        val stats2 = ApkRepackager.repack(
            sourceApk = out,
            outputApk = out2,
            targetDexName = dexName,
            newDexBytes = inj.dex,
            payloadDexBytes = null,
            extraEntries = mapOf("assets/apkforge_dialog.json" to """{"标题":"二次"}""".toByteArray()),
        )
        assertTrue("替换已有资产也要报告", stats2.addedAssets.contains("assets/apkforge_dialog.json"))
        assertEquals(
            "同名资产应被替换而不是追加",
            """{"标题":"二次"}""",
            String(TestIo.readEntry(out2, "assets/apkforge_dialog.json")!!),
        )
        val names = java.util.zip.ZipFile(out2).use { z -> z.entries().toList().map { it.name } }
        assertEquals(
            "zip 内不该出现重复的同名条目",
            1, names.count { it == "assets/apkforge_dialog.json" },
        )
    }
}

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
}

package com.xixin.apkforge.core.dex

import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 幂等保护：方法开头已经是这条调用时，不许再插一次。
 *
 * 【为什么这条值得单独测】重复注入不会崩、不会报错，只是让载荷**执行两次**。
 * 这类「行为翻倍」的失败没有任何日志可查，用户只会觉得工具坏了。
 * UI 的 `running` 状态挡不住「从历史记录复用再跑」和「换套配置重跑」。
 */
class InjectIdempotencyTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "apkforge-idem-$tag").apply { mkdirs() }

    private val callLine =
        "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"

    /** 找一个带 onCreate(Bundle) 的类。 */
    private fun pickActivity(apk: File): Pair<String, String>? {
        for (name in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, name) ?: continue
            val df = runCatching {
                com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                    DexOpcodes.forBytes(bytes), bytes.inputStream()
                )
            }.getOrNull() ?: continue
            for (cls in df.classes) {
                val m = cls.methods.firstOrNull {
                    it.name == "onCreate" &&
                        it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;") &&
                        it.implementation != null
                } ?: continue
                return name to cls.type
            }
        }
        return null
    }

    private fun spec(ownerType: String, calls: List<SmaliCallParser.Call> = parse(callLine)) =
        DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls)

    private fun parse(text: String) = SmaliCallParser.parseAll(text).first

    /** 跑一次真实注入，产出「已经注过一次」的 APK。 */
    private fun injectOnce(apk: File, tag: String): Triple<File, String, String> {
        val (dexName, ownerType) = pickActivity(apk) ?: error("没有可注入的类")
        val outcome = DexCallInjector.inject(
            TestIo.readEntry(apk, dexName)!!,
            spec(ownerType),
        )
        assertTrue("前置注入应成功", outcome is DexCallInjector.Outcome.Injected)

        val out = File(workDir(tag), "once.apk").apply { delete() }
        ApkRepackager.repack(
            apk, out, dexName,
            (outcome as DexCallInjector.Outcome.Injected).dex, null,
        )
        return Triple(out, dexName, ownerType)
    }

    /** 干净包应判为可注入。 */
    @Test
    fun `干净的包判为可注入`() {
        val apk = locateApk()
        val picked = pickActivity(apk)
        assumeTrue("没有可注入的类", picked != null)

        val verdict = InjectIdempotency.check(apk, spec(picked!!.second))
        assertEquals("干净包应判为 Clean，实际 $verdict", InjectIdempotency.Verdict.Clean, verdict)
    }

    /** 已注过一次的产物，再注一次必须被识别为重复。 */
    @Test
    fun `注过一次之后再检查应判为重复`() {
        val apk = locateApk()
        assumeTrue("没有可注入的类", pickActivity(apk) != null)
        val (injected, _, ownerType) = injectOnce(apk, "once")

        val verdict = InjectIdempotency.check(injected, spec(ownerType))
        assertTrue(
            "应识别出重复注入，实际 $verdict",
            verdict is InjectIdempotency.Verdict.AlreadyInjected,
        )
        assertEquals(
            "应报出已有的条数",
            1,
            (verdict as InjectIdempotency.Verdict.AlreadyInjected).existingCount,
        )
    }

    /** 换一条**不同**的调用行，是合法的新增，不该被拦。 */
    @Test
    fun `换一条不同的调用行不算重复`() {
        val apk = locateApk()
        assumeTrue("没有可注入的类", pickActivity(apk) != null)
        val (injected, _, ownerType) = injectOnce(apk, "different")

        val other = parse("invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->other(Landroid/app/Activity;)V")
        val verdict = InjectIdempotency.check(injected, spec(ownerType, other))
        assertEquals(
            "换了方法名应判为可注入（用户可能真的想插两条不同调用）",
            InjectIdempotency.Verdict.Clean,
            verdict,
        )
    }

    /** 参数类型不同也算不同调用——签名是契约的一部分。 */
    @Test
    fun `同名但签名不同的调用不算重复`() {
        val apk = locateApk()
        assumeTrue("没有可注入的类", pickActivity(apk) != null)
        val (injected, _, ownerType) = injectOnce(apk, "sig")

        val other = parse("invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/content/Context;)V")
        val verdict = InjectIdempotency.check(injected, spec(ownerType, other))
        assertEquals(
            "参数类型不同应判为可注入",
            InjectIdempotency.Verdict.Clean,
            verdict,
        )
    }

    /** 目标方法不存在时给 Unknown，让下游按「新建方法」的正常流程处理。 */
    @Test
    fun `目标方法不存在时不下结论`() {
        val apk = locateApk()
        val picked = pickActivity(apk)
        assumeTrue("没有可注入的类", picked != null)

        val verdict = InjectIdempotency.check(
            apk,
            DexCallInjector.Spec(picked!!.second, "onCreateForgeProbeNotHere", "()V", parse(callLine)),
        )
        assertEquals(
            "方法不存在应给 Unknown（下游会新建），不能误判成重复",
            InjectIdempotency.Verdict.Unknown,
            verdict,
        )
    }

    /**
     * 端到端：对产物再跑一次流水线，落点必须被跳过。
     *
     * 这条才是用户真实路径——点两次「开始注入」。
     *
     * ⚠️ 必须显式传 `targetClass`：不传的话流水线会自动取 Manifest 的 LAUNCHER
     * （`MainActivity`），而 `injectOnce` 注入的是「第一个带 onCreate 的类」，
     * 两者未必同一个。测试里混用会导致「检查的类 ≠ 注入的类」，
     * 从而误判成「没注过」——这不是产品 bug，是测试自己找错了靶子。
     */
    @Test
    fun `对产物再跑一次流水线应跳过注入`() = kotlinx.coroutines.runBlocking {
        val apk = locateApk()
        assumeTrue("没有可注入的类", pickActivity(apk) != null)
        val (injected, _, ownerType) = injectOnce(apk, "pipeline")

        val out = File(workDir("pipeline"), "second.apk").apply { delete() }
        val result = com.xixin.apkforge.core.apk.ApkInjectPipeline().run(
            com.xixin.apkforge.core.apk.ApkInjectPipeline.Input(
                sourceApk = injected,
                outputApk = out,
                // 钉住与 injectOnce 相同的类，否则自动解析可能选到另一个 Activity
                targetClass = ownerType.trimStart('L').trimEnd(';').replace('/', '.'),
                targetMethod = "onCreate",
                targetSignature = "(Landroid/os/Bundle;)V",
                callsText = callLine,
            )
        )

        assertFalse("全部落点都跳过时不该报成功（会让人以为注入了）", result.ok)
        assertTrue(
            "错误信息应说清「已经注过」，实际：${result.error}",
            result.error.orEmpty().contains("已经是我们这条调用") ||
                result.error.orEmpty().contains("没有可注入的落点"),
        )
        assertTrue(
            "应留下「跳过重复注入」的日志，实际：${result.steps.map { it.label }}",
            result.steps.any { it.label.contains("跳过重复注入") },
        )
        assertTrue("不该产出文件", !out.isFile || out.length() == 0L)
    }
}

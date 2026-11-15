package com.xixin.apkforge.core.apk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 流水线本身的端到端验证。
 *
 * 【为什么这条以前不存在】`ApkInjectPipeline` 原来持有一个 `private val context: Context`
 * （一次都没用到），于是整个类不能出现在 JVM 单测里——`core/` 里一行 `android.*`
 * 就把「核心逻辑可在纯 JVM 上跑」变成了空话（AGENTS.md §1.1）。
 * 去掉 Context 后这条测试才写得出来，它覆盖的是**编排**而不是零件：
 * 选点 → 注入 → 落盘，以及产物边界（只多一个 dex）。
 */
class PipelineEndToEndTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    private val sampleDex: File
        get() = listOf(
            File("src/main/assets/sample/DemoDialog.dex"),
            File("app/src/main/assets/sample/DemoDialog.dex"),
        ).first { it.isFile }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "apkforge-pipeline-$tag").apply { mkdirs() }

    private fun input(apk: File, out: File) = ApkInjectPipeline.Input(
        sourceApk = apk,
        outputApk = out,
        targetClass = null,      // 自动取 Manifest 的 LAUNCHER
        targetMethod = "",       // 自动挑注入点
        callsText = "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V",
        payloadDex = sampleDex,
    )

    @Test
    fun `整条流水线跑通并只多一个 dex`() = runBlocking {
        val apk = locateApk()
        val out = File(workDir("happy"), "out.apk").apply { delete() }
        val steps = mutableListOf<ApkInjectPipeline.Step>()

        val result = ApkInjectPipeline().run(input(apk, out)) { steps += it }

        assertTrue("流水线应成功，实际 error=${result.error}", result.ok)
        assertNotNull("应记录产物路径", result.outputApk)
        assertEquals("应只注入一次（ONCE 模式）", 1, result.targets.size)
        assertTrue("应报告注入条数", result.injectedCount > 0)
        assertTrue("应解析出包名", !result.packageName.isNullOrBlank())
        assertTrue("应记录耗时", result.durationMs > 0)

        // 进度必须单调不减（坑 22：进度条不许「补齐」，也不许倒退）。
        // 上限只到 REPACK 之后：SAVE 段（写 Download）由 ViewModel 负责，
        // 流水线本身不落盘，所以这里不该要求它收到 1.0。
        var last = 0f
        steps.forEach {
            assertTrue("进度倒退：${it.label} ${it.progress} < $last", it.progress >= last)
            last = it.progress
        }
        assertTrue("流水线跑完应至少推进到 REPACK 段末尾以上，实际 $last", last >= 0.9f)
        assertTrue("流水线不该自己报 SAVE 段的进度", steps.none { it.stage == ApkInjectPipeline.Stage.SAVE })

        // 产物边界：条目 +1，且多出来的只能是 classesN.dex（坑 28）
        val before = ZipFile(apk).use { z -> z.entries().toList().map { it.name }.toSet() }
        val after = ZipFile(out).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertEquals("产物应恰好比源包多一个条目", before.size + 1, after.size)
        val added = after - before
        assertEquals("多出来的只能是一个条目", 1, added.size)
        assertTrue("新增条目必须是 classesN.dex，实际 $added",
            Regex("""^classes\d+\.dex$""").matches(added.first()))
        assertTrue("原包条目一个都不能少", after.containsAll(before))
        assertTrue("不许出现 json 资产", after.none { it.endsWith(".json") || it.endsWith(".jsonc") })
    }

    /**
     * 结构性约束：`Stage` 的声明顺序就是进度的推进顺序，
     * 所以流水线 emit 的 Step 序列，其 Stage 序号**不许倒退**。
     *
     * 这条抓的是一类很隐蔽的错：某个步骤挂到了后面的 Stage 上
     * （比如「解析调用行」挂到 `INJECT`，但它发生在 `LOCATE` 之前），
     * 进度就会先跳后缩。UI 用 `maxOf` 掩盖了它，界面上看不出来，
     * 只有对着 Step 序列做断言才抓得到。
     */
    @Test
    fun `Step 的阶段序号不许倒退`() = runBlocking {
        val apk = locateApk()
        val out = File(workDir("order"), "out.apk").apply { delete() }
        val steps = mutableListOf<ApkInjectPipeline.Step>()

        ApkInjectPipeline().run(input(apk, out)) { steps += it }

        val order = ApkInjectPipeline.Stage.entries
        var lastIdx = -1
        steps.forEach { s ->
            val idx = order.indexOf(s.stage)
            assertTrue(
                "阶段倒退：${s.stage}（第 $idx 段）出现在第 $lastIdx 段之后 —— ${s.label}",
                idx >= lastIdx,
            )
            lastIdx = idx
        }
    }

    /**
     * 失败路径也要能说清「下一步」。
     *
     * 不在 steps 里塞一句「注入失败」就完事——用户要的是「哪里错了、该改什么」。
     */
    @Test
    fun `调用行语法错误时给可执行的原因`() = runBlocking {
        val apk = locateApk()
        val out = File(workDir("badcall"), "out.apk").apply { delete() }
        val result = ApkInjectPipeline().run(
            input(apk, out).copy(callsText = "invoke-static {p0}, 这不是 smali")
        )
        assertTrue("应失败", !result.ok)
        val err = result.error.orEmpty()
        assertTrue("错误信息应指出是调用解析失败，实际：$err", err.contains("调用解析失败") || err.contains("没有解析出"))
        assertTrue("失败时不该产出文件", !out.isFile || out.length() == 0L)
    }

    /** 类名不存在时，错误里必须带上是哪个类——否则用户无从下手。 */
    @Test
    fun `类名找不到时报出类名本身`() = runBlocking {
        val apk = locateApk()
        val out = File(workDir("badclass"), "out.apk").apply { delete() }
        val bogus = "com.apkforge.definitely.NotHere"
        val result = ApkInjectPipeline().run(input(apk, out).copy(targetClass = bogus))
        assertTrue("应失败", !result.ok)
        assertTrue(
            "错误应包含完整类名，实际：${result.error}",
            result.error.orEmpty().contains(bogus),
        )
    }
}

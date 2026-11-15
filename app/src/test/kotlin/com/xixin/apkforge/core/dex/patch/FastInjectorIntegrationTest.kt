package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.apk.ApkInjectPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接入后的行为验证：流水线必须走**增量**路径，而不是每次都悄悄回退。
 *
 * 【为什么这条必须单独测】`FastInjector` 的设计是「不适用就回退」，
 * 于是「回退了」和「根本没接上」在结果上完全一样——都是成功产出包。
 * 只有断言日志里出现「注入调用（增量）」且**没有**出现「增量不适用」，
 * 才能证明快路径真的生效。
 */
class FastInjectorIntegrationTest {

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
        File(System.getProperty("java.io.tmpdir"), "apkforge-fast-$tag").apply { mkdirs() }

    /**
     * 载荷类不在宿主的 dex 里，所以「引用不在池」是本项目的**正常情况**，
     * 增量必然不适用。这条以此钉住：回退必须有明确原因，且不能崩。
     */
    @Test
    fun `外部载荷引用应回退全量并说明原因`() = runBlocking {
        val apk = locateApk()
        val out = File(workDir("fallback"), "out.apk").apply { delete() }
        val steps = mutableListOf<ApkInjectPipeline.Step>()

        val r = ApkInjectPipeline().run(
            ApkInjectPipeline.Input(
                sourceApk = apk,
                outputApk = out,
                callsText = "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V",
                payloadDex = sampleDex,
            )
        ) { steps += it }

        assertTrue("应成功（回退全量后仍然成功），实际 error=${r.error}", r.ok)

        val fallback = steps.filter { it.label.contains("增量不适用") }
        assertTrue("应有回退说明，实际步骤：${steps.map { it.label }}", fallback.isNotEmpty())
        assertTrue(
            "回退原因应说清是引用不在池里，实际：${fallback.map { it.detail }}",
            fallback.any { it.detail.contains("方法池") || it.detail.contains("池条目") },
        )
    }

    /**
     * 引用**在本 dex 内**时，增量必须生效。
     *
     * 做法：不追加外部 payload，直接调用目标类所在 dex 里已有的一个 static 方法。
     *
     * ⚠️ 必须同 dex：`method_idx` 是**每个 dex 独立**的编号，
     * 跨 dex 引用意味着那个方法在本次要改的 dex 里没有编号，
     * 增量写入（不改池）无法表达，只能走全量。
     */
    @Test
    fun `引用在池内时应走增量路径`() = runBlocking {
        val apk = locateApk()

        // 先定位流水线会选中的类（LAUNCHER），并拿到它所在的 dex
        val target = findTargetWithStaticMethod(apk)
            ?: run {
                org.junit.Assume.assumeTrue("找不到「LAUNCHER 类同 dex 内有 static 方法」的组合", false)
                return@runBlocking
            }

        val out = File(workDir("fast"), "out.apk").apply { delete() }
        val steps = mutableListOf<ApkInjectPipeline.Step>()

        val r = ApkInjectPipeline().run(
            ApkInjectPipeline.Input(
                sourceApk = apk,
                outputApk = out,
                callsText = "invoke-static {}, ${target.second}->${target.third}()V",
                payloadDex = null,
            )
        ) { steps += it }

        assertTrue("应成功，实际 error=${r.error}", r.error == null)
        val fast = steps.filter { it.label.contains("注入调用（增量）") }
        val fallback = steps.filter { it.label.contains("增量不适用") }

        assertTrue(
            "应走增量路径，实际回退原因：${fallback.map { it.detail }}",
            fast.isNotEmpty() && fallback.isEmpty(),
        )
        assertEquals("增量路径应只注入 1 条", 1, r.injectedCount)
    }

    /**
     * 找一组「LAUNCHER 类 + 同 dex 内的无参 static 方法」。
     *
     * 返回 (dexName, 被调用类描述符, 被调用方法名)。
     */
    private fun findTargetWithStaticMethod(apk: File): Triple<String, String, String>? {
        // 先找 LAUNCHER 类在哪个 dex
        val manifest = com.xixin.apkforge.core.apk.ApkRepackager
            .readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) }
        val launcher = manifest?.primaryLauncher ?: return null
        val launcherType = "L${launcher.replace('.', '/')};"

        for (dexName in com.xixin.apkforge.core.apk.ApkRepackager.listDexNames(apk)) {
            val bytes = com.xixin.apkforge.support.TestIo.readEntry(apk, dexName) ?: continue
            val df = runCatching {
                com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                    com.xixin.apkforge.core.dex.DexOpcodes.forBytes(bytes), bytes.inputStream()
                )
            }.getOrNull() ?: continue
            // 这个 dex 里有没有 LAUNCHER 类？
            if (df.classes.none { it.type == launcherType }) continue
            // 有就从这个 dex 里挑一个无参 static 方法
            for (cls in df.classes) {
                for (m in cls.methods) {
                    if (m.accessFlags and 0x8 != 0 && m.parameterTypes.isEmpty() &&
                        m.returnType == "V" && m.implementation != null &&
                        m.implementation!!.tryBlocks.isEmpty()
                    ) {
                        return Triple(dexName, cls.type, m.name)
                    }
                }
            }
        }
        return null
    }

    /** 找一个本 dex 内已有的无参 static 方法。 */
    private fun findStaticMethod(apk: File): Pair<String, String>? {
        for (dexName in com.xixin.apkforge.core.apk.ApkRepackager.listDexNames(apk)) {
            val bytes = com.xixin.apkforge.support.TestIo.readEntry(apk, dexName) ?: continue
            val df = runCatching {
                com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                    com.xixin.apkforge.core.dex.DexOpcodes.forBytes(bytes), bytes.inputStream()
                )
            }.getOrNull() ?: continue
            for (cls in df.classes) {
                for (m in cls.methods) {
                    if (m.accessFlags and 0x8 != 0 && m.parameterTypes.isEmpty() &&
                        m.returnType == "V" && m.implementation != null &&
                        m.implementation!!.tryBlocks.isEmpty()
                    ) {
                        return cls.type to m.name
                    }
                }
            }
        }
        return null
    }
}

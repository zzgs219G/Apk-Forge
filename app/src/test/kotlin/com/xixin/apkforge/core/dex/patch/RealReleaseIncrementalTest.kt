package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实 release 包上**真正走到增量路径**的验证。
 *
 * 【为什么需要这条】`RealReleaseApkTest` 里的靶子是「同 dex 内任意 static 方法」，
 * 但那只覆盖了「方法池里有」这一个条件。用户的实际场景是：
 * 注入 `MainActivity` 的 `onCreate`，调用一个**宿主自己**的方法。
 * 这条用日志里那个真实的包、真实的类、真实的注入点来验证。
 *
 * 判别标准来自 `docs/日志.md` 的那次运行：
 * 注入外部 payload（`Lcom/apkforge/demo/DemoDialog;`）会回退全量，
 * 因为 `method_idx` 是 per-dex 的，外部类在宿主池里没有编号。
 */
class RealReleaseIncrementalTest {

    private fun locateReleaseApk(): File? {
        val p = System.getProperty("apkforge.releaseApk") ?: System.getenv("APKFORGE_RELEASE_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return File(System.getProperty("user.home"), "sample-release.apk").takeIf { it.isFile }
    }

    /** 取 LAUNCHER 所在的 dex 与类。 */
    private fun launcherDex(apk: File): Triple<String, ByteArray, String>? {
        val manifest = ApkRepackager.readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) } ?: return null
        val launcher = manifest.primaryLauncher ?: return null
        val type = "L${launcher.replace('.', '/')};"
        for (dexName in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, dexName) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            }.getOrNull() ?: continue
            if (df.classes.any { it.type == type }) return Triple(dexName, bytes, type)
        }
        return null
    }

    /**
     * 调用宿主自己已有的方法 → 增量必须生效。
     *
     * 这正是「注入一次弹窗」之外的真实用法：想在 `onCreate` 里调自己的初始化函数。
     */
    @Test
    fun `调用宿主自身方法时增量生效`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val (dexName, dexBytes, launcherType) = launcherDex(apk!!)
            ?: run { assumeTrue("找不到 LAUNCHER dex", false); return }

        // 用宿主自己的 <clinit>（无参 static，必然存在）作为被调用方
        val spec = DexCallInjector.Spec(
            ownerType = launcherType,
            methodName = "onCreate",
            methodSignature = "(Landroid/os/Bundle;)V",
            calls = SmaliCallParser.parseAll(
                "invoke-static {}, $launcherType-><clinit>()V"
            ).first,
        )

        val t0 = System.nanoTime()
        val fast = FastInjector.tryInject(dexBytes, spec)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertTrue(
            "调用宿主自身方法时应走增量。实际：$fast",
            fast is FastInjector.Result.Injected,
        )
        val ok = fast as FastInjector.Result.Injected
        println("INC dex=$dexName ${dexBytes.size / 1048576}MB 耗时=${ms}ms ${ok.note}")

        // 产物结构自检
        val newDf = runCatching {
            DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(ok.dex), ok.dex.inputStream())
        }.getOrNull()
        assertNotNull("产物 dex 非法", newDf)

        val cls = newDf!!.classes.first { it.type == launcherType }
        val m = cls.methods.first {
            it.name == "onCreate" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
        }
        assertEquals(
            "首条指令应是 INVOKE_STATIC",
            com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
            m.implementation!!.instructions.first().opcode,
        )
        println("INC 首条指令已确认为 INVOKE_STATIC")
    }

    /**
     * 对照组：注入外部 payload 时必须回退，且原因说清是「池里没有」。
     *
     * 这条不是测试失败，而是**钉住既有事实**——`docs/日志.md` 里那次
     * 19 秒就是这么来的。若哪天它突然「成功走增量」了，说明池被改动过，
     * 需要重新审查。
     */
    @Test
    fun `注入外部 payload 必须回退并说明原因`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val (_, dexBytes, launcherType) = launcherDex(apk!!)
            ?: run { assumeTrue("找不到 LAUNCHER dex", false); return }

        val spec = DexCallInjector.Spec(
            ownerType = launcherType,
            methodName = "onCreate",
            methodSignature = "(Landroid/os/Bundle;)V",
            calls = SmaliCallParser.parseAll(
                "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
            ).first,
        )
        val r = FastInjector.tryInject(dexBytes, spec)
        assertTrue("外部 payload 应回退，实际：$r", r is FastInjector.Result.NotApplicable)
        val reason = (r as FastInjector.Result.NotApplicable).reason
        assertTrue("原因应指出方法池问题，实际：$reason", reason.contains("方法池") || reason.contains("池条目"))
        println("INC 回退原因（预期行为）：$reason")
    }
}

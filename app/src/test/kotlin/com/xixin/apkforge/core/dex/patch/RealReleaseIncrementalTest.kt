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
        // 优先用 assembleRelease 的产物：它就是本 App 的正式包，
        // 3 个 dex / classes.dex 29.9MB / 16722 类 / dex 039 / R8 混淆形态，
        // 与线上发布的一致。用自己构建的包当靶子，不依赖外部样本。
        val candidates = buildList {
            add(File("build/outputs/apk/release/app-release-unsigned.apk"))
            add(File("app/build/outputs/apk/release/app-release-unsigned.apk"))
            add(File("../app/build/outputs/apk/release/app-release-unsigned.apk"))
            System.getProperty("user.home")?.let { add(File(it, "sample-release.apk")) }
            add(File("sample-release.apk"))
            add(File("app/sample-release.apk"))
            add(File("../sample-release.apk"))
        }
        return candidates.firstOrNull { it.isFile }
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
     * 真实包上的对照组：注入外部 payload。
     *
     * 【这条曾经断言「必须回退」】写池注入器之前，外部引用必然回退
     * （`method_idx` 是 per-dex 编号，宿主池里没有该条目）。
     * 现在 `PoolInjector` 能往池里加条目，限制解除，断言反转为「必须走增量」。
     *
     * 用的正是 `docs/日志.md` 里那次 19 秒的同一个包、同一条调用行。
     */
    @Test
    fun `注入外部 payload 现在应走池注入`() {
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

        val t0 = System.nanoTime()
        val r = FastInjector.tryInject(dexBytes, spec)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertTrue(
            "外部 payload 现在应走池注入，实际：$r",
            r is FastInjector.Result.Injected,
        )
        val ok = r as FastInjector.Result.Injected
        println("INC 外部 payload 走增量：${ms}ms · ${ok.note}")

        // 产物必须可解析，且首条指令是插入的那条
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(ok.dex), ok.dex.inputStream())
        val cls = df.classes.first { it.type == launcherType }
        val m = cls.methods.first {
            it.name == "onCreate" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
        }
        assertEquals(
            "首条指令应是 INVOKE_STATIC",
            com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
            m.implementation!!.instructions.first().opcode,
        )
        // 早期错误版本是 0.3 秒，但产物装上闪退（注解与指令索引未重映射）。
        // 补齐后约 1~2 秒，仍比全量 9 秒快数倍。
        assertTrue("应在 5 秒内完成（原来全量要 ~10 秒），实际 ${ms}ms", ms < 5000)
    }
}

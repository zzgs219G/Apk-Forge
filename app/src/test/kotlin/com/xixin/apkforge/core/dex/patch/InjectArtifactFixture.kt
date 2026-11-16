package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 夹具：产出**和真实注入流程完全一致**的 APK，供装机验证。
 *
 * ## 为什么必须走真实流程
 *
 * 早先这个夹具只做了「替换被改的那个 dex」，没有追加 payload dex。
 * 结果产物里 `invoke` 指向的 `DemoDialog` 根本不存在，
 * 这样即使 dex 格式全对，装上去也必然闪退 —— 而那种失败跟
 * 「dex 格式错」表现一样（都是启动即崩），极易误判。
 *
 * 所以这里调 [ApkRepackager.repack]，和 App 里走的是同一段代码：
 * 替换目标 dex + 追加 payload 为新的 `classesN.dex` + 保持对齐。
 *
 * ## 输入
 *
 * - `~/fixture-in.apk`  ：待注入的 APK
 * - `~/fixture-payload.dex`：payload（含 `com.apkforge.demo.DemoDialog`）
 * - 输出 `~/fixture-out.apk`
 */
class InjectArtifactFixture {

    @Test
    fun emit() {
        val home = System.getProperty("user.home") ?: "/data/data/com.termux/files/home"
        val inPath = "$home/fixture-in.apk"
        val outPath = "$home/fixture-out.apk"
        val payloadPath = "$home/fixture-payload.dex"

        val apk = File(inPath)
        if (!apk.isFile) return
        val payload = File(payloadPath)
        if (!payload.isFile) {
            println("FIXTURE 跳过：缺少 payload dex（$payloadPath）")
            return
        }

        val manifest = ApkRepackager.readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) }
        val launcher = manifest?.primaryLauncher ?: error("没有 LAUNCHER")
        val launcherType = "L${launcher.replace('.', '/')};"

        var dexName: String? = null
        var dexBytes: ByteArray? = null
        for (n in ApkRepackager.listDexNames(apk)) {
            val b = TestIo.readEntry(apk, n) ?: continue
            if (DexCallInjector.containsClass(b, launcherType)) {
                dexName = n; dexBytes = b; break
            }
        }
        assertNotNull("找不到含 LAUNCHER 的 dex", dexBytes)

        val spec = DexCallInjector.Spec(
            launcherType, "onCreate", "(Landroid/os/Bundle;)V",
            SmaliCallParser.parseAll(
                "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
            ).first,
        )
        val r = FastInjector.tryInject(dexBytes!!, spec)
        check(r is FastInjector.Result.Injected) { "注入失败：$r" }

        // 走真实重打包：替换目标 dex + 追加 payload
        val out = File(outPath).apply { parentFile?.mkdirs(); delete() }
        ApkRepackager.repack(
            sourceApk = apk,
            outputApk = out,
            targetDexName = dexName!!,
            newDexBytes = r.dex,
            payloadDexBytes = payload.readBytes(),
        )

        ZipFile(out).use { z ->
            val dexes = z.entries().toList().count {
                it.name.startsWith("classes") && it.name.endsWith(".dex")
            }
            println("FIXTURE 写入 $outPath，替换 $dexName，dex 总数 $dexes")
        }
    }
}

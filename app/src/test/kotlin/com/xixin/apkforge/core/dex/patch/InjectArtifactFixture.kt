package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 夹具：对 [user.dir] 下的指定 APK 做一次注入，产出到临时文件，
 * 供 jadx / dexdump 这类外部严格解析器交叉验证。
 *
 * 只在显式指定 `-Dapkforge.fixtureApk=/path/in.apk -Dapkforge.fixtureOut=/path/out.apk`
 * 时工作，平时空跑。
 */
class InjectArtifactFixture {

    @Test
    fun emit() {
        // 【为什么读固定路径】Gradle 的 test 任务既不继承环境变量、也不转发 -D，
        // 而配置缓存会把任务环境固化下来（改了也不生效）。这里改为读固定的
        // 临时路径，配合仓库根目录的 symlink / 拷贝使用。
        val home = System.getProperty("user.home") ?: "/data/data/com.termux/files/home"
        val inPath = System.getProperty("apkforge.fixtureApk")
            ?: System.getenv("APKFORGE_FIXTURE_APK")
            ?: "$home/fixture-in.apk"
        val outPath = System.getProperty("apkforge.fixtureOut")
            ?: System.getenv("APKFORGE_FIXTURE_OUT")
            ?: "$home/fixture-out.apk"
        if (!File(inPath).isFile) return
        val apk = File(inPath)
        if (!apk.isFile) return

        val manifest = ApkRepackager.readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) }
        val launcher = manifest?.primaryLauncher ?: error("没有 LAUNCHER")
        val launcherType = "L${launcher.replace('.', '/')};"

        var dexName: String? = null
        var dexBytes: ByteArray? = null
        for (n in ApkRepackager.listDexNames(apk)) {
            val b = TestIo.readEntry(apk, n) ?: continue
            if (DexCallInjector.containsClass(b, launcherType)) { dexName = n; dexBytes = b; break }
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

        // 重打包：只替换被改的那个 dex
        val out = File(outPath).apply { parentFile?.mkdirs(); delete() }
        ZipFile(apk).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zip.entries().toList().forEach { e ->
                    if (e.isDirectory) return@forEach
                    val data = if (e.name == dexName) r.dex
                    else zip.getInputStream(e).use { it.readBytes() }
                    val ne = ZipEntry(e.name).apply {
                        time = e.time
                        method = if (e.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
                        if (method == ZipEntry.STORED) {
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            crc = java.util.zip.CRC32().apply { update(data) }.value
                        }
                    }
                    zos.putNextEntry(ne); zos.write(data); zos.closeEntry()
                }
            }
        }
        println("FIXTURE 写入 $outPath，替换 $dexName")
    }
}

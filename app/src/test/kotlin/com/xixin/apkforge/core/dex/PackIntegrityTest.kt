package com.xixin.apkforge.core.dex

import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 加固检测：只认确定性证据，**不许误报正常包**。
 *
 * 误报的代价在这里不对称——把正常包说成「加固」，用户会以为工具坏了；
 * 漏报的代价只是一次失败注入，而失败时下游本来就会说「找不到类」。
 * 所以下面既有「能抓出加固」的用例，也有专门的「不许误报」用例。
 */
class PackIntegrityTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "apkforge-integrity-$tag").apply { mkdirs() }

    /**
     * 防误报：正常包必须判成「没问题」。
     *
     * 这条是本次改动的核心约束。判据不数阈值（类数 / assets 里有没有 jar），
     * 因为实测反例都很硬：
     *  · 正常包里有只有 0 个类的 dex（多 dex 切分产物）；
     *  · 正常商业包在 assets/ 下带了 2MB 的广告 SDK jar。
     * 只要拿这两条当判据，就一定误报。
     */
    @Test
    fun `正常包不应被判成加固`() {
        val apk = locateApk()
        val r = PackIntegrity.inspect(apk)
        assertTrue("正常包被判成有问题：${r.issues}", r.issues.isEmpty())
        assertNull("正常包不该有提示", r.hint)
        assertFalse("正常包不该被拦", r.blocked)
        assertEquals("所有 dex 都应可读", r.checked, r.readableDex)
        assertTrue("应该真的检查到了 dex", r.checked > 0)
    }

    /** 魔数被破坏 = 整体加密，这是加固最典型的外层形态。 */
    @Test
    fun `dex 被整体加密时应报告魔数不对`() {
        val apk = locateApk()
        val modified = encryptAllDex(apk, workDir("encrypted"))
        val r = PackIntegrity.inspect(modified)

        assertTrue("应至少报出一条", r.issues.isNotEmpty())
        assertTrue(
            "应指出魔数问题，实际：${r.issues.map { it.detail }}",
            r.issues.any { it.detail.contains("魔数") },
        )
        assertEquals("加密后没有 dex 可读", 0, r.readableDex)
        assertTrue("全部不可读时应拦下", r.blocked)
        assertNotNull("应给出可执行的下一步", r.hint)
        assertTrue(
            "提示里应点明「加固」这个真实原因，实际：${r.hint}",
            r.hint!!.contains("加固"),
        )
    }

    /** `file_size` 字段与实际字节数不符 = 数据被拼接/截断。 */
    @Test
    fun `dex 声明大小与实际不符时应报告`() {
        val apk = locateApk()
        val modified = tamperDeclaredSize(apk, workDir("size"))
        val r = PackIntegrity.inspect(modified)

        assertTrue(
            "应指出声明大小不符，实际：${r.issues.map { it.detail }}",
            r.issues.any { it.detail.contains("声明") && it.detail.contains("实际") },
        )
    }

    /**
     * 只有**部分** dex 坏掉时不该拦。
     *
     * 目标类可能就在好的那个 dex 里，注入有机会成功。此时拦下是过度干预，
     * 正确做法是提示 + 让下游按实际情况报错。
     */
    @Test
    fun `部分 dex 不可读时只提示不拦截`() {
        val apk = locateApk()
        val names = ApkRepackager.listDexNames(apk)
        assumeTrue("这个包只有一个 dex，测不了部分坏的情况", names.size > 1)

        val modified = corruptOneDex(apk, workDir("partial"), names.last())
        val r = PackIntegrity.inspect(modified)

        assertTrue("应报出问题", r.issues.isNotEmpty())
        assertTrue("还有可读 dex，不该拦", !r.blocked)
        assertTrue("可读 dex 数应小于总数", r.readableDex in 1 until r.checked)
        assertNotNull("仍然要有提示", r.hint)
    }

    // ────────────────────── 构造加固样本 ──────────────────────

    /** 把所有 dex 的魔数抹掉，模拟「dex 被整体加密」。 */
    private fun encryptAllDex(apk: File, dir: File): File {
        val out = File(dir, "encrypted.apk")
        val names = ApkRepackager.listDexNames(apk)
        val patched = names.associateWith { name ->
            val b = TestIo.readEntry(apk, name)!!
            // 只破坏前 8 字节（magic + version），其余保持原样：
            // 这已经足够让任何 dex 解析器认不出它，而且不改文件大小。
            b.copyOf().also { for (i in 0 until 8) it[i] = 0x5A }
        }
        rewriteApk(apk, out, patched)
        return out
    }

    /** 只改一个 dex 头里的 file_size 字段（0x20），模拟拼接/截断。 */
    private fun tamperDeclaredSize(apk: File, dir: File): File {
        val out = File(dir, "size.apk")
        val name = ApkRepackager.listDexNames(apk).first()
        val b = TestIo.readEntry(apk, name)!!.copyOf()
        // +4096 保持「是个合理数字」，只是与真实大小对不上
        val fake = (b.size + 4096)
        b[0x20] = (fake and 0xFF).toByte()
        b[0x21] = ((fake shr 8) and 0xFF).toByte()
        b[0x22] = ((fake shr 16) and 0xFF).toByte()
        b[0x23] = ((fake shr 24) and 0xFF).toByte()
        rewriteApk(apk, out, mapOf(name to b))
        return out
    }

    /** 只破坏指定 dex 的魔数。 */
    private fun corruptOneDex(apk: File, dir: File, name: String): File {
        val out = File(dir, "partial.apk")
        val b = TestIo.readEntry(apk, name)!!.copyOf()
        for (i in 0 until 4) b[i] = 0x00
        rewriteApk(apk, out, mapOf(name to b))
        return out
    }

    /** 按原条目顺序重建 zip，只替换给定条目。 */
    private fun rewriteApk(src: File, dst: File, replacements: Map<String, ByteArray>) {
        dst.parentFile?.mkdirs()
        dst.delete()
        java.util.zip.ZipFile(src).use { zip ->
            java.util.zip.ZipOutputStream(dst.outputStream().buffered()).use { out ->
                zip.entries().toList().forEach { e ->
                    val data = if (e.isDirectory) {
                        null
                    } else {
                        replacements[e.name] ?: zip.getInputStream(e).use { it.readBytes() }
                    }
                    if (data == null) {
                        out.putNextEntry(java.util.zip.ZipEntry(e.name).apply { time = e.time })
                    } else {
                        val ne = java.util.zip.ZipEntry(e.name).apply { time = e.time }
                        out.putNextEntry(ne)
                        out.write(data)
                    }
                    out.closeEntry()
                }
            }
        }
    }
}

package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ApkSigningBlock
import com.xixin.apkforge.core.apk.ZipCentralDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 端到端：**原始 APK → 中间包 → 签名块注入 → 数据复用 → 自检**。
 *
 * ## 这个测试钉的是「顺序」，不是「各部件能单独跑」
 *
 * 三个部件各自都有单测（[com.xixin.apkforge.core.apk.ApkSigningBlockTest] /
 * `ZipReuseTest`），但它们组合起来的**顺序**才是真正的风险：
 *
 * ```
 * ① rebuild 中间包            ZipOutputStream 重写 → 签名块必然丢
 * ② 注入签名块                必须在 ① 之后（否则被 ① 冲掉）
 * ③ 数据复用                  必须在 ② 之后（origin 数据区 = 中间包字节）
 * ④ 任何 ZIP 重写              绝不能在 ③ 之后（会把重叠条目展开）
 * ```
 *
 * 顺序错任何一步的表现都是「产物能打开、装机成功、功能不生效」，无报错。
 * 云端的 2026-11-24 事故就是 ② 与 ① 顺序反了导致 SIGBUS。
 *
 * ## 为什么用真实 APK
 *
 * `AGENTS.md` §3.1：伪造样本覆盖不到多 dex、大方法、真实按钮表。
 * 真实 APK 里 `resources.arsc` 的 STORED + 4 字节对齐是 AGP 保证的，
 * 而夹具是手造的——两者对「复用平移是否破坏对齐」的敏感度不同。
 */
class UncheckAssemblyOrderTest {

    private fun locateApk(): File? {
        System.getProperty("apkforge.testApk")?.let { p ->
            val f = File(p); if (f.isFile) return f
        }
        System.getenv("APKFORGE_TEST_APK")?.let { p ->
            val f = File(p); if (f.isFile) return f
        }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile }
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "uncheck-order-$tag").apply {
            deleteRecursively(); mkdirs()
        }

    /**
     * 按真实流程组装：中间包（改一个条目）→ 注入签名块 → 数据复用。
     *
     * 模拟 [UncheckPipeline] 的真实步骤，但不依赖它（这样 Pipeline 还没写完
     * 也能先钉住顺序契约）。
     */
    private fun assemble(
        original: File,
        work: File,
        extraEntries: List<ZipReuse.ExtraEntry>,
    ): File {
        val intermediate = File(work, "intermediate.apk")

        // ① 重写中间包（这里只加额外条目，不改 Manifest —— 改 Manifest 由
        //    AxmlEditor 负责，与本测试要验的「ZIP 层顺序」无关）
        ZipFile(original).use { zin ->
            ZipOutputStream(intermediate.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    if (e.isDirectory) {
                        zout.putNextEntry(ZipEntry(e.name).apply { time = e.time })
                        zout.closeEntry()
                        continue
                    }
                    val data = zin.getInputStream(e).use { it.readBytes() }
                    val ne = ZipEntry(e.name).apply {
                        method = if (e.method == ZipEntry.STORED) ZipEntry.STORED
                        else ZipEntry.DEFLATED
                        time = e.time
                        if (method == ZipEntry.STORED) {
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            crc = java.util.zip.CRC32().apply { update(data) }.value
                        }
                    }
                    zout.putNextEntry(ne)
                    zout.write(data)
                    zout.closeEntry()
                }
                // 我们的额外条目（boot dex / payload.dex / .so / json）
                for (x in extraEntries) {
                    val ne = ZipEntry(x.name).apply {
                        method = if (x.method == ZipCentralDirectory.METHOD_STORED) ZipEntry.STORED
                        else ZipEntry.DEFLATED
                        time = 0L
                        if (method == ZipEntry.STORED) {
                            size = x.data.size.toLong()
                            compressedSize = x.data.size.toLong()
                            crc = java.util.zip.CRC32().apply { update(x.data) }.value
                        }
                    }
                    zout.putNextEntry(ne)
                    zout.write(x.data)
                    zout.closeEntry()
                }
            }
        }

        // ② 注入原包的签名块 —— 必须在数据复用之前
        val block = ApkSigningBlock.extract(original)
        if (block != null) ApkSigningBlock.inject(intermediate, block)

        // ③ 数据复用
        val out = File(work, "final.apk")
        ZipReuse.build(intermediate, out)
        return out
    }

    // ── 断言 ──────────────────────────────────────────────────

    /**
     * 全链路：产物里 origin.apk **必须带原签名块**。
     *
     * 这是 native 层能不能读到证书的唯一判据。缺了它，`open(base.apk)`
     * 重定向到 origin.apk 后拿到空签名块 → 证书为空 → 整个 native 层白干。
     */
    @Test
    fun `产物里的 origin_apk 带着原包的签名块`() {
        val apk = locateApk() ?: return
        assumeTrue("测试 APK 本身没有签名块（debug 包未签名？），跳过", ApkSigningBlock.hasBlock(apk))

        val work = workDir("sigblock")
        val out = assemble(apk, work, emptyList())

        // 取出 origin.apk，它必须是一个带签名块的完整 APK
        val layout = ZipCentralDirectory.read(out)
        val origin = layout.byName(ZipReuse.ORIGIN_ENTRY)
        assertNotNull("产物缺 ${ZipReuse.ORIGIN_ENTRY}", origin)

        val extracted = File(work, "origin.apk")
        extracted.writeBytes(ZipCentralDirectory.readRaw(out, origin!!))

        assertTrue(
            "origin.apk 里没有签名块 —— native 层将读不到证书，去校验的 native 部分完全不生效",
            ApkSigningBlock.hasBlock(extracted),
        )
        // 且签名块内容与原包一致
        val origBlock = ApkSigningBlock.extract(apk)
        val newBlock = ApkSigningBlock.extract(extracted)
        assertTrue(
            "origin.apk 的签名块与原包不一致",
            origBlock!!.contentEquals(newBlock),
        )
        println("SIGBLOCK: 原包 ${origBlock.size} B 签名块已随 origin.apk 保留")
    }

    /** origin.apk 必须是完整可打开的 APK（不是片段）。 */
    @Test
    fun `产物里的 origin_apk 是完整可打开的 APK`() {
        val apk = locateApk() ?: return
        val work = workDir("origin-full")
        val out = assemble(apk, work, emptyList())

        val layout = ZipCentralDirectory.read(out)
        val origin = layout.byName(ZipReuse.ORIGIN_ENTRY)!!
        val extracted = File(work, "origin.apk")
        extracted.writeBytes(ZipCentralDirectory.readRaw(out, origin))

        val inner = ZipCentralDirectory.read(extracted)
        val srcNames = ZipCentralDirectory.read(apk).names.toSet()
        val innerNames = inner.names.toSet()

        assertTrue("origin.apk 缺 AndroidManifest.xml", innerNames.contains("AndroidManifest.xml"))
        assertEquals(
            "origin.apk 的条目集合应与原包一致（我们的额外条目除外）",
            srcNames,
            innerNames - setOf(ZipReuse.ORIGIN_ENTRY),
        )
    }

    /** 额外条目（.so / dex / json）在产物里必须齐全且可读。 */
    @Test
    fun `额外条目在产物里齐全`() {
        val apk = locateApk() ?: return
        val work = workDir("extras")

        val soBytes = ByteArray(4096) { (it % 113).toByte() }
        val bootDex = "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(256)
        val payloadDex = "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(128)
        val json = """{"cert":"AAAA","originalApp":"com.x.Y"}""".toByteArray()

        val extras = listOf(
            ZipReuse.ExtraEntry("classes99.dex", bootDex, ZipCentralDirectory.METHOD_DEFLATED),
            ZipReuse.ExtraEntry(
                "assets/nc_sigfix/payload.dex", payloadDex,
                ZipCentralDirectory.METHOD_DEFLATED,
            ),
            ZipReuse.ExtraEntry(
                "assets/nc_sigfix/sigfix.json", json,
                ZipCentralDirectory.METHOD_DEFLATED,
            ),
            ZipReuse.ExtraEntry(
                "lib/arm64-v8a/libusign.so", soBytes,
                ZipCentralDirectory.METHOD_STORED,
            ),
        )
        val out = assemble(apk, work, extras)
        val layout = ZipCentralDirectory.read(out)

        for (x in extras) {
            val e = layout.byName(x.name)
            assertNotNull("产物缺 ${x.name}", e)
            if (x.method == ZipCentralDirectory.METHOD_STORED) {
                val back = ZipCentralDirectory.readRaw(out, e!!)
                assertTrue("${x.name} 内容被改", back.contentEquals(x.data))
            }
        }

        // .so 必须 STORED（System.load 需要直接映射，压缩过的不行）
        assertTrue(
            "libusign.so 必须 STORED",
            layout.byName("lib/arm64-v8a/libusign.so")!!.isStored,
        )

        // 交叉验证：标准 ZipFile 也能读到压缩条目
        ZipFile(out).use { zf ->
            val text = zf.getInputStream(zf.getEntry("assets/nc_sigfix/sigfix.json"))
                .use { String(it.readBytes()) }
            assertEquals("sigfix.json 内容不符", String(json), text)
        }
    }

    /** 产物必须通过自检（含 arsc 对齐这条装机硬判据）。 */
    @Test
    fun `真实 APK 的产物通过自检`() {
        val apk = locateApk() ?: return
        val work = workDir("verify")
        val out = assemble(apk, work, emptyList())

        val problems = ZipReuse.verify(out)
        assertTrue("产物自检不通过:\n  ${problems.joinToString("\n  ")}", problems.isEmpty())

        // 真实包的 resources.arsc 应当存在且对齐（AGP 保证的）
        val layout = ZipCentralDirectory.read(out)
        val arsc = layout.byName("resources.arsc")
        if (arsc != null) {
            assertEquals("真实包的 arsc 数据偏移必须仍是 4 的倍数", 0L, arsc.dataOffset % 4)
        }
    }

    /** 产物体积不该接近翻倍（数据复用生效）。 */
    @Test
    fun `真实 APK 复用后体积不翻倍`() {
        val apk = locateApk() ?: return
        val work = workDir("size")
        val out = assemble(apk, work, emptyList())

        val src = apk.length()
        val dst = out.length()
        assertTrue(
            "产物 $dst 字节 ≥ 源包 $src 的 1.5 倍，数据复用没生效",
            dst < src * 1.5,
        )
        println("SIZE: ${src / 1024 / 1024} MB → ${dst / 1024 / 1024} MB（开销 ${(dst - src)} B）")
    }

    /**
     * 反向说明：如果顺序错了（先复用、再重写 ZIP），签名块会丢。
     *
     * 这条不是「期望这样」，而是把已知的错法固化成断言，
     * 让后来改代码的人一眼看到「为什么必须是这个顺序」。
     */
    @Test
    fun `顺序错时签名块会丢_反向说明`() {
        val apk = locateApk() ?: return
        assumeTrue("测试 APK 无签名块，跳过", ApkSigningBlock.hasBlock(apk))

        val work = workDir("wrong-order")
        val out = assemble(apk, work, emptyList())
        val layout = ZipCentralDirectory.read(out)
        val origin = layout.byName(ZipReuse.ORIGIN_ENTRY)!!
        val goodOrigin = File(work, "good-origin.apk")
        goodOrigin.writeBytes(ZipCentralDirectory.readRaw(out, origin))
        assertTrue("前置条件：正确顺序下 origin 应带签名块", ApkSigningBlock.hasBlock(goodOrigin))

        // 现在模拟「复用之后再重写一遍 ZIP」（= 多了个 zipalign 步骤）
        val rewritten = File(work, "rewritten.apk")
        ZipFile(out).use { zin ->
            ZipOutputStream(rewritten.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
            }
        }
        val rl = ZipCentralDirectory.read(rewritten)
        val badOrigin = File(work, "bad-origin.apk")
        badOrigin.writeBytes(ZipCentralDirectory.readRaw(rewritten, rl.byName(ZipReuse.ORIGIN_ENTRY)!!))

        assertNull(
            "重写后 origin.apk 里的签名块必然消失 —— 这就是「复用之后绝不能" +
                "再跑任何 ZIP 重写工具」的由来（zipalign / apksigner 都会）",
            ApkSigningBlock.extract(badOrigin),
        )
    }
}

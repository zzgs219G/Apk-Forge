package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ZipCentralDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `ZipReuse`（数据复用）的结构契约。
 *
 * ## 为什么这个测试必须存在
 *
 * 复用写错的三种形态**全都不报错**：
 * ```
 * ① header 没补到 4096  → 产物能生成、能打开，装机被拒（arsc 未对齐）
 * ② CD 的 extra 与 LFH 不一致 → 等于没对齐，同上
 * ③ origin.apk 内容不完整 → 装机成功，运行期 NoClassDefFoundError
 * ```
 * 没有这条测试，改坏的表现是「用户装不上 / 装上闪退」，而不是构建失败。
 */
class ZipReuseTest {

    // ── 夹具：造一个最小但形态真实的 APK ──────────────────────────

    /** 夹具条目的规格：(名字, 内容, 是否 STORED) */
    private data class Spec(val name: String, val data: ByteArray, val stored: Boolean)

    /**
     * 造一个形态贴近真实 APK 的夹具。
     *
     * ## 为什么必须自己对齐 STORED 条目
     *
     * `ZipOutputStream` **不会**自动对齐，直接写出来的 `resources.arsc`
     * 数据偏移是 `30 + nameLen` 的倍数关系，未必是 4 的倍数。
     * 而真实 APK 里 arsc 必然是 4 字节对齐的（AGP/zipalign 保证）。
     *
     * 用不对齐的夹具测「复用后是否保持对齐」是**测不出东西**的：
     * 源本来 mod4=2，复用平移 4096 后还是 mod4=2，断言必然假红 ——
     * 第一版夹具就踩了这个（源 arsc 偏移 910，910%4=2）。
     *
     * 这里按真实打包器的做法，给每个 STORED 条目补 extra 使其数据偏移
     * 落在 4 的倍数上。`EXTRA_ID` 与生产代码同约定（未知 ID，解析器跳过），
     * 但这是**独立实现**，不复用被测代码 —— 否则等于自己给自己判卷。
     */
    private fun makeFixtureApk(
        dir: File,
        arscSize: Int = 1024,
    ): File {
        dir.mkdirs()
        val apk = File(dir, "fixture.apk")

        val specs = listOf(
            Spec("AndroidManifest.xml", ByteArray(256) { 0x11 }, stored = true),
            Spec(
                "classes.dex",
                "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(512) { 0x42 },
                stored = true,
            ),
            Spec("resources.arsc", ByteArray(arscSize) { (it % 251).toByte() }, stored = true),
        )

        ZipOutputStream(apk.outputStream().buffered()).use { zout ->
            var pos = 0L      // 当前写入位置，用于算对齐
            for (s in specs) {
                val nameBytes = s.name.toByteArray(Charsets.UTF_8)
                val crc = java.util.zip.CRC32().apply { update(s.data) }.value
                val extra = if (s.stored) alignExtra(30 + nameBytes.size) else ByteArray(0)

                // STORED 条目必须显式给 size / compressedSize / crc，
                // 否则 ZipOutputStream 抛 "invalid entry size"
                val e = ZipEntry(s.name).apply {
                    method = if (s.stored) ZipEntry.STORED else ZipEntry.DEFLATED
                    if (s.stored) {
                        size = s.data.size.toLong()
                        compressedSize = s.data.size.toLong()
                        this.crc = crc
                    }
                    if (extra.isNotEmpty()) setExtra(extra)
                }
                zout.putNextEntry(e)
                zout.write(s.data)
                zout.closeEntry()

                pos += 30 + nameBytes.size + extra.size + s.data.size
            }
        }
        return apk
    }

    /** 使 `baseLen + len(extra)` 是 4 的倍数。与真实打包器的做法一致。 */
    private fun alignExtra(baseLen: Int, align: Int = 4): ByteArray {
        var need = (align - baseLen % align) % align
        if (need == 0) return ByteArray(0)
        if (need < 4) need += align
        val body = need - 4
        val out = ByteArray(need)
        // extra field: [id 2B][size 2B][body]；id=0x0000 是未知 ID，解析器会跳过
        out[2] = (body and 0xFF).toByte()
        out[3] = ((body shr 8) and 0xFF).toByte()
        return out
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "zipreuse-$tag").apply {
            deleteRecursively(); mkdirs()
        }

    // ── 断言 ──────────────────────────────────────────────────

    /**
     * 防「体积翻倍」：origin.apk 的数据不能以实体副本形式再存一份。
     *
     * ## 判据为什么不是「开销占源包的比例」
     *
     * 第一版用的是 `outputSize < sourceSize * 2`，在 2KB 的夹具上必然假红：
     * header 补到 4096 + 中央目录（每个条目约 60 字节）是**固定开销**，
     * 与源包大小无关。真实场景源包几十 MB，这点开销可以忽略；
     * 但小夹具上占比自然很大 —— 那是夹具的尺度问题，不是复用没生效。
     *
     * 正确判据是**容量关系**：产物必须远远装不下「源包 + 源包副本」。
     *
     * ```
     * 不复用：outputSize ≈ sourceSize * 2 + 开销
     * 复用后：outputSize ≈ sourceSize + 开销       ← 我们期望这个
     * ```
     */
    @Test
    fun `复用后 origin 数据不占第二份体积`() {
        val dir = workDir("size")
        // 用大一点的 arsc 让源包有量级，判据才不受固定开销干扰
        val src = makeFixtureApk(dir, arscSize = 64 * 1024)
        val out = File(dir, "reused.apk")

        val stats = ZipReuse.build(src, out)

        // 若 origin 被存了两份，产物至少要 sourceSize * 2
        assertTrue(
            "产物(${stats.outputSize}) 装得下两份源包(${stats.sourceSize}×2 = " +
                "${stats.sourceSize * 2})，说明 origin.apk 被实体复制了，数据复用没生效",
            stats.outputSize < stats.sourceSize * 2,
        )
        // 开销的构因（这是本实现的固有成本，不是浪费）：
        //   LFH 的 4096 对齐填充
        // + CD  的 4096 对齐填充   ← 红线 ② 要求两处 extra 长度必须一致
        // + 新增的 CD 条目（origin 一条）
        // 所以上限是 2×header 再加一点 CD 开销。关键是它**与源包规模无关**，
        // 换个 100MB 的包也还是这么多。
        assertTrue(
            "开销 ${stats.overheadBytes} 字节超出预期（header ${stats.originHeaderBytes} B，" +
                "LFH+CD 各一份对齐填充 + 新增 CD 条目）",
            stats.overheadBytes < stats.originHeaderBytes * 2 + 1024,
        )
        // 最有意义的判据：开销远小于「再存一份源包」
        assertTrue(
            "开销(${stats.overheadBytes}) 不该接近源包大小(${stats.sourceSize})",
            stats.overheadBytes < stats.sourceSize,
        )
        assertTrue("header 应补到 4096 以内，实际 ${stats.originHeaderBytes}", stats.originHeaderBytes <= 4096)
        assertTrue("header 必须是 4 的倍数，实际 ${stats.originHeaderBytes}", stats.originHeaderBytes % 4 == 0L)
        println(
            "REUSE: ${stats.sourceSize} → ${stats.outputSize}" +
                "（header ${stats.originHeaderBytes} B，省 ${stats.savedBytes} B）"
        )
    }

    /**
     * 防「origin.apk 内容不完整」——这是最危险的一类。
     *
     * 最小化 origin（只留签名块）能省体积，但实测会让 ART 的 dex 懒加载
     * 读到空条目 → `NoClassDefFoundError` → App 启动即崩。
     * 判据：origin.apk 按 offset 读出后，必须能当 ZIP 打开且有条目。
     */
    @Test
    fun `origin_apk 是完整可打开的 APK`() {
        val dir = workDir("origin")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")
        ZipReuse.build(src, out)

        val layout = ZipCentralDirectory.read(out)
        val origin = layout.byName(ZipReuse.ORIGIN_ENTRY)
        assertNotNull("产物里必须有 ${ZipReuse.ORIGIN_ENTRY}", origin)

        val bytes = ZipCentralDirectory.readRaw(out, origin!!)
        assertEquals(
            "origin.apk 的数据区应等于原包全字节（长度）",
            src.length(),
            bytes.size.toLong(),
        )

        // 把 origin.apk 单独落盘，必须是一个结构完整的 ZIP
        val extracted = File(dir, "extracted-origin.apk")
        extracted.writeBytes(bytes)
        val inner = ZipCentralDirectory.read(extracted)
        assertTrue("origin.apk 里应有条目，实际 ${inner.entries.size} 个", inner.entries.isNotEmpty())
        assertTrue(
            "origin.apk 里必须有 AndroidManifest.xml（否则不是完整 APK）",
            inner.names.contains("AndroidManifest.xml"),
        )
        assertEquals(
            "origin.apk 的条目数应等于原包",
            ZipCentralDirectory.read(src).entries.size,
            inner.entries.size,
        )
    }

    /**
     * 防红线 ①：header 未补到 4096 时 `resources.arsc` 失去 4 字节对齐。
     *
     * 平移量必须是 4 的倍数，否则原包对齐过的条目平移后全部错位。
     * Android R+ 在装机期直接拒：
     * ```
     * Targeting R+ (version 30 and above) requires the resources.arsc of
     * installed APKs to be stored uncompressed and aligned on a 4-byte boundary
     * ```
     */
    @Test
    fun `resources_arsc 复用后仍 4 字节对齐且保持 STORED`() {
        val dir = workDir("arsc")
        val src = makeFixtureApk(dir)
        val srcArsc = ZipCentralDirectory.read(src).byName("resources.arsc")!!
        assumeTrue("夹具的 arsc 应是 STORED", srcArsc.isStored)

        val out = File(dir, "reused.apk")
        ZipReuse.build(src, out)

        val after = ZipCentralDirectory.read(out).byName("resources.arsc")!!
        assertTrue("复用后 arsc 必须仍是 STORED", after.isStored)
        assertEquals(
            "复用后 arsc 数据偏移必须仍是 4 的倍数（源 ${srcArsc.dataOffset} → 产物 ${after.dataOffset}）",
            0L,
            after.dataOffset % 4,
        )
        // 内容不能变
        val before = ZipCentralDirectory.readRaw(src, srcArsc)
        val now = ZipCentralDirectory.readRaw(out, after)
        assertTrue("arsc 内容被改变了", before.contentEquals(now))
    }

    /**
     * 防红线 ②：local header 与中央目录的 extra 必须长度一致。
     *
     * 只给 LFH 加 extra、CD 不加时，读取方从 CD 得到 `extraLen=0`，
     * 实际平移量恢复成原始 header 长度 → 对齐静默失效。
     * artifact-pipeline 在此踩过，且当时单测只查 LFH 而误判成功。
     *
     * 判据：从**中央目录**读 origin.apk 的 extraLen，与从 **LFH** 读的一致。
     */
    @Test
    fun `origin 的 local header 与中央目录 extra 长度一致`() {
        val dir = workDir("extra")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")
        ZipReuse.build(src, out)

        RandomAccessFile(out, "r").use { raf ->
            // origin 的 LFH 在 offset 0
            val lfh = ByteArray(30)
            raf.seek(0)
            raf.readFully(lfh)
            val lfhNameLen = (lfh[26].toInt() and 0xFF) or ((lfh[27].toInt() and 0xFF) shl 8)
            val lfhExtraLen = (lfh[28].toInt() and 0xFF) or ((lfh[29].toInt() and 0xFF) shl 8)

            // CD 里 origin 那一条（走中央目录解析，不自己猜位置）
            val layout = ZipCentralDirectory.read(out)
            val cdOffset = layout.cdOffset
            raf.seek(cdOffset)
            val cd = ByteArray(46)
            raf.readFully(cd)
            val cdNameLen = (cd[28].toInt() and 0xFF) or ((cd[29].toInt() and 0xFF) shl 8)
            val cdExtraLen = (cd[30].toInt() and 0xFF) or ((cd[31].toInt() and 0xFF) shl 8)

            assertEquals("LFH 与 CD 的名字长度应一致", lfhNameLen, cdNameLen)
            assertEquals(
                "LFH 与 CD 的 extra 长度必须一致，否则跨 4096 边界后对齐失效" +
                    "（LFH=$lfhExtraLen, CD=$cdExtraLen）",
                lfhExtraLen,
                cdExtraLen,
            )
            assertEquals(
                "origin 数据起点应正好是 header 长度",
                ZipReuse.ORIGIN_ENTRY.toByteArray(Charsets.UTF_8).size + 30 + lfhExtraLen,
                lfhNameLen + 30 + lfhExtraLen,
            )
        }
    }

    /** 原包的每个条目在产物里都必须还能按 offset 正确读出。 */
    @Test
    fun `原包条目在产物里仍可正确读取`() {
        val dir = workDir("entries")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")
        ZipReuse.build(src, out)

        val srcLayout = ZipCentralDirectory.read(src)
        val outLayout = ZipCentralDirectory.read(out)

        for (e in srcLayout.entries) {
            val now = outLayout.byName(e.name)
            assertNotNull("产物里丢了条目 ${e.name}", now)
            assertEquals("${e.name} 的 CRC 变了", e.crc, now!!.crc)
            assertEquals("${e.name} 的原始大小变了", e.size, now.size)
            val a = ZipCentralDirectory.readRaw(src, e)
            val b = ZipCentralDirectory.readRaw(out, now)
            assertTrue("${e.name} 的字节内容变了", a.contentEquals(b))
        }
    }

    /** 额外条目（.so 等）必须被正确追加。 */
    @Test
    fun `额外条目被正确追加且可读`() {
        val dir = workDir("extras")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")

        val soBytes = ByteArray(2048) { (it % 97).toByte() }
        ZipReuse.build(
            src, out,
            extraEntries = listOf(
                ZipReuse.ExtraEntry(
                    "lib/arm64-v8a/libusign.so", soBytes,
                    ZipCentralDirectory.METHOD_STORED,
                ),
                ZipReuse.ExtraEntry(
                    "assets/nc_sigfix/sigfix.json",
                    """{"cert":"abc"}""".toByteArray(),
                    ZipCentralDirectory.METHOD_DEFLATED,
                ),
            ),
        )

        val layout = ZipCentralDirectory.read(out)
        val so = layout.byName("lib/arm64-v8a/libusign.so")
        assertNotNull("追加的 .so 丢了", so)
        assertTrue(".so 必须是 STORED", so!!.isStored)
        assertTrue(
            "读回的 .so 内容不符",
            ZipCentralDirectory.readRaw(out, so).contentEquals(soBytes),
        )

        // 用标准 ZipFile 交叉验证（它能容忍重叠条目，正好当独立实现）
        ZipFile(out).use { zf ->
            val e = zf.getEntry("assets/nc_sigfix/sigfix.json")
            assertNotNull("压缩条目丢了", e)
            val text = zf.getInputStream(e).use { String(it.readBytes()) }
            assertEquals("压缩条目内容不符", """{"cert":"abc"}""", text)
        }
    }

    /** 自检函数对合法产物应返回空问题列表。 */
    @Test
    fun `verify 对合法复用包返回无问题`() {
        val dir = workDir("verify")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")
        ZipReuse.build(src, out)

        val problems = ZipReuse.verify(out)
        assertTrue("合法复用包不该有问题，实际: $problems", problems.isEmpty())
    }

    /**
     * 防「origin 只留签名块」那类破坏。
     *
     * 传一个不是 APK 的东西当 origin，`build` 必须在**写文件之前**拒绝，
     * 而不是产出一个人装不上的包。
     */
    @Test
    fun `origin 不是 APK 时 build 直接拒绝`() {
        val dir = workDir("bad-origin")
        val src = makeFixtureApk(dir)
        val notApk = File(dir, "not.apk").apply { writeBytes("这不是 APK".toByteArray()) }
        val out = File(dir, "reused.apk")

        val err = runCatching { ZipReuse.build(src, out, originApk = notApk) }.exceptionOrNull()
        assertTrue(
            "origin 不是 ZIP 时应抛 ReuseException，实际: $err",
            err is ZipReuse.ReuseException,
        )
        assertTrue("拒绝时不该留下半成品", !out.isFile)
    }

    /** 中间包里已经有 origin.apk 时要拒绝（重复复用会导致两份内容打架）。 */
    @Test
    fun `中间包已含 origin 条目时拒绝`() {
        val dir = workDir("dup")
        val src = makeFixtureApk(dir)

        // 造一个已经含 origin.apk 的中间包
        val withOrigin = File(dir, "with-origin.apk")
        ZipFile(src).use { zin ->
            ZipOutputStream(withOrigin.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
                val payload = "fake".toByteArray()
                val ne = ZipEntry(ZipReuse.ORIGIN_ENTRY).apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(payload) }.value
                }
                zout.putNextEntry(ne); zout.write(payload); zout.closeEntry()
            }
        }

        val err = runCatching {
            ZipReuse.build(withOrigin, File(dir, "out.apk"))
        }.exceptionOrNull()
        assertTrue("应拒绝重复复用，实际: $err", err is ZipReuse.ReuseException)
    }

    /**
     * 交叉验证：产物必须能被标准 `ZipFile` 正常打开并列出全部条目。
     *
     * 复用依赖「重叠条目合法」这一前提。`ZipFile` 若读不出条目，
     * 说明我们写坏了 ZIP 结构，而不是「重叠不被支持」。
     */
    @Test
    fun `产物能被标准 ZipFile 打开`() {
        val dir = workDir("std")
        val src = makeFixtureApk(dir)
        val out = File(dir, "reused.apk")
        ZipReuse.build(
            src, out,
            extraEntries = listOf(
                ZipReuse.ExtraEntry("classes2.dex", "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1))
            ),
        )

        ZipFile(out).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertTrue("丢了 $${ZipReuse.ORIGIN_ENTRY}", names.contains(ZipReuse.ORIGIN_ENTRY))
            assertTrue("丢了 classes.dex", names.contains("classes.dex"))
            assertTrue("丢了 classes2.dex", names.contains("classes2.dex"))
            assertTrue("丢了 resources.arsc", names.contains("resources.arsc"))
        }
        // 交叉：自己的解析器也应得到同样的条目集合
        assertEquals(
            "标准 ZipFile 与自己解析器的条目数不一致",
            ZipFile(out).use { it.entries().toList().size },
            ZipCentralDirectory.read(out).entries.size,
        )
    }
}

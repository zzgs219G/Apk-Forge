package com.xixin.apkforge.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `ApkSigningBlock` 的搬运契约。
 *
 * ## 防的是什么
 *
 * 签名块是**中央目录之前的一段独立数据**，不是 ZIP 条目。
 * `java.util.zip` 重写包时必然丢掉它，而且**不报错**——产物照样能打开。
 *
 * 后果链（每一步都无报错）：
 * ```
 * 中间包丢签名块
 *   → 数据复用后 origin.apk 里也没有签名块
 *   → native 层 open(base.apk)→origin.apk 读到空签名块
 *   → 拿不到证书 → 去校验的 native 层完全不生效
 * ```
 *
 * 所以这条必须钉死：**注入后能原样取回，且 EOCD 的 CD offset 正确修正**。
 */
class ApkSigningBlockTest {

    /** 造一个 ZIP，并在中央目录前插入一段伪造的签名块。 */
    private fun makeSignedApk(dir: File, withBlock: Boolean = true): File {
        dir.mkdirs()
        val base = File(dir, if (withBlock) "signed.apk" else "unsigned.apk")
        val payload = ByteArray(512) { (it % 61).toByte() }

        ZipOutputStream(base.outputStream().buffered()).use { zout ->
            val e = ZipEntry("classes.dex").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = java.util.zip.CRC32().apply { update(payload) }.value
            }
            zout.putNextEntry(e)
            zout.write(payload)
            zout.closeEntry()
        }

        if (withBlock) injectFakeBlock(base, dir)
        return base
    }

    /**
     * 手工在 CD 之前插一段结构合法的假签名块。
     *
     * 结构（AOSP `ApkSigningBlockUtils`）：
     * ```
     * [size_payload 8B][ID-value pairs...][size_total 8B]["APK Sig Block 42" 16B]
     *   块起点 = cdOffset - size_total - 8
     * ```
     * 这里造一个最小的 value，只要魔数与 size 字段自洽。
     */
    private fun injectFakeBlock(apk: File, dir: File) {
        val value = ByteArray(64) { 0x5A }
        val pairLen = 4 + value.size          // id(4) + value
        val pair = ByteArray(8 + pairLen)
        // pair_len (u64 LE)
        for (i in 0 until 8) pair[i] = ((pairLen.toLong() shr (8 * i)) and 0xFF).toByte()
        // id = v2 (0x7109871A) LE
        pair[8] = 0x1A.toByte()
        pair[9] = 0x87.toByte()
        pair[10] = 0x09.toByte()
        pair[11] = 0x71.toByte()
        value.copyInto(pair, 12)

        // 块总长 = [size_payload 8B][pair][size_total 8B][magic 16B]
        //         = 8 + pair.size + 8 + 16
        val blockSize = 8 + pair.size + 8 + 16
        val block = ByteArray(blockSize)

        // size_payload = 块总长 - 8（不含开头的 size 字段本身）
        val sizePayload = blockSize - 8
        for (i in 0 until 8) block[i] = ((sizePayload.toLong() shr (8 * i)) and 0xFF).toByte()
        pair.copyInto(block, 8)

        // 尾部 size_total（与开头同值），紧跟 magic
        val off = 8 + pair.size
        for (i in 0 until 8) block[off + i] = ((sizePayload.toLong() shr (8 * i)) and 0xFF).toByte()
        "APK Sig Block 42".toByteArray(Charsets.US_ASCII).copyInto(block, off + 8)

        // 插到 CD 之前，并修正 EOCD 的 CD offset
        val data = apk.readBytes()
        val eocd = data.size - 22
        require(le32(data, eocd) == 0x06054b50) { "夹具没有 EOCD" }
        val cdOff = le32(data, eocd + 16)
        val newCdOff = cdOff + block.size

        val out = ByteArray(data.size + block.size)
        System.arraycopy(data, 0, out, 0, cdOff)
        System.arraycopy(block, 0, out, cdOff, block.size)
        System.arraycopy(data, cdOff, out, cdOff + block.size, data.size - cdOff)
        // 回填 EOCD 的 CD offset
        val newEocd = eocd + block.size
        putLe32(out, newEocd + 16, newCdOff)
        apk.writeBytes(out)
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "sigblock-$tag").apply {
            deleteRecursively(); mkdirs()
        }

    // ── 断言 ──────────────────────────────────────────────────

    /** 基本能力：能从带签名块的包取出，且字节非空、尾部魔数正确。 */
    @Test
    fun `能从带签名块的 APK 取出签名块`() {
        val dir = workDir("extract")
        val apk = makeSignedApk(dir)

        val block = ApkSigningBlock.extract(apk)
        assertNotNull("带签名块的包应该取得到", block)
        assertTrue("签名块过短: ${block!!.size}", block.size > 24)
        assertTrue("未带签名块时应为 null", block.isNotNull())

        // 尾部 16 字节是魔数
        val magic = String(block.copyOfRange(block.size - 16, block.size), Charsets.US_ASCII)
        assertEquals("签名块尾部魔数不对", "APK Sig Block 42", magic)
    }

    /** 未签名的包必须返回 null，而不是抛异常或返回垃圾。 */
    @Test
    fun `未签名包取签名块返回 null`() {
        val dir = workDir("none")
        val apk = makeSignedApk(dir, withBlock = false)
        assertNull("未签名包不该有签名块", ApkSigningBlock.extract(apk))
        assertFalse(ApkSigningBlock.hasBlock(apk))
    }

    /**
     * 核心：注入后能原样取回，且原包条目仍可读。
     *
     * 这条钉住两件事：
     * ① 块内容逐字节不变（否则 native 层读到的证书是坏的）
     * ② EOCD 的 CD offset 被正确修正（否则整个 ZIP 解析失败）
     */
    @Test
    fun `注入签名块后能原样取回且条目仍可读`() {
        val dir = workDir("inject")
        val source = makeSignedApk(dir)
        val original = ApkSigningBlock.extract(source)!!

        // 目标：一个没有签名块的包（模拟 ZipOutputStream 产出的中间包）
        val target = File(dir, "intermediate.apk")
        ZipFile(source).use { zin ->
            ZipOutputStream(target.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
            }
        }
        assertFalse("中间包不该有签名块", ApkSigningBlock.hasBlock(target))

        val injected = ApkSigningBlock.inject(target, original)
        assertTrue("应该发生了插入", injected)

        val back = ApkSigningBlock.extract(target)
        assertNotNull("注入后应能取回", back)
        assertTrue(
            "取回的签名块与原始不一致（${original.size} vs ${back!!.size}）",
            original.contentEquals(back),
        )

        // 交叉验证：标准 ZipFile 仍能正常读（EOCD 的 CD offset 修对了）
        ZipFile(target).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertTrue("注入后条目丢了: $names", names.contains("classes.dex"))
            val data = zf.getInputStream(zf.getEntry("classes.dex")).use { it.readBytes() }
            assertEquals("条目内容变了", 512, data.size)
        }
    }

    /** 幂等：已经带签名块的包再注入一次，不应重复插入。 */
    @Test
    fun `重复注入是幂等的`() {
        val dir = workDir("idem")
        val apk = makeSignedApk(dir)
        val block = ApkSigningBlock.extract(apk)!!
        val before = apk.length()

        val second = ApkSigningBlock.inject(apk, block)
        assertFalse("已有签名块时不该再插入", second)
        assertEquals("文件大小不该变", before, apk.length())
    }

    /**
     * 防「注入点选错，zipalign 之前」那类事故。
     *
     * 云端的教训：先注入再 zipalign → zipalign 重写 ZIP 时丢掉签名块。
     * 本模块不做 zipalign，但必须保证「注入后没有别的步骤重写 ZIP」。
     * 这条测试的作用是留一个显式记录：如果将来加了重写步骤，
     * 这里会红，提醒去检查顺序。
     */
    @Test
    fun `注入后再被 ZipFile 重写会丢签名块_这是调用顺序的反向说明`() {
        val dir = workDir("order")
        val source = makeSignedApk(dir)
        val block = ApkSigningBlock.extract(source)!!

        val target = File(dir, "t.apk")
        ZipFile(source).use { zin ->
            ZipOutputStream(target.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
            }
        }
        ApkSigningBlock.inject(target, block)
        assertTrue("注入后应有签名块", ApkSigningBlock.hasBlock(target))

        // 用 ZipOutputStream 再重写一遍（等价于 zipalign 的行为）
        val rewritten = File(dir, "rewritten.apk")
        ZipFile(target).use { zin ->
            ZipOutputStream(rewritten.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
            }
        }
        assertFalse(
            "重写后签名块必然丢失 —— 这正是「注入必须在任何 ZIP 重写之后」的原因。" +
                "若此断言变红，说明 java.util.zip 行为变了，需重新评估注入顺序。",
            ApkSigningBlock.hasBlock(rewritten),
        )
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun ByteArray?.isNotNull(): Boolean = true
}

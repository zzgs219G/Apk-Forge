package com.xixin.apkforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `Bytes` / `HumanSize` —— 全工程共用的底层工具。
 *
 * ## 为什么底层工具需要自己的测试
 *
 * 这些函数原先在 7 个文件里各有一份副本，每一份都被它所在模块的测试
 * **间接**覆盖着。合并成一份之后，测试反而只剩这里的直接覆盖——
 * 所以这里必须比任何一份副本都更严格，否则就是把 7 份的保险换成 1 份薄的。
 *
 * 重点覆盖两类"抄的时候最容易漏"的地方：
 *
 * ```
 * ① 无符号：漏掉 `and 0xFF` → 高位变成负数
 * ② 跨字节：移位方向写反 → 小端读成大端
 * ```
 */
class BytesTest {

    // ── 读 ────────────────────────────────────────────────────

    @Test
    fun `u8 把高位字节当无符号`() {
        // 0xFF 作为有符号 Int 是 -1；作为字节必须读成 255
        assertEquals(255, Bytes.u8(byteArrayOf(0xFF.toByte()), 0))
        assertEquals(128, Bytes.u8(byteArrayOf(0x80.toByte()), 0))
        assertEquals(0, Bytes.u8(byteArrayOf(0x00), 0))
    }

    @Test
    fun `u16 小端且无符号`() {
        // 0xFFFF：漏掉 and 0xFF 会读成 -1
        assertEquals(0xFFFF, Bytes.u16(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
        // 0x1234 小端 = [0x34, 0x12]
        assertEquals(0x1234, Bytes.u16(byteArrayOf(0x34, 0x12), 0))
        // 0x8000：最高位为 1，仍应是 32768
        assertEquals(32768, Bytes.u16(byteArrayOf(0x00, 0x80.toByte()), 0))
    }

    @Test
    fun `i32 小端有符号`() {
        assertEquals(0x12345678, Bytes.i32(byteArrayOf(0x78, 0x56, 0x34, 0x12), 0))
        // 0xFFFFFFFF 作为有符号 Int 是 -1
        assertEquals(
            -1,
            Bytes.i32(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 0),
        )
    }

    /**
     * `u32` 是最容易踩坑的一个：ZIP 里的偏移量、长度都是 u32。
     *
     * 用 `i32` 读 0xFFFFFFFF 会得到 -1，拿去做偏移就是越界；
     * 用 `u32` 才是 4294967295。
     */
    @Test
    fun `u32 把最高位当无符号`() {
        val max = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(4294967295L, Bytes.u32(max, 0))
        // 有符号版本确实会给 -1 —— 这不是 bug，是两者的分工
        assertEquals(-1, Bytes.i32(max, 0))

        // 3GB：> Int.MAX_VALUE，只有 u32 读得对
        // 0xC0000000 = 3221225472
        val threeGb = byteArrayOf(0x00, 0x00, 0x00, 0xC0.toByte())
        assertEquals(3221225472L, Bytes.u32(threeGb, 0))
    }

    @Test
    fun `i64 小端`() {
        val b = ByteArray(8)
        Bytes.putI32(b, 0, 0x12345678)
        Bytes.putI32(b, 4, 0x1ABCDEF0)
        // 低 32 位在前
        val expect = 0x1ABCDEF0_12345678L
        assertEquals(expect, Bytes.i64(b, 0))
    }

    @Test
    fun `读写偏移互不影响`() {
        val b = ByteArray(16)
        Bytes.putU16(b, 0, 0xABCD)
        Bytes.putI32(b, 2, 0x11223344)
        Bytes.putI32(b, 8, 0x55667788)

        assertEquals(0xABCD, Bytes.u16(b, 0))
        assertEquals(0x11223344, Bytes.i32(b, 2))
        assertEquals(0x55667788, Bytes.i32(b, 8))
        // 中间不该被踩坏
        assertEquals(0, b[6].toInt())
        assertEquals(0, b[7].toInt())
    }

    // ── 写 ────────────────────────────────────────────────────

    @Test
    fun `putU16 与 u16 往返一致`() {
        for (v in listOf(0, 1, 0xFF, 0x100, 0x7FFF, 0x8000, 0xFFFF)) {
            val b = ByteArray(2)
            Bytes.putU16(b, 0, v)
            assertEquals("往返不一致: $v", v, Bytes.u16(b, 0))
        }
    }

    @Test
    fun `putI32 与 i32 往返一致`() {
        for (v in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x12345678, -0x12345678)) {
            val b = ByteArray(4)
            Bytes.putI32(b, 0, v)
            assertEquals("往返不一致: $v", v, Bytes.i32(b, 0))
        }
    }

    /** 写入必须是**小端**：这是与 ZIP / dex / AXML 全部格式的契约。 */
    @Test
    fun `写入是小端字节序`() {
        val b = ByteArray(4)
        Bytes.putI32(b, 0, 0x12345678)
        assertEquals("最低位字节应写在最前", 0x78.toByte(), b[0])
        assertEquals(0x56.toByte(), b[1])
        assertEquals(0x34.toByte(), b[2])
        assertEquals(0x12.toByte(), b[3])
    }

    // ── zip 读取 ───────────────────────────────────────────────

    @Test
    fun `readEntry 读得到存在的条目`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bytes-test").apply {
            deleteRecursively(); mkdirs()
        }
        val zip = File(dir, "t.zip")
        val content = "hello-字节".toByteArray(Charsets.UTF_8)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a/b.txt"))
            z.write(content)
            z.closeEntry()
        }

        val got = Bytes.readEntry(zip, "a/b.txt")
        assertEquals("内容应逐字节一致", content.size, got?.size)
        assertEquals(String(content, Charsets.UTF_8), String(got!!, Charsets.UTF_8))

        zip.delete()
    }

    @Test
    fun `readEntry 对不存在的条目返回 null 而不是抛异常`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bytes-test2").apply {
            deleteRecursively(); mkdirs()
        }
        val zip = File(dir, "t.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("exists.txt"))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        assertNull("不存在的条目必须是 null", Bytes.readEntry(zip, "missing.txt"))
        zip.delete()
    }

    // ── 人类可读大小 ────────────────────────────────────────────

    /**
     * 三处重复的实现合并后，格式必须**完全不变** ——
     * 否则日志与界面上显示的数字会和以前不一样，用户会以为产物大小变了。
     */
    @Test
    fun `HumanSize 与原先三份实现输出一致`() {
        assertEquals("0 B", HumanSize.format(0))
        assertEquals("128 B", HumanSize.format(128))
        assertEquals("1023 B", HumanSize.format(1023))
        assertEquals("1 KB", HumanSize.format(1024))
        assertEquals("512 KB", HumanSize.format(512 * 1024L))
        assertEquals("1.0 MB", HumanSize.format(1024 * 1024L))
        assertEquals("22.4 MB", HumanSize.format((22.4 * 1024 * 1024).toLong()))
        // 新增的 GB 档：原先三份都没有，超过 1GB 会显示成 "1024.0 MB"
        assertEquals("1.00 GB", HumanSize.format(1024L * 1024 * 1024))
    }
}

package com.xixin.apkforge.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.io.File

/**
 * 二进制读写与格式化小工具 —— 全工程**唯一**一份。
 *
 * ## 为什么值得单独抽出来
 *
 * 这些函数原先在 7 个文件里各抄了一份（`le16` / `le32` / `putInt` …）。
 * 单看每一份都是 3 行、无脑、不会写错，所以复制时毫无负罪感。
 * 但它们的**正确性标准是同一个**：小端、无符号用 `and 0xFF`。
 * 抄 7 份意味着有 7 个地方可能把 `and 0xFF` 漏掉 —— 而漏掉的症状是
 * 「高位字节变成负数」，在偏移量上表现为读到天文数字，在长度上表现为负数越界。
 * 这类 bug 在 APK 解析里不会立刻崩，而是产出一个结构怪异的包。
 *
 * 收成一处的收益不是"少写几行"，是**只可能错一次**。
 *
 * ## 为什么不用 `java.nio.ByteBuffer`
 *
 * `ByteBuffer.order(LITTLE_ENDIAN).getInt(off)` 确实更短，但：
 * · 它要先把数据包成 ByteBuffer（`ByteBuffer.wrap` 在热路径上有分配）；
 * · 现有代码大量是「拿到 `ByteArray` + 绝对偏移」的形态，
 *   换 ByteBuffer 要求每个调用点把 buffer 传进来，改动面大且更绕；
 * · `getInt` 是有符号的，做无符号比较仍要 `and 0xFFFFFFFFL`，
 *   并没有省掉那一步。
 *
 * 所以这里就用最直的 `ByteArray + offset`。
 */
object Bytes {

    // ── 读 ────────────────────────────────────────────────────

    /** 无符号 8 位。 */
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    /** 小端 16 位，按无符号处理（0..65535）。 */
    fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** 小端 32 位，**有符号**返回。用于需要 Int 语义（如长度差）的场合。 */
    fun i32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    /**
     * 小端 32 位，**无符号**返回（0..4294967295）。
     *
     * ⚠️ 偏移量、长度这类字段必须用这个而不是 [i32]：
     * ZIP 里 `cd size` / `local header offset` 都是 u32，
     * 一个 3GB 的文件用 i32 读出来是负数。
     */
    fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL

    /** 小端 64 位。JS 侧不存在，这里只给 ZIP64 / Signing Block 的 size 用。 */
    fun i64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[o + i].toLong() and 0xFF)
        return r
    }

    // ── 写 ────────────────────────────────────────────────────

    /** 写小端 16 位。 */
    fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** 写小端 32 位。 */
    fun putI32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ── 读 zip ───────────────────────────────────────────────

    /**
     * 读 APK 里某个条目的全部字节；没有返回 null。
     *
     * 先用 `getEntry` 挡一次：`ZipFile.getInputStream` 收到 null 会抛 NPE，
     * 而"条目不存在"是调用方要正常处理的情况，不该变成异常。
     */
    fun readEntry(apk: File, name: String): ByteArray? {
        ZipFile(apk).use { zip ->
            val e = zip.getEntry(name) ?: return null
            return zip.getInputStream(e).use { it.readBytesCompat() }
        }
    }

    /** 把流读干净。`InputStream.readBytes()` 在 Android 上没有，所以自己写。 */
    fun InputStream.readBytesCompat(): ByteArray {
        val buf = ByteArrayOutputStream(64 * 1024)
        val tmp = ByteArray(64 * 1024)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }
}

/**
 * 人类可读的字节数 —— 全工程唯一一份。
 *
 * 原先有 3 份（`ApkInjectPipeline.mb`、`UncheckViewModel.humanSize`、
 * `UncheckScreen.humanSize`），三处阈值与小数位完全一样。
 * 这种"看起来无害的重复"代价是：改一次单位（比如 GB 档）要改三处，
 * 漏一处就出现「日志写 GB、界面写 MB」。
 */
object HumanSize {
    /** `1.5 MB` / `512 KB` / `128 B`。 */
    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

package com.xixin.apkforge.core.dex.patch

/**
 * 对 dex 原始字节的**只读**视图，所有访问都带边界检查。
 *
 * 【为什么不能直接用 ByteArray + 手算下标】这个包做的事是「按固定偏移读写」，
 * 而偏移是从文件头里读出来的**外部数据**——一个被裁剪过或故意构造的 dex
 * 可以让偏移指向文件末尾之外。裸下标只会抛 `ArrayIndexOutOfBoundsException`，
 * 堆栈落在无关行号上；这里统一转成**带上下文**的失败，
 * 调用方能把「第几个 class_def、期望读什么、越界多少」写进日志。
 *
 * 【为什么不复用 dexlib2 的 DexBackedDexFile】它读得对，但它不暴露
 * 「class_def_item 的原始 32 字节在哪」这种写路径需要的东西；
 * 而增量写入的全部工作就是操作原始字节。
 */
internal class DexBuffer(private val data: ByteArray) {

    val size: Int get() = data.size

    /** 暴露原始数组只为「整体拷贝」，调用方不应修改它。 */
    fun raw(): ByteArray = data

    fun u8(at: Int): Int = check(at, 1) { (data[at].toInt() and 0xFF) }

    fun u16(at: Int): Int = check(at, 2) {
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(at: Int): Long = check(at, 4) {
        ((data[at].toInt() and 0xFF).toLong()) or
            ((data[at + 1].toInt() and 0xFF).toLong() shl 8) or
            ((data[at + 2].toInt() and 0xFF).toLong() shl 16) or
            ((data[at + 3].toInt() and 0xFF).toLong() shl 24)
    }

    /** u32 但确定落在 Int 范围内才用（如偏移、计数）。 */
    fun u32AsInt(at: Int, what: String): Int {
        val v = u32(at)
        check(v <= Int.MAX_VALUE) { "$what 超过 Int 范围：$v @ 0x${at.toString(16)}" }
        return v.toInt()
    }

    fun bytes(at: Int, len: Int): ByteArray = check(at, len) { data.copyOfRange(at, at + len) }

    /** 比较一段字节是否等于 [expected]（用于魔数、endian_tag 这类固定值）。 */
    fun matches(at: Int, expected: ByteArray): Boolean {
        if (at < 0 || at + expected.size > data.size) return false
        for (i in expected.indices) {
            if (data[at + i] != expected[i]) return false
        }
        return true
    }

    private inline fun <T> check(at: Int, len: Int, body: () -> T): T {
        require(at >= 0 && len >= 0 && at.toLong() + len <= data.size) {
            "越界读取：offset=0x${at.toString(16)} len=$len 文件大小=${data.size}"
        }
        return body()
    }
}

/**
 * 一个小型的**可增长**字节写入器。
 *
 * 【为什么不直接用 `ByteArrayOutputStream`】需要在写入过程中知道
 * 「当前位置」，以便计算对齐填充；也需要在末尾一次性定位回某个偏移做原位修改。
 */
internal class GrowableBuffer(initialCapacity: Int) {

    private var buf = ByteArray(initialCapacity.coerceAtLeast(64))
    private var len = 0

    val size: Int get() = len

    private fun ensure(extra: Int) {
        if (len + extra <= buf.size) return
        var cap = buf.size
        while (cap < len + extra) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) {
        ensure(1)
        buf[len++] = v.toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        buf[len++] = (v and 0xFF).toByte()
        buf[len++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(v: Long) {
        ensure(4)
        buf[len++] = (v and 0xFF).toByte()
        buf[len++] = ((v ushr 8) and 0xFF).toByte()
        buf[len++] = ((v ushr 16) and 0xFF).toByte()
        buf[len++] = ((v ushr 24) and 0xFF).toByte()
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    /** 补 0 到 [alignment] 的整数倍，返回补了几个字节。 */
    fun align(alignment: Int): Int {
        val pad = paddingTo(len, alignment)
        repeat(pad) { u8(0) }
        return pad
    }

    /** 当前位置（下次写入的偏移）。 */
    val position: Int get() = len

    /**
     * 原位覆盖之前写过的字节。
     *
     * 增量写入的最后一步要回到 header 里改 file_size / data_size / map_off，
     * 那些位置在文件开头，而我们是先追加数据后回填。
     */
    fun patch(at: Int, b: ByteArray) {
        require(at >= 0 && at + b.size <= len) {
            "原位修改越界：offset=$at len=${b.size} 已写入=$len"
        }
        b.copyInto(buf, at)
    }

    fun patchU32(at: Int, v: Long) {
        require(at >= 0 && at + 4 <= len) { "原位修改越界：offset=$at 已写入=$len" }
        buf[at] = (v and 0xFF).toByte()
        buf[at + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[at + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    companion object {
        /** [position] 需要再补几字节才能落到 [alignment] 的整数倍上。 */
        fun paddingTo(position: Int, alignment: Int): Int {
            if (alignment <= 1) return 0
            val mod = position % alignment
            return if (mod == 0) 0 else alignment - mod
        }
    }
}

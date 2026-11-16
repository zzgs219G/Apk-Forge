package com.xixin.apkforge.core.dex.patch

/**
 * 按字节追加的缓冲区，**不装箱**。
 *
 * ## 为什么需要它
 *
 * 原来写注解/静态值用的是 `MutableList<Byte>`。那是 `ArrayList<Byte>`，
 * 每次 `+=` 都要把 `byte` 装箱成 `Byte` 对象。一个 30MB 的 dex 上有
 * 3.5 万个 annotation_item，每个几十字节 —— 上百万次装箱，
 * 在手机上实测占了注入总时间的 55%（3.5s / 18.3s 里的一半以上）。
 *
 * ## 与 GrowableBuffer 的区别
 *
 * `GrowableBuffer` 支持原位读写（`patchU32` / `peekU32`），那是回填阶段用的。
 * 这里只需要「往后追加」，所以更轻。
 */
internal class ByteSink(initialCapacity: Int = 64) {

    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
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

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    /** 取内容。会复制一份，调用方持有的是独立数组。 */
    fun toByteArray(): ByteArray = buf.copyOf(len)

    /** 直接拿到内部数组（长度可能大于 [size]），只在确定不修改时用。 */
    fun raw(): ByteArray = buf

    /**
     * 清空以便复用。
     *
     * 【为什么要复用】`remapItem` 会被调用 3.5 万次。每次新建一个 `ByteSink`
     * 都要分配数组；在手机上这些短命对象会频繁触发 GC，
     * 实测注解重写因此占到注入总时间的 63%。
     */
    fun reset() {
        len = 0
    }

    // ── LEB128 写入（内联到本类，避免经过 ArrayList<Byte>）──

    /** 无符号 LEB128。规则：每次取低 7 位，最高位表示「后面还有」。 */
    fun uleb128(value: Long) {
        require(value >= 0) { "uleb128 不能编码负数：$value" }
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            u8(b)
        } while (v != 0L)
    }

    /**
     * 有符号 LEB128。
     *
     * 与无符号的区别：除了「还有后续」位，最高有效位还要表示符号。
     * 终止条件是「剩下的值全 0 且当前符号位是 0」或「剩下的值全 1 且符号位是 1」。
     */
    fun sleb128(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            val rest = v shr 7
            val signBit = (b and 0x40) != 0
            val done = (rest == 0L && !signBit) || (rest == -1L && signBit)
            u8(if (done) b else b or 0x80)
            if (done) return
            v = rest
        }
    }

    /** `uleb128p1`：值 + 1 的无符号编码，0 表示「没有」。 */
    fun uleb128p1(value: Long) = uleb128(if (value < 0) 0L else value + 1)
}

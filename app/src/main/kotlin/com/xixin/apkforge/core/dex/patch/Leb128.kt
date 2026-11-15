package com.xixin.apkforge.core.dex.patch

/**
 * dex 里所有「不定长整数」的编解码：`uleb128` / `sleb128` / `uleb128p1`。
 *
 * 【为什么必须自己写】`class_data_item` 里的字段（方法数、方法下标差、访问标志、
 * code_off 差）全是 uleb128，且**方法数变了就要整体重编**。
 * 这四种编码在 dex 里出现的位置不同、语义也不同：
 *
 * | 编码 | 用途 | 取值范围 |
 * |---|---|---|
 * | `uleb128` | 计数、下标差、偏移差 | 无符号 |
 * | `sleb128` | 带符号的常量 | 有符号，Z 字？**不是**，是符号扩展 |
 * | `uleb128p1` | 可空的下标（0 表示「无」，实际值 = 编码值 - 1） | 无符号 |
 *
 * 【为什么不用 dexlib2 的 Leb128】它只提供读，写要靠 `Leb128Writer` 那种内部类，
 * 且没有针对「我这次要写多少字节、怎么影响后续偏移」的显式控制。
 * 这里的实现是可逐字节单测的纯函数，出错立刻能被断言抓到，
 * 而不是等到产物装不上。
 *
 * 实现依据：dex-format 规范 `leb128` 一节与 `art/libdexfile/dex/leb128.h`。
 */
internal object Leb128 {

    /**
     * 把一个无符号整数编码成 `uleb128`，追加到 [out]。
     *
     * 规则：每次取低 7 位，最高位表示「后面还有」。
     * 所以 0~127 编 1 字节、128~16383 编 2 字节，依此类推，最多 5 字节（u32）。
     */
    fun writeUleb128(out: MutableList<Byte>, value: Long) {
        require(value >= 0) { "uleb128 不能编码负数：$value" }
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out += b.toByte()
        } while (v != 0L)
    }

    /**
     * 把一个有符号整数编码成 `sleb128`。
     *
     * 与 uleb128 的区别：除了「还有后续」位，最高有效位还要用来表示符号。
     * 终止条件是「剩下的值全 0 且当前符号位是 0」或「剩下的值全 1 且当前符号位是 1」。
     */
    fun writeSleb128(out: MutableList<Byte>, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            // 算术右移 7 位：负数会补 1，正好用来判断「是否还能用符号位表示完」
            val rest = v shr 7
            val signBit = (b and 0x40) != 0
            val done = (rest == 0L && !signBit) || (rest == -1L && signBit)
            out += (if (done) b else b or 0x80).toByte()
            if (done) return
            v = rest
        }
    }

    /** 读 `uleb128`，返回 (值, 消耗的字节数)。 */
    fun readUleb128(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (true) {
            require(pos < data.size) { "uleb128 越界读取：offset=$offset" }
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 35) { "uleb128 过长（超过 5 字节）：offset=$offset" }
        }
        return result to (pos - offset)
    }

    /** 读 `uleb128p1`：0 表示「无」（null），否则实际值 = 编码值 - 1。 */
    fun readUleb128p1(data: ByteArray, offset: Int): Pair<Long, Int> {
        val (raw, n) = readUleb128(data, offset)
        return (if (raw == 0L) -1L else raw - 1) to n
    }

    /** 写出 `uleb128p1`。传 -1 表示 null。 */
    fun writeUleb128p1(out: MutableList<Byte>, value: Long) {
        require(value >= -1) { "uleb128p1 最小为 -1（表示 null）：$value" }
        writeUleb128(out, if (value < 0) 0L else value + 1)
    }

    /** 一个数值编码成 uleb128 后占几字节。用于预估新 class_data_item 的长度。 */
    fun uleb128Size(value: Long): Int {
        var v = value
        var n = 1
        while (v ushr 7 != 0L) {
            v = v ushr 7
            n++
        }
        return n
    }
}

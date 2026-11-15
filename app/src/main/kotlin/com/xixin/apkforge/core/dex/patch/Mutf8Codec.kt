package com.xixin.apkforge.core.dex.patch

/**
 * dex 字符串池用的 **MUTF-8** 编解码。
 *
 * 【为什么不直接用 `String.toByteArray(UTF_8)`】
 * MUTF-8 与标准 UTF-8 有三处不同，写错任何一处都会让产物在 ART 那边解析失败：
 *
 * 1. **U+0000 编码成两字节 `C0 80`**（标准 UTF-8 是单字节 `00`，
 *    而 `00` 在 MUTF-8 里是字符串结束符，不能出现在内容中）；
 * 2. **补充平面字符（emoji 等）按 UTF-16 代理对分别编码**，
 *    标准 UTF-8 用的是 4 字节形式；
 * 3. 字符串以单字节 `00` 结尾。
 *
 * 【排序规则】dex 要求 `string_ids` 按 **UTF-16 码元** 递增排序，
 * 而 Kotlin 的 `String` 就是 UTF-16，`compareTo` 正好按码元比较——
 * 所以排序可以直接用 `String.compareTo`，但**比较前必须正确解码**，
 * 否则代理对会被拼错，排序结果就错。
 */
internal object Mutf8Codec {

    /** 从 [data] 的 [offset] 处读一个 string_data_item，返回 (字符串, 消耗字节数)。 */
    fun readOne(data: ByteArray, offset: Int): Pair<String, Int> {
        val (_, lenBytes) = Leb128.readUleb128(data, offset)
        var p = offset + lenBytes
        val sb = StringBuilder()
        while (true) {
            require(p < data.size) { "MUTF-8 越界读取：offset=$offset" }
            val a = data[p].toInt() and 0xFF
            when {
                a == 0 -> {
                    p++
                    break
                }
                a and 0x80 == 0 -> {
                    sb.append(a.toChar())
                    p++
                }
                a and 0xE0 == 0xC0 -> {
                    val b = data[p + 1].toInt() and 0xFF
                    sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                    p += 2
                }
                a and 0xF0 == 0xE0 -> {
                    val b = data[p + 1].toInt() and 0xFF
                    val c = data[p + 2].toInt() and 0xFF
                    sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                    p += 3
                }
                else -> error("非法 MUTF-8 前导字节 0x${a.toString(16)} @ $p")
            }
        }
        return sb.toString() to (p - offset)
    }

    /**
     * 编码成完整的 `string_data_item`（含 uleb128 长度前缀与结尾 `00`）。
     *
     * 长度前缀是 **UTF-16 码元数**，不是字节数。
     */
    fun encodeItem(s: String): ByteArray {
        val body = mutableListOf<Byte>()
        for (ch in s) {
            val c = ch.code
            when {
                c == 0 -> {
                    body += 0xC0.toByte()
                    body += 0x80.toByte()
                }
                c < 0x80 -> body += c.toByte()
                c < 0x800 -> {
                    body += (0xC0 or (c shr 6)).toByte()
                    body += (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    body += (0xE0 or (c shr 12)).toByte()
                    body += (0x80 or ((c shr 6) and 0x3F)).toByte()
                    body += (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        val out = mutableListOf<Byte>()
        Leb128.writeUleb128(out, s.length.toLong())   // UTF-16 码元数
        out += body
        out += 0.toByte()
        return out.toByteArray()
    }
}

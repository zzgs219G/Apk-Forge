package com.xixin.apkforge.core.dex.patch

/**
 * 重写 `debug_info_item` 里的池引用。
 *
 * ## 结构
 *
 * ```
 * debug_info_item {
 *   uleb128  line_start
 *   uleb128  parameters_size
 *   uleb128p1 parameter_names[parameters_size]   ← string_idx（0 表示无）
 *   ubyte[]  state_machine                       ← 见下
 * }
 * ```
 *
 * 状态机指令（每条 1 字节 opcode，部分带负载）：
 *
 * | opcode | 名称 | 负载 |
 * |---|---|---|
 * | 0x00 | END_SEQUENCE | — |
 * | 0x01 | ADVANCE_PC | uleb128 addr_diff |
 * | 0x02 | ADVANCE_LINE | sleb128 line_diff |
 * | 0x03 | START_LOCAL | uleb128 reg, **uleb128p1 name_idx**, **uleb128p1 type_idx** |
 * | 0x04 | START_LOCAL_EXTENDED | uleb128 reg, **name_idx**, **type_idx**, **uleb128p1 sig_idx** |
 * | 0x05 | END_LOCAL | uleb128 reg |
 * | 0x06 | RESTART_LOCAL | uleb128 reg |
 * | 0x07 | SET_PROLOGUE_END | — |
 * | 0x08 | SET_EPILOGUE_BEGIN | — |
 * | 0x09 | SET_FILE | **uleb128p1 name_idx** |
 * | 0x0a..0xff | 特殊 opcode（行号增量） | — |
 *
 * 加粗的四个字段是池下标，池重排后必须重映射。
 *
 * ## 不修会怎样
 *
 * `debug_info` 只影响「崩溃栈的行号与局部变量名」。不修的话：
 * - 运行**可能**正常，但崩溃时行号全错、变量名是别的字符串
 * - 某些严格解析器（jadx）会在解析 debug 状态机时失败
 *
 * 本工程选择修，因为「产物能被严格解析器接受」是判断结构正确的重要信号。
 */
internal object DebugInfoRemapper {

    // 状态机 opcode
    private const val DBG_END_SEQUENCE = 0x00
    private const val DBG_ADVANCE_PC = 0x01
    private const val DBG_ADVANCE_LINE = 0x02
    private const val DBG_START_LOCAL = 0x03
    private const val DBG_START_LOCAL_EXTENDED = 0x04
    private const val DBG_END_LOCAL = 0x05
    private const val DBG_RESTART_LOCAL = 0x06
    private const val DBG_SET_PROLOGUE_END = 0x07
    private const val DBG_SET_EPILOGUE_BEGIN = 0x08
    private const val DBG_SET_FILE = 0x09
    private const val DBG_FIRST_SPECIAL = 0x0a

    /**
     * 重写一个 `debug_info_item`。
     *
     * @return 新的字节；调用方追加到文件末尾并更新 `code_item.debug_info_off`
     */
    fun remap(data: ByteArray, offset: Int, maps: EncodedValueRemapper.Maps): ByteArray {
        require(offset > 0 && offset < data.size) { "debug_info_off 越界：$offset" }
        var p = offset

        val out = mutableListOf<Byte>()

        // line_start
        val (lineStart, n1) = Leb128.readUleb128(data, p)
        p += n1
        Leb128.writeUleb128(out, lineStart)

        // parameters_size + parameter_names[]
        val (paramsSize, n2) = Leb128.readUleb128(data, p)
        p += n2
        Leb128.writeUleb128(out, paramsSize)
        repeat(paramsSize.toInt()) {
            val (raw, n) = Leb128.readUleb128(data, p)
            p += n
            writeMappedUleb128p1(out, raw, maps.stringMap)
        }

        // 状态机
        var guard = 0
        while (p < data.size) {
            require(guard++ < 1_000_000) { "debug_info 状态机疑似死循环 @ $offset" }
            val op = data[p].toInt() and 0xFF

            when (op) {
                DBG_END_SEQUENCE -> {
                    out += op.toByte()
                    p++
                    return out.toByteArray()
                }
                DBG_ADVANCE_PC -> {
                    out += op.toByte(); p++
                    val (v, n) = Leb128.readUleb128(data, p); p += n
                    Leb128.writeUleb128(out, v)
                }
                DBG_ADVANCE_LINE -> {
                    out += op.toByte(); p++
                    val (v, n) = Leb128.readSleb128(data, p); p += n
                    Leb128.writeSleb128(out, v)
                }
                DBG_START_LOCAL -> {
                    out += op.toByte(); p++
                    val (reg, n1b) = Leb128.readUleb128(data, p); p += n1b
                    Leb128.writeUleb128(out, reg)
                    val (name, n2b) = Leb128.readUleb128(data, p); p += n2b
                    writeMappedUleb128p1(out, name, maps.stringMap)
                    val (type, n3b) = Leb128.readUleb128(data, p); p += n3b
                    writeMappedUleb128p1(out, type, maps.typeMap)
                }
                DBG_START_LOCAL_EXTENDED -> {
                    out += op.toByte(); p++
                    val (reg, a) = Leb128.readUleb128(data, p); p += a
                    Leb128.writeUleb128(out, reg)
                    val (name, b) = Leb128.readUleb128(data, p); p += b
                    writeMappedUleb128p1(out, name, maps.stringMap)
                    val (type, c) = Leb128.readUleb128(data, p); p += c
                    writeMappedUleb128p1(out, type, maps.typeMap)
                    val (sig, d2) = Leb128.readUleb128(data, p); p += d2
                    writeMappedUleb128p1(out, sig, maps.stringMap)
                }
                DBG_END_LOCAL, DBG_RESTART_LOCAL -> {
                    out += op.toByte(); p++
                    val (reg, n) = Leb128.readUleb128(data, p); p += n
                    Leb128.writeUleb128(out, reg)
                }
                DBG_SET_PROLOGUE_END, DBG_SET_EPILOGUE_BEGIN -> {
                    out += op.toByte(); p++
                }
                DBG_SET_FILE -> {
                    out += op.toByte(); p++
                    val (name, n) = Leb128.readUleb128(data, p); p += n
                    writeMappedUleb128p1(out, name, maps.stringMap)
                }
                else -> {
                    // 0x0a..0xff：行号增量，无负载
                    require(op >= DBG_FIRST_SPECIAL) {
                        "未知 debug opcode 0x${op.toString(16)} @ $p"
                    }
                    out += op.toByte(); p++
                }
            }
        }
        // 没有 END_SEQUENCE 是畸形数据，原样返回已解析部分
        return out.toByteArray()
    }
    /**
     * 写 `uleb128p1` 并把值重映射。
     *
     * 编码是「值 + 1」：0 表示 NO_INDEX（无），n 表示下标 n-1。
     */
    private fun writeMappedUleb128p1(out: MutableList<Byte>, raw: Long, map: IntArray) {
        if (raw == 0L) {
            Leb128.writeUleb128(out, 0L)
        } else {
            val oldIdx = (raw - 1).toInt()
            val newIdx = map[oldIdx]
            Leb128.writeUleb128(out, newIdx + 1L)
        }
    }
}

package com.xixin.apkforge.core.dex.patch

/**
 * 重写 `encoded_value` 与 `encoded_annotation` 里的池引用。
 *
 * ## 为什么需要
 *
 * 注解和静态初值都是 `encoded_value` 的树形结构，里面的**字符串、类型、字段、
 * 方法、枚举**全都用**下标**指向对应的池。池一旦重排（插入新条目），
 * 这些下标就全部错位。
 *
 * 表现极具迷惑性：dexlib2 能正常解析、结构自检全绿，但反编译出来是
 *
 * ```
 * @java.util.Iterator(createListBuilder = {...}, item\4 = 1)   ← 本该是 @kotlin.Metadata(d1=..., k=1)
 * ```
 *
 * ——注解本身还在，只是每一项都指向了别的名字。运行时 `kotlin.Metadata` 找不到，
 * Kotlin 反射与 Compose 全部失效，app 启动即崩。
 *
 * ## 结构
 *
 * ```
 * encoded_value := (value_arg << 5) | value_type  然后按类型跟不同数量的负载
 *
 *   0x17 STRING      uleb128 string_idx            ← 要改
 *   0x18 TYPE        uleb128 type_idx              ← 要改
 *   0x19 FIELD       uleb128 field_idx             ← 要改
 *   0x1a METHOD      uleb128 method_idx            ← 要改
 *   0x1b ENUM        uleb128 field_idx             ← 要改
 *   0x1d ANNOTATION  内联一个 encoded_annotation   ← 递归
 *   0x1c ARRAY       uleb128 count + count 个 value ← 递归
 *   0x15 METHOD_TYPE uleb128 proto_idx             ← 要改
 *   0x16 METHOD_HANDLE uleb128 method_handle_idx   ← 要改
 *   其余（数值/布尔/null）负载是定长或 0 长，原样拷贝
 * ```
 *
 * ⚠️ 数值类型的负载长度由 `value_arg + 1` 决定，**不能假设固定宽度**——
 * 这是最容易写错的地方（例如 `int` 可以是 1~4 字节）。
 */
internal object EncodedValueRemapper {

    /** 一次重映射需要的全部映射表。 */
    class Maps(
        val stringMap: IntArray,
        val typeMap: IntArray,
        val fieldMap: IntArray,
        val methodMap: IntArray,
        val protoMap: IntArray,
    )

    // value_type 码
    private const val VT_BYTE = 0x00
    private const val VT_SHORT = 0x02
    private const val VT_CHAR = 0x03
    private const val VT_INT = 0x04
    private const val VT_LONG = 0x06
    private const val VT_FLOAT = 0x10
    private const val VT_DOUBLE = 0x11
    private const val VT_METHOD_TYPE = 0x15
    private const val VT_METHOD_HANDLE = 0x16
    private const val VT_STRING = 0x17
    private const val VT_TYPE = 0x18
    private const val VT_FIELD = 0x19
    private const val VT_METHOD = 0x1a
    private const val VT_ENUM = 0x1b
    private const val VT_ARRAY = 0x1c
    private const val VT_ANNOTATION = 0x1d
    private const val VT_NULL = 0x1e
    private const val VT_BOOLEAN = 0x1f

    /**
     * 从 [data] 的 [pos] 处读一个 `encoded_value`，重映射后写到 [out]。
     *
     * @return 读完后的新位置
     */
    fun readAndWrite(
        data: ByteArray,
        pos: Int,
        out: MutableList<Byte>,
        maps: Maps,
    ): Int {
        val b = data[pos].toInt() and 0xFF
        val valueType = b and 0x1F
        val valueArg = (b ushr 5) and 0x07
        var p = pos + 1

        when (valueType) {
            VT_STRING, VT_TYPE, VT_FIELD, VT_METHOD, VT_ENUM,
            VT_METHOD_TYPE, VT_METHOD_HANDLE -> {
                // ⚠️ 索引字段**不是 uleb128**：长度由 `value_arg + 1` 决定（1~4 字节小端）。
                // 按 uleb128 读会在 value_arg=1 时只吃 1 字节，从第二个元素起整体错位——
                // 实测表现是读到 0x0d 这类不存在的 value_type 而报错。
                val len = valueArg + 1
                var raw = 0L
                for (i in 0 until len) {
                    raw = raw or ((data[p + i].toLong() and 0xFF) shl (i * 8))
                }
                p += len
                val idx = raw.toInt()
                val mapped = when (valueType) {
                    VT_STRING -> maps.stringMap[idx]
                    VT_TYPE -> maps.typeMap[idx]
                    VT_FIELD, VT_ENUM -> maps.fieldMap[idx]
                    VT_METHOD -> maps.methodMap[idx]
                    VT_METHOD_TYPE -> maps.protoMap[idx]
                    else -> idx   // method_handle 池未变
                }
                // 重映射后仍用「能容纳该值的最小字节数」写，保持结构紧凑且合法
                val newLen = byteWidthOf(mapped)
                out += (((newLen - 1) shl 5) or valueType).toByte()
                for (i in 0 until newLen) {
                    out += ((mapped.toLong() ushr (i * 8)) and 0xFF).toByte()
                }
            }

            VT_ANNOTATION -> {
                out += b.toByte()
                p = readAnnotation(data, p, out, maps)
            }

            VT_ARRAY -> {
                out += b.toByte()
                val (count, n) = Leb128.readUleb128(data, p)
                p += n
                Leb128.writeUleb128(out, count)
                repeat(count.toInt()) {
                    p = readAndWrite(data, p, out, maps)
                }
            }

            VT_NULL, VT_BOOLEAN -> {
                // 无负载：value_arg 就是值本身
                out += b.toByte()
            }

            VT_BYTE, VT_SHORT, VT_CHAR, VT_INT, VT_LONG, VT_FLOAT, VT_DOUBLE -> {
                // 数值：负载长度 = value_arg + 1，原样拷贝
                val len = valueArg + 1
                out += b.toByte()
                for (i in 0 until len) out += data[p + i]
                p += len
            }

            else -> error("未知 encoded_value 类型 0x${valueType.toString(16)} @ $pos")
        }
        return p
    }

    /**
     * 读一个 `encoded_annotation`：`uleb128 type_idx` + `uleb128 size` + 若干元素。
     *
     * 元素是 `uleb128 name_string_idx` + `encoded_value`。
     */
    /**
     * 读一个 `encoded_annotation`：`uleb128 type_idx` + `uleb128 size` + 若干元素。
     *
     * ⚠️ 注意区别：`encoded_annotation` 开头的 `type_idx` 和元素的 `name_idx`
     * 都是**真正的 uleb128**，而 `encoded_value` 里的索引是「value_arg+1 字节」。
     * 两者格式不同，混淆会让整段注解错位。
     */
    fun readAnnotation(
        data: ByteArray,
        pos: Int,
        out: MutableList<Byte>,
        maps: Maps,
    ): Int {
        var p = pos
        val (typeIdx, n1) = Leb128.readUleb128(data, p)
        p += n1
        Leb128.writeUleb128(out, maps.typeMap[typeIdx.toInt()].toLong())

        val (size, n2) = Leb128.readUleb128(data, p)
        p += n2
        Leb128.writeUleb128(out, size)

        repeat(size.toInt()) {
            val (nameIdx, n3) = Leb128.readUleb128(data, p)
            p += n3
            Leb128.writeUleb128(out, maps.stringMap[nameIdx.toInt()].toLong())
            p = readAndWrite(data, p, out, maps)
        }
        return p
    }

    /** 一个非负整数用几个字节表示（最少 1 字节）。 */
    fun byteWidthOf(v: Int): Int = when {
        v < 0 -> 1
        v <= 0xFF -> 1
        v <= 0xFFFF -> 2
        v <= 0xFFFFFF -> 3
        else -> 4
    }
}

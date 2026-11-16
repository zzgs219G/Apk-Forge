package com.xixin.apkforge.core.dex.patch

/**
 * 重写 `class_data_item` 里的字段/方法索引。
 *
 * ## 为什么必须做这件事
 *
 * `class_data_item` 里存的是**下标**（`field_idx` / `method_idx`），而不是名字。
 * 往池中间插入新条目后，后面所有下标都会偏移，这些引用就全错了。
 *
 * 更麻烦的是它们是**变长差分编码**：
 *
 * ```
 * encoded_method {
 *   uleb128 method_idx_diff   ← 相对同段前一项的差
 *   uleb128 access_flags
 *   uleb128 code_off          ← 绝对值
 * }
 * ```
 *
 * 重映射后差值变化，元素还可能改变相对顺序（因为 `method_ids` 按
 * `(class_idx, name_idx, proto_idx)` 排序，而 name_idx 本身也变了），
 * 所以必须**重新排序 + 重新编码**，不能原地改数字。
 *
 * ## 做法
 *
 * 对每个类：
 * 1. 解析出四项（静态字段/实例字段/直接方法/虚方法）的绝对下标
 * 2. 用映射表换算成新下标，按新下标排序
 * 3. 重新差分编码
 * 4. 追加到文件末尾，把 `class_def_item.class_data_off` 指过去
 */
internal object ClassDataRemapper {

    /** 重映射所需的两张表。`fieldMap[oldIdx] = newIdx`。 */
    class Maps(
        val fieldMap: IntArray,
        val methodMap: IntArray,
        /**
         * 旧 code_off → 新 code_off。
         *
         * 带 try/catch 的方法，其 code_item 会被整体重写并搬到文件末尾
         * （因为 type_idx 是 uleb128，改后长度可能变，handler_off 会失效）。
         * 这里传进来，重编码时把 `code_off` 换成新位置。
         */
        val codeOffMap: Map<Int, Int> = emptyMap(),
    )

    /**
     * 重写一个 class_data_item，返回新的字节。
     *
     * @param data 原 dex 字节
     * @param offset class_data_item 的原偏移
     * @param maps 索引映射表
     */
    fun remap(data: ByteArray, offset: Int, maps: Maps): ByteArray {
        var p = offset

        val (staticSize, n1) = Leb128.readUleb128(data, p); p += n1
        val (instanceSize, n2) = Leb128.readUleb128(data, p); p += n2
        val (directSize, n3) = Leb128.readUleb128(data, p); p += n3
        val (virtualSize, n4) = Leb128.readUleb128(data, p); p += n4

        // ── 字段：解析成绝对下标 ──
        fun readFields(count: Int): List<Pair<Int, Long>> {
            var idx = 0L
            val out = ArrayList<Pair<Int, Long>>(count)
            repeat(count) {
                val (diff, a) = Leb128.readUleb128(data, p); p += a
                val (flags, b) = Leb128.readUleb128(data, p); p += b
                idx += diff
                out += idx.toInt() to flags
            }
            return out
        }

        val staticFields = readFields(staticSize.toInt())
        val instanceFields = readFields(instanceSize.toInt())

        // ── 方法：解析成绝对下标 + 绝对 code_off ──
        fun readMethods(count: Int): List<Triple<Int, Long, Long>> {
            var idx = 0L
            val out = ArrayList<Triple<Int, Long, Long>>(count)
            repeat(count) {
                val (diff, a) = Leb128.readUleb128(data, p); p += a
                val (flags, b) = Leb128.readUleb128(data, p); p += b
                val (codeOff, c) = Leb128.readUleb128(data, p); p += c
                idx += diff
                out += Triple(idx.toInt(), flags, codeOff)
            }
            return out
        }

        val directMethods = readMethods(directSize.toInt())
        val virtualMethods = readMethods(virtualSize.toInt())

        // ── 重映射 + 排序 ──
        fun remapFields(list: List<Pair<Int, Long>>): List<Pair<Int, Long>> =
            list.map { (idx, flags) -> maps.fieldMap[idx] to flags }
                .sortedBy { it.first }

        fun remapMethods(list: List<Triple<Int, Long, Long>>): List<Triple<Int, Long, Long>> =
            list.map { (idx, flags, off) ->
                // code_off 也要换成新位置（带 try 的方法其 code_item 被搬到了末尾）
                val newOff = if (off > 0) {
                    maps.codeOffMap[off.toInt()]?.toLong() ?: off
                } else off
                Triple(maps.methodMap[idx], flags, newOff)
            }.sortedBy { it.first }

        val newStatic = remapFields(staticFields)
        val newInstance = remapFields(instanceFields)
        val newDirect = remapMethods(directMethods)
        val newVirtual = remapMethods(virtualMethods)

        // ── 重新编码 ──
        val out = mutableListOf<Byte>()
        Leb128.writeUleb128(out, newStatic.size.toLong())
        Leb128.writeUleb128(out, newInstance.size.toLong())
        Leb128.writeUleb128(out, newDirect.size.toLong())
        Leb128.writeUleb128(out, newVirtual.size.toLong())

        fun writeFields(list: List<Pair<Int, Long>>) {
            var prev = 0
            list.forEach { (idx, flags) ->
                Leb128.writeUleb128(out, (idx - prev).toLong())
                Leb128.writeUleb128(out, flags)
                prev = idx
            }
        }

        fun writeMethods(list: List<Triple<Int, Long, Long>>) {
            var prev = 0
            list.forEach { (idx, flags, off) ->
                Leb128.writeUleb128(out, (idx - prev).toLong())
                Leb128.writeUleb128(out, flags)
                Leb128.writeUleb128(out, off)
                prev = idx
            }
        }

        writeFields(newStatic)
        writeFields(newInstance)
        writeMethods(newDirect)
        writeMethods(newVirtual)

        return out.toByteArray()
    }
}

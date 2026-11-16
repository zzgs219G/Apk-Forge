package com.xixin.apkforge.core.dex.patch

/**
 * 重写注解结构里的池引用。
 *
 * ## 结构
 *
 * ```
 * class_def_item.annotations_off
 *   └─ annotations_directory_item {
 *        u32 class_annotations_off      → annotation_set_item
 *        u32 fields_size
 *        u32 annotated_methods_size
 *        u32 annotated_parameters_size
 *        field_annotation[]             { u32 field_idx,  u32 annotations_off }
 *        method_annotation[]            { u32 method_idx, u32 annotations_off }
 *        parameter_annotation[]         { u32 method_idx, u32 annotations_off }
 *      }
 *
 * annotation_set_item        { u32 size; u32 entries[size] → annotation_item }
 * annotation_set_ref_list    { u32 size; u32 list[size]   → annotation_set_item }
 * annotation_item            { u8 visibility; encoded_annotation }
 * encoded_annotation         { uleb128 type_idx; uleb128 size; element[] }
 * element                    { uleb128 name_idx(string); encoded_value }
 * ```
 *
 * `field_idx` / `method_idx` / `type_idx` / `name_idx` 全都是池下标，
 * 池重排后必须逐个重映射。
 *
 * ## 为什么四个结构要分开落段
 *
 * ART 校验器要求**同一 type 的 item 连续**（map 只记「起点 + 个数」），
 * 且物理布局必须与 map 顺序一致。所以 `annotation_item`、
 * `annotation_set_item`、`annotation_set_ref_list`、
 * `annotations_directory_item` 必须各自成段、各自连续。
 *
 * 第一版把它们统统追加到文件末尾，四种 type 交错在一起，
 * 直接触发 `List too large for insns size` 一类错误。
 *
 * 所以这里通过 [Sink] 把每种结构路由到自己的段缓冲。
 */
internal object AnnotationRemapper {

    /** 按结构种类路由到各自的段缓冲，返回该结构在段内的偏移。 */
    interface Sink {
        /**
         * `annotation_item`：[buf] 的前 [len] 字节即内容。
         *
         * ⚠️ 传 (buf, len) 而不是 ByteArray：调用方每次 `toByteArray()` 都会复制，
         * 3.5 万个 annotation_item 就是 3.5 万次无谓分配 ——
         * 实测注解重写在手机上占注入总时间的 63%（11.2s / 17.9s），
         * 大部分是这个开销和它引发的 GC。
         */
        fun item(buf: ByteSink, len: Int): Int

        /** `annotation_set_item` */
        fun set(buf: GrowableBuffer, len: Int): Int

        /** `annotation_set_ref_list` */
        fun refList(buf: GrowableBuffer, len: Int): Int
    }

    /**
     * 重写一个 `annotations_directory_item` 及其引用到的所有子结构。
     *
     * @param cache 旧偏移 → 新偏移（段内），跨调用共享。四种结构共用一个表没问题：
     *              原文件里每个 item 的偏移都是唯一的。
     * @return 重写后的 directory 字节（调用方负责写入 directory 段）
     */
    fun remapDirectory(
        data: ByteArray,
        dirOff: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        sink: Sink,
        scratch: ByteSink = ByteSink(256),
    ): ByteArray {
        val buf = DexBuffer(data)
        val classAnnOff = buf.u32AsInt(dirOff, "class_ann")
        val fieldsSize = buf.u32AsInt(dirOff + 4, "fs")
        val methodsSize = buf.u32AsInt(dirOff + 8, "ms")
        val paramsSize = buf.u32AsInt(dirOff + 12, "ps")

        val out = GrowableBuffer(16 + (fieldsSize + methodsSize + paramsSize) * 8)

        // class_annotations_off：指向 annotation_set
        out.u32(if (classAnnOff == 0) 0L else remapSet(data, classAnnOff, maps, cache, sink, scratch).toLong() + 1)
        out.u32(fieldsSize.toLong())
        out.u32(methodsSize.toLong())
        out.u32(paramsSize.toLong())

        var p = dirOff + 16

        // field_annotation: field_idx 要重映射
        repeat(fieldsSize) {
            val idx = buf.u32AsInt(p, "field_idx")
            val setOff = buf.u32AsInt(p + 4, "set")
            out.u32(maps.fieldMap[idx].toLong())
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, sink, scratch).toLong() + 1)
            p += 8
        }

        // method_annotation 与 parameter_annotation: method_idx 要重映射
        repeat(methodsSize) {
            val idx = buf.u32AsInt(p, "m_idx")
            val setOff = buf.u32AsInt(p + 4, "set")
            out.u32(maps.methodMap[idx].toLong())
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, sink, scratch).toLong() + 1)
            p += 8
        }
        repeat(paramsSize) {
            val idx = buf.u32AsInt(p, "p_idx")
            val refOff = buf.u32AsInt(p + 4, "ref")
            out.u32(maps.methodMap[idx].toLong())
            out.u32(if (refOff == 0) 0L else remapRefList(data, refOff, maps, cache, sink, scratch).toLong() + 1)
            p += 8
        }

        return out.toByteArray()
    }

    /** `annotation_set_ref_list { u32 size; u32 list[size] }`，每项是 annotation_set 的偏移。 */
    private fun remapRefList(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        sink: Sink,
        scratch: ByteSink,
    ): Int {
        cache[off]?.let { return it }
        val buf = DexBuffer(data)
        val size = buf.u32AsInt(off, "rl_size")
        val out = GrowableBuffer(4 + size * 4)
        out.u32(size.toLong())
        repeat(size) { i ->
            val setOff = buf.u32AsInt(off + 4 + i * 4, "set")
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, sink, scratch).toLong() + 1)
        }
        val newOff = sink.refList(out, out.size)
        cache[off] = newOff
        return newOff
    }

    /** `annotation_set_item { u32 size; u32 entries[size] }`，每项是 annotation_item 的偏移。 */
    private fun remapSet(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        sink: Sink,
        scratch: ByteSink,
    ): Int {
        cache[off]?.let { return it }
        val buf = DexBuffer(data)
        val size = buf.u32AsInt(off, "as_size")
        val out = GrowableBuffer(4 + size * 4)
        out.u32(size.toLong())
        repeat(size) { i ->
            val itemOff = buf.u32AsInt(off + 4 + i * 4, "item")
            out.u32(remapItem(data, itemOff, maps, cache, sink, scratch).toLong() + 1)
        }
        val newOff = sink.set(out, out.size)
        cache[off] = newOff
        return newOff
    }

    /**
     * `annotation_item { u8 visibility; encoded_annotation }`。
     *
     * [scratch] 由调用方复用一个实例，避免 3.5 万次数组分配。
     */
    private fun remapItem(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        sink: Sink,
        scratch: ByteSink,
    ): Int {
        cache[off]?.let { return it }
        scratch.reset()
        scratch.u8(data[off].toInt() and 0xFF)             // visibility
        EncodedValueRemapper.readAnnotation(data, off + 1, scratch, maps)
        val newOff = sink.item(scratch, scratch.size)
        cache[off] = newOff
        return newOff
    }
}

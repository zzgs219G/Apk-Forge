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
 * annotation_set_item {  u32 size;  u32 entries[size] → annotation_item }
 * annotation_item     {  u8 visibility;  encoded_annotation }
 * encoded_annotation  {  uleb128 type_idx;  uleb128 size;  element[] }
 * element             {  uleb128 name_idx(string);  encoded_value }
 * ```
 *
 * `field_idx` / `method_idx` / `type_idx` / `name_idx` 全都是池下标，
 * 池重排后必须逐个重映射。
 *
 * ## 为什么不复用原偏移
 *
 * 重写后的字节长度可能变化（uleb128 变长），所以每个改写后的 item
 * 都要追加到文件末尾，并把引用它的地方指过去。
 * 这里按「旧偏移 → 新偏移」缓存，`annotation_set` 与 `annotation_item`
 * 都是共享的（多个类可能引用同一个），缓存能避免重复展开。
 */
internal object AnnotationRemapper {

    /**
     * 重写一个 `annotations_directory_item` 及其引用到的所有子结构。
     *
     * @param outSink 追加字节并返回新偏移（用于写子结构）
     * @param cache 旧偏移 → 新偏移，跨调用共享
     * @return 重写后的 directory 字节（调用方负责追加并记录新偏移）
     */
    fun remapDirectory(
        data: ByteArray,
        dirOff: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        outSink: (ByteArray) -> Int,
    ): ByteArray {
        val buf = DexBuffer(data)
        val classAnnOff = buf.u32AsInt(dirOff, "class_ann")
        val fieldsSize = buf.u32AsInt(dirOff + 4, "fs")
        val methodsSize = buf.u32AsInt(dirOff + 8, "ms")
        val paramsSize = buf.u32AsInt(dirOff + 12, "ps")

        val out = GrowableBuffer(16 + (fieldsSize + methodsSize + paramsSize) * 8)

        // class_annotations_off：指向 annotation_set，要重写后指向新位置
        out.u32(if (classAnnOff == 0) 0L else remapSet(data, classAnnOff, maps, cache, outSink).toLong())
        out.u32(fieldsSize.toLong())
        out.u32(methodsSize.toLong())
        out.u32(paramsSize.toLong())

        var p = dirOff + 16

        // field_annotation: field_idx 要重映射
        repeat(fieldsSize) {
            val idx = buf.u32AsInt(p, "field_idx")
            val setOff = buf.u32AsInt(p + 4, "set")
            out.u32(maps.fieldMap[idx].toLong())
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, outSink).toLong())
            p += 8
        }

        // method_annotation 与 parameter_annotation: method_idx 要重映射
        repeat(methodsSize) {
            val idx = buf.u32AsInt(p, "m_idx")
            val setOff = buf.u32AsInt(p + 4, "set")
            out.u32(maps.methodMap[idx].toLong())
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, outSink).toLong())
            p += 8
        }
        repeat(paramsSize) {
            val idx = buf.u32AsInt(p, "p_idx")
            val refOff = buf.u32AsInt(p + 4, "ref")
            out.u32(maps.methodMap[idx].toLong())
            // annotation_set_ref_list：u32 size + size 个 u32（指向 annotation_set）
            out.u32(if (refOff == 0) 0L else remapSetRefList(data, refOff, maps, cache, outSink).toLong())
            p += 8
        }

        return out.toByteArray()
    }

    /** `annotation_set_ref_list { u32 size; u32 list[size] }`，每项是 annotation_set 的偏移。 */
    private fun remapSetRefList(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        outSink: (ByteArray) -> Int,
    ): Int {
        cache[off]?.let { return it }
        val buf = DexBuffer(data)
        val size = buf.u32AsInt(off, "rl_size")
        val out = GrowableBuffer(4 + size * 4)
        out.u32(size.toLong())
        repeat(size) { i ->
            val setOff = buf.u32AsInt(off + 4 + i * 4, "set")
            out.u32(if (setOff == 0) 0L else remapSet(data, setOff, maps, cache, outSink).toLong())
        }
        val newOff = outSink(out.toByteArray())
        cache[off] = newOff
        return newOff
    }

    /** `annotation_set_item { u32 size; u32 entries[size] }`，每项是 annotation_item 的偏移。 */
    private fun remapSet(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        outSink: (ByteArray) -> Int,
    ): Int {
        cache[off]?.let { return it }
        val buf = DexBuffer(data)
        val size = buf.u32AsInt(off, "as_size")
        val out = GrowableBuffer(4 + size * 4)
        out.u32(size.toLong())
        repeat(size) { i ->
            val itemOff = buf.u32AsInt(off + 4 + i * 4, "item")
            out.u32(remapItem(data, itemOff, maps, cache, outSink).toLong())
        }
        val newOff = outSink(out.toByteArray())
        cache[off] = newOff
        return newOff
    }

    /** `annotation_item { u8 visibility; encoded_annotation }`。 */
    private fun remapItem(
        data: ByteArray,
        off: Int,
        maps: EncodedValueRemapper.Maps,
        cache: MutableMap<Int, Int>,
        outSink: (ByteArray) -> Int,
    ): Int {
        cache[off]?.let { return it }
        val out = mutableListOf<Byte>()
        out += data[off]                                   // visibility
        EncodedValueRemapper.readAnnotation(data, off + 1, out, maps)
        val newOff = outSink(out.toByteArray())
        cache[off] = newOff
        return newOff
    }
}

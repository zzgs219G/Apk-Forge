package com.xixin.apkforge.core.dex.patch

/**
 * 按 **ART 规范的段序** 拼接整份 dex。
 *
 * ## ART 的四条硬约束（`dex_file_verifier.cc`）
 *
 * 1. `CheckMap`：map 项 `offset` 必须**严格递增**（否则 `Out of order map item`）
 * 2. `CheckIntraSection`：用全局游标 `ptr_` 顺序推进，**物理布局必须与 map 顺序一致**
 * 3. `CheckPadding`：段与段之间只允许**全零填充**（否则 `Non-zero padding %x`）
 * 4. map 只记录「起点 + 个数」，所以**同一 type 的 item 必须连续**
 *
 * ## 段序从哪来
 *
 * 照抄 dexlib2 的 `DexWriter.writeTo()`。它写出的文件必然通过 ART 校验，
 * 这个顺序是被生产验证过的：
 *
 * ```
 * header
 * string_ids → type_ids → proto_ids → field_ids → method_ids → class_defs
 * string_data → type_list → encoded_array → annotation_item
 *   → annotation_set → annotation_set_ref → annotation_directory
 *   → debug_info → code_item → class_data
 * map_list
 * ```
 *
 * 注意 `data_off` 之后的所有段必须整体落在
 * `[data_off, data_off + data_size)` 内（`CheckIntraDataSection`），
 * 所以 data_off 取第一个 data 段的起点。
 *
 * ## 为什么能保持速度
 *
 * 未改动的段直接 `arraycopy` 原文件切片，**不做任何重新编码**。
 * 只有被池重排牵连的段才重新产出——而这些正是池注入本来就必须重写的那部分。
 */
internal object DexSectionLayout {

    /** 段定义。 */
    class Section(
        val type: Int,
        val count: Int,
        /**
         * 段的长度（字节）。**不要**为了拿长度就去 `toByteArray()` ——
         * 那会为 30MB 的段复制一份完整副本，在手机上直接 OOM。
         */
        val size: Int,
        /**
         * 把段内容写进 [target]。默认实现是「生成字节数组再写入」；
         * 大数据段应覆写它以直接写入，避免中间副本。
         */
        val writeTo: (GrowableBuffer) -> Unit,
    ) {
        constructor(type: Int, count: Int, render: () -> ByteArray) : this(
            type, count,
            size = run {
                // 只在没有更便宜的途径时才渲染一次取长度。
                // 调用方应尽量用带 size 的构造器。
                render().size
            },
            writeTo = { target -> target.bytes(render()) },
        )
    }

    /** 需要 1 字节对齐的段；其余按 4 对齐。与 ART `CheckIntraSectionIterate` 一致。 */
    fun alignmentOf(type: Int): Int = when (type) {
        DexFormat.TYPE_CLASS_DATA_ITEM,
        DexFormat.TYPE_STRING_DATA_ITEM,
        DexFormat.TYPE_DEBUG_INFO_ITEM,
        DexFormat.TYPE_ANNOTATION_ITEM,
        DexFormat.TYPE_ENCODED_ARRAY_ITEM,
        -> 1
        else -> 4
    }

    fun isDataSection(type: Int): Boolean = when (type) {
        DexFormat.TYPE_STRING_DATA_ITEM,
        DexFormat.TYPE_TYPE_LIST,
        DexFormat.TYPE_ANNOTATION_SET_REF_LIST,
        DexFormat.TYPE_ANNOTATION_SET_ITEM,
        DexFormat.TYPE_CLASS_DATA_ITEM,
        DexFormat.TYPE_CODE_ITEM,
        DexFormat.TYPE_DEBUG_INFO_ITEM,
        DexFormat.TYPE_ANNOTATION_ITEM,
        DexFormat.TYPE_ENCODED_ARRAY_ITEM,
        DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM,
        -> true
        else -> false
    }

    /**
     * 排版结果。
     *
     * 【为什么不直接返回最终字节】map_list 的内容依赖各段最终偏移，
     * 而各段内部还有交叉引用需要回填（例如 `code_item.debug_info_off`）。
     * 所以先只铺出段与段之间的位置关系，回填完成后再调 [appendMap]。
     */
    class Laid(
        /** 已铺好的段字节，**末尾追加了 map 的预留空间**，其内容尚未填写 */
        val buffer: GrowableBuffer,
        val mapOffset: Int,
        /** 预留给 map 的字节数（size 字段 + 各项） */
        val mapReserveSize: Int,
        val dataOffset: Int,
        val dataSize: Int,
        /** type → 段起点（绝对偏移） */
        val sectionOffsets: Map<Int, Int>,
        /** type → item 个数 */
        val sectionCounts: Map<Int, Int>,
        /** map 写完后文件的真实长度（由 [appendMap] 填充） */
        var finalSize: Int = -1,
    ) {
        fun base(type: Int): Int = sectionOffsets[type] ?: 0
    }

    fun layout(headerSize: Int, sourceHeader: ByteArray, sections: List<Section>): Laid {
        // 至少 1 个 header 项 + 各段 + map_list
        val mapItemCount = sections.size + 2

        // ⚠️ 两遍渲染：第一遍只取长度、算容量；第二遍才真正产出字节并立即写入。
        // 单遍「先渲染全部段、再拼装」会同时持有两份完整数据 ——
        // 29.9MB 的 dex 上实测直接 OOM：
        // `Failed to allocate a 29989720 byte allocation`（堆上限 256MB）。
        // 渲染过程本身是纯函数（只读输入、不依赖外部状态），跑两遍是安全的。
        var estimate = headerSize + 64 * 1024
        sections.forEach { estimate += it.size + 8 }
        estimate += 8 + mapItemCount * DexFormat.MAP_ITEM_WIDTH

        val out = GrowableBuffer(estimate)
        // header：先从原 dex 复制（保留 magic / endian / link 等字段），
        // 后续由调用方按新布局回填各段 size/off。
        // ⚠️ 曾写成 `ByteArray(headerSize)` 占位，结果 magic 全 0，
        // dex2oat 直接报 `Expected valid zip or dex file`。
        require(sourceHeader.size >= headerSize) {
            "原 header 只有 ${sourceHeader.size} 字节，不足 $headerSize"
        }
        out.bytes(sourceHeader.copyOfRange(0, headerSize))

        val offsets = LinkedHashMap<Int, Int>()
        val counts = LinkedHashMap<Int, Int>()
        // header 项：offset 0、count 1
        offsets[DexFormat.TYPE_HEADER_ITEM] = 0
        counts[DexFormat.TYPE_HEADER_ITEM] = 1

        var dataOffset = -1
        sections.forEachIndexed { i, s ->
            val align = alignmentOf(s.type)
            if (align > 1) out.align(align)
            if (dataOffset < 0 && isDataSection(s.type)) dataOffset = out.position
            offsets[s.type] = out.position
            counts[s.type] = s.count
            s.writeTo(out)
        }
        if (dataOffset < 0) dataOffset = out.position
        val dataSectionEnd = out.position

        // map_list 紧接最后一个段（4 对齐）
        out.align(4)
        val mapOffset = out.position
        offsets[DexFormat.TYPE_MAP_LIST] = mapOffset
        counts[DexFormat.TYPE_MAP_LIST] = 1

        val reserve = 4 + mapItemCount * DexFormat.MAP_ITEM_WIDTH
        out.bytes(ByteArray(reserve))       // 预留，appendMap 时填

        return Laid(
            buffer = out,
            mapOffset = mapOffset,
            mapReserveSize = reserve,
            dataOffset = dataOffset,
            dataSize = dataSectionEnd - dataOffset,
            sectionOffsets = offsets,
            sectionCounts = counts,
        )
    }

    /**
     * 把 map_list 写进 [Laid.buffer] 的预留区。
     *
     * 必须在所有交叉引用回填**之后**调用——map 依赖各段起点，
     * 而段内回填不改段起点，所以顺序上只要在最后即可。
     */
    /** @return map_list 写完后在文件里的结束偏移（= 最终 file_size） */
    fun appendMap(laid: Laid): Int {
        val out = laid.buffer
        // 按 offset 升序：ART 的 CheckMap 只校验 offset 严格递增，
        // 不校验 type 顺序（原版 dex 的 map 也是 offset 序、type 乱序）。
        val ordered = laid.sectionOffsets.entries
            .filter { (laid.sectionCounts[it.key] ?: 0) > 0 }
            .sortedBy { it.value }

        var p = laid.mapOffset
        out.patchU32(p, ordered.size.toLong())
        p += 4
        ordered.forEach { (type, off) ->
            out.patchU16(p, type)
            out.patchU16(p + 2, 0)
            out.patchU32(p + 4, (laid.sectionCounts[type] ?: 0).toLong())
            out.patchU32(p + 8, off.toLong())
            p += DexFormat.MAP_ITEM_WIDTH
        }
        // 预留区按「段数 + 2」算，但 size 为 0 的段不写进 map，
        // 所以实际项数往往更少 —— 多余字节是 0，必须裁掉：
        // map_list 之后还挂着 24 字节零填充时，文件大小与实际内容对不上，
        // 虽然 ART 不报错，但会让「map 是文件最后一项」这条不变量失效，
        // 后续注入时难以判断真实边界。
        laid.finalSize = p
        return p
    }

    /**
     * 只算某一段在最终文件里的绝对起点，不产出字节。
     *
     * 【为什么需要】`class_data_item` 里的 `code_off` 是 uleb128 变长字段，
     * 必须是**最终绝对偏移**，排版后无法原地补丁。
     * 而 `class_data` 段在 `code_item` 段之后，所以只要按顺序累加
     * 「header + 各段（含对齐）」，就能在生成 class_data 之前知道 code_item 的基址。
     *
     * @param sections 完整段列表（顺序即物理顺序）
     * @param sizes type → 该段字节数。不在表里的段视为长度未知，直接返回 -1。
     * @param target 要求基址的段 type
     */
    fun offsetOf(
        headerSize: Int,
        sections: List<Int>,
        sizes: Map<Int, Int>,
        target: Int,
    ): Int {
        var pos = headerSize
        for (type in sections) {
            val align = alignmentOf(type)
            if (align > 1) {
                val pad = (align - pos % align) % align
                pos += pad
            }
            if (type == target) return pos
            val size = sizes[type] ?: return -1
            pos += size
        }
        return -1
    }
}

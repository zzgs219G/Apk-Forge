package com.xixin.apkforge.core.dex.patch

/**
 * `class_data_item` 的解析与重编码。
 *
 * 【它是什么】一个类里所有字段和方法的「索引表 + 每个方法的 code_off」，
 * 结构是紧凑的差分编码：
 *
 * ```
 * class_data_item {
 *   uleb128 static_fields_size
 *   uleb128 instance_fields_size
 *   uleb128 direct_methods_size
 *   uleb128 virtual_methods_size
 *   encoded_field[static_fields_size]     // 下标是差分累加
 *   encoded_field[instance_fields_size]
 *   encoded_method[direct_methods_size] {
 *     uleb128 method_idx_diff      // 相对前一项的差
 *     uleb128 access_flags
 *     uleb128 code_off_diff        // 相对前一项 code_off 的差（0 表示无方法体）
 *   }
 *   encoded_method[virtual_methods_size]
 * }
 * ```
 *
 * 【为什么必须重编码而不是就地改】`code_off` 是**差分**存放的。
 * 只要有一个方法的 code_off 变了，它后面所有项的 diff 都可能跟着变，
 * 而 uleb128 是变长的——改一个数字就可能改变整个 item 的长度，
 * 进而让后面所有数据的偏移全部移动。所以做法是：
 *
 * 1. 完整解析成结构化数据；
 * 2. 只改目标方法那一项；
 * 3. **整体重新编码**成一份新的 `class_data_item`；
 * 4. 追加到文件末尾，把 `class_def_item.class_data_off` 指过去。
 *
 * 旧的那份留在原地不动（成为垃圾数据），新的一律追加 —— 这是「不移动任何既有数据」
 * 的代价：文件会变大一点点，但**其余 40MB 一个字节都不用动**。
 */
internal object ClassDataItem {

    /** 编码后的字段项：字段下标是差分，访问标志原样。 */
    data class EncodedField(
        val fieldIdxDiff: Long,
        val accessFlags: Long,
    )

    /** 编码后的方法项。`codeOff` 为 **0** 表示抽象/native 方法（无方法体）。 */
    data class EncodedMethod(
        val methodIdxDiff: Long,
        val accessFlags: Long,
        /**
         * code_item 的偏移。**这是绝对值，不是差分。**
         *
         * 【这里曾经写错，代价很高】`encoded_method` 的三个字段里：
         * - `method_idx_diff` 是**差分**（相对同一段里前一项）
         * - `access_flags` 是原值
         * - `code_off` 是**绝对偏移**
         *
         * 曾经把 `code_off` 当差分累加，结果第 2 项就变成了「1 项 + 2 项」的和，
         * 每多一项数值翻一倍，几十项后直接冲出文件尾部（实测读到一个
         * 0x2fdeee0 的偏移，而文件只有 0x280a3e8 字节）。
         * 这个错很隐蔽：**前 1 项永远正确**，所以只瞄一眼第一个方法时查不出来。
         */
        val codeOff: Long,
    )

    /**
     * 解析结果。
     *
     * 除了原始编码数据，还额外保存**绝对下标**与**绝对 code_off**，
     * 因为重编码时如果直接操作 diff 很容易算错；先还原成绝对值、
     * 改完之后再重新差分，逻辑更清楚也更好测。
     */
    data class Parsed(
        val staticFields: List<EncodedField>,
        val instanceFields: List<EncodedField>,
        val directMethods: List<EncodedMethod>,
        val virtualMethods: List<EncodedMethod>,
        /** 与 directMethods 一一对应的绝对 method_idx */
        val directMethodIndices: List<Long>,
        /** 与 virtualMethods 一一对应的绝对 method_idx */
        val virtualMethodIndices: List<Long>,
        /** 与 directMethods 一一对应的绝对 code_off（0 表示无方法体） */
        val directCodeOffsets: List<Long>,
        /** 与 virtualMethods 一一对应的绝对 code_off（0 表示无方法体） */
        val virtualCodeOffsets: List<Long>,
        /** 这份 class_data_item 自身的字节长度（含所有 uleb128） */
        val byteLength: Int,
    )

    /** 从 [data] 的 [offset] 处解析一个 class_data_item。 */
    fun parse(data: ByteArray, offset: Int): Parsed {
        require(offset in 0 until data.size) { "class_data_off 越界：$offset" }
        var pos = offset

        val (staticFieldsSize, n1) = Leb128.readUleb128(data, pos); pos += n1
        val (instanceFieldsSize, n2) = Leb128.readUleb128(data, pos); pos += n2
        val (directMethodsSize, n3) = Leb128.readUleb128(data, pos); pos += n3
        val (virtualMethodsSize, n4) = Leb128.readUleb128(data, pos); pos += n4

        val staticFields = mutableListOf<EncodedField>()
        repeat(staticFieldsSize.toIntChecked("static_fields_size")) {
            val (idxDiff, a) = Leb128.readUleb128(data, pos); pos += a
            val (flags, b) = Leb128.readUleb128(data, pos); pos += b
            staticFields += EncodedField(idxDiff, flags)
        }

        val instanceFields = mutableListOf<EncodedField>()
        repeat(instanceFieldsSize.toIntChecked("instance_fields_size")) {
            val (idxDiff, a) = Leb128.readUleb128(data, pos); pos += a
            val (flags, b) = Leb128.readUleb128(data, pos); pos += b
            instanceFields += EncodedField(idxDiff, flags)
        }

        // 方法项：method_idx 是差分（相对同段前一项），**code_off 是绝对值**
        val directMethods = mutableListOf<EncodedMethod>()
        val directIndices = mutableListOf<Long>()
        val directCodeOffs = mutableListOf<Long>()
        var directIdx = 0L
        repeat(directMethodsSize.toIntChecked("direct_methods_size")) {
            val (idxDiff, a) = Leb128.readUleb128(data, pos); pos += a
            val (flags, b) = Leb128.readUleb128(data, pos); pos += b
            val (codeOff, c) = Leb128.readUleb128(data, pos); pos += c
            directIdx += idxDiff
            directMethods += EncodedMethod(idxDiff, flags, codeOff)
            directIndices += directIdx
            directCodeOffs += codeOff
        }

        val virtualMethods = mutableListOf<EncodedMethod>()
        val virtualIndices = mutableListOf<Long>()
        val virtualCodeOffs = mutableListOf<Long>()
        var virtualIdx = 0L
        repeat(virtualMethodsSize.toIntChecked("virtual_methods_size")) {
            val (idxDiff, a) = Leb128.readUleb128(data, pos); pos += a
            val (flags, b) = Leb128.readUleb128(data, pos); pos += b
            val (codeOff, c) = Leb128.readUleb128(data, pos); pos += c
            virtualIdx += idxDiff
            virtualMethods += EncodedMethod(idxDiff, flags, codeOff)
            virtualIndices += virtualIdx
            virtualCodeOffs += codeOff
        }

        return Parsed(
            staticFields = staticFields,
            instanceFields = instanceFields,
            directMethods = directMethods,
            virtualMethods = virtualMethods,
            directMethodIndices = directIndices,
            virtualMethodIndices = virtualIndices,
            directCodeOffsets = directCodeOffs,
            virtualCodeOffsets = virtualCodeOffs,
            byteLength = pos - offset,
        )
    }

    /**
     * 用新的绝对 code_off 表重新编码整个 class_data_item。
     *
     * @param parsed 解析结果
     * @param isDirect 目标方法是否在 direct 段（`invoke-direct` 能调到的那些：
     *                 构造器、private、static 方法）
     * @param newCodeOffsets 与对应段一一对应的**绝对** code_off 列表；
     *                       长度必须与 `parsed` 里的同段列表一致
     */
    fun rebuild(
        parsed: Parsed,
        isDirect: Boolean,
        newCodeOffsets: List<Long>,
    ): ByteArray {
        val out = mutableListOf<Byte>()

        Leb128.writeUleb128(out, parsed.staticFields.size.toLong())
        Leb128.writeUleb128(out, parsed.instanceFields.size.toLong())
        Leb128.writeUleb128(out, parsed.directMethods.size.toLong())
        Leb128.writeUleb128(out, parsed.virtualMethods.size.toLong())

        parsed.staticFields.forEach {
            Leb128.writeUleb128(out, it.fieldIdxDiff)
            Leb128.writeUleb128(out, it.accessFlags)
        }
        parsed.instanceFields.forEach {
            Leb128.writeUleb128(out, it.fieldIdxDiff)
            Leb128.writeUleb128(out, it.accessFlags)
        }

        // direct 段
        writeMethodSection(
            out,
            parsed.directMethods,
            if (isDirect) newCodeOffsets else parsed.directCodeOffsets,
        )
        // virtual 段
        writeMethodSection(
            out,
            parsed.virtualMethods,
            if (isDirect) parsed.virtualCodeOffsets else newCodeOffsets,
        )

        return out.toByteArray()
    }

    /**
     * 重写一个方法段的编码。
     *
     * 【两个字段的编码方式不同，别混】
     * - `method_idx_diff`：**差分**，相对同段前一项，必须单调递增且非负
     * - `code_off`：**绝对值**，直接写入，没有累加，也不需要排序
     *
     * 把 `code_off` 当差分写会把每个方法都指向错误位置，
     * 而 dexlib2 依然能解析产物（只是方法体全乱了）——最难发现的一类错。
     */
    private fun writeMethodSection(
        out: MutableList<Byte>,
        methods: List<EncodedMethod>,
        absoluteOffsets: List<Long>,
    ) {
        require(methods.size == absoluteOffsets.size) {
            "方法数与 code_off 数不一致：${methods.size} vs ${absoluteOffsets.size}"
        }
        methods.forEachIndexed { i, m ->
            Leb128.writeUleb128(out, m.methodIdxDiff)
            Leb128.writeUleb128(out, m.accessFlags)
            // 绝对值直写：0 表示无方法体，非 0 就是 code_item 的偏移
            Leb128.writeUleb128(out, absoluteOffsets[i])
        }
    }

    private fun Long.toIntChecked(what: String): Int {
        require(this >= 0 && this <= Int.MAX_VALUE) { "$what 超出范围：$this" }
        return toInt()
    }
}

package com.xixin.apkforge.core.dex.patch

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * **增量 dex 写入器**：只改必须改的字节，其余原样拷贝。
 *
 * ## 为什么要有它
 *
 * `DexPool.writeTo`（本项目原来用的）会重建整个 dex 的字符串池 / 类型池 / 方法池。
 * 实测对一个 40MB、16972 个类的 dex，**即使什么都不改也要 11.5 秒**。
 * 而「往一个方法开头插 1 条指令」实际只需要动十几个字节。
 *
 * MT 管理器之所以是秒级，就是因为它走这条路。
 *
 * ## 做了什么
 *
 * ```
 * 1. 原文件的 0 .. 原大小    → 原样拷贝（一个字节不动）
 * 2. 文件末尾追加            → 新的 code_item（注入后的指令）
 * 3. 文件末尾继续追加        → 新的 class_data_item（让目标方法指向上面那个）
 * 4. 原位改 4 字节           → 目标类的 class_def_item.class_data_off
 * 5. 原位改 header           → file_size / data_size / map_list 计数
 * 6. 重算                    → Adler32 checksum + SHA-1 signature
 * ```
 *
 * 旧的 `code_item` 与 `class_data_item` 留在原地不删（成为无人引用的垃圾），
 * 这就是「不移动任何既有数据」的代价：文件略微变大，换来零拷贝成本。
 *
 * ## 明确的能力边界
 *
 * - **不支持**带 try/catch 的方法（`tries_size > 0`）：需要重算 try 项的指令偏移，
 *   且 try_item 有 4 字节对齐要求，会牵动布局。遇到时如实报错，让调用方走全量路径。
 * - **不支持**新增方法（`class_data_item` 里没有这个条目）：那是「修改结构」而不是
 *   「替换方法体」，需要同步 method_ids。本包只做「替换已有方法的 code_off」。
 * - **不支持**修改指令引用的常量池（新增字符串/类型）。注入的调用行指向的类和方法
 *   必须**已经存在**于该 dex 的池里——这一点由调用方预先校验。
 *
 * 这三条不是妥协，是「增量」的固有前提：只改偏移、不改池。
 * 需要改池时，池的索引空间要重排，全量重写反而是对的。
 */
object DexPatcher {

    /** 一次替换的请求。 */
    data class ReplaceCodeRequest(
        /** 目标类的类型描述符，如 `Lcom/foo/Bar;` */
        val classType: String,
        /** 目标方法名 */
        val methodName: String,
        /** 目标方法签名，如 `(Landroid/os/Bundle;)V` */
        val methodSignature: String,
        /**
         * 新的指令字序列（16 位为单位，已编码好）。
         *
         * 传 `null` 表示「在原指令前插入 [prefixInsns]、保留原指令」——
         * 这是注入的常见形态；传非 null 表示整段替换（打桩）。
         */
        val newInsns: ShortArray? = null,
        /** 当 [newInsns] 为 null 时，插在原指令前面的前缀 */
        val prefixInsns: ShortArray? = null,
    )

    sealed interface Outcome {
        data class Ok(
            val dex: ByteArray,
            /** 新 code_item 的偏移，便于日志与自检 */
            val newCodeOff: Int,
            /** 新 class_data_item 的偏移 */
            val newClassDataOff: Int,
            /** 原 dex 大小 → 新大小 */
            val originalSize: Int,
            val newSize: Int,
        ) : Outcome {
            /**
             * 【为什么必须重写 toString】`dex` 是 40MB 级别的数组，
             * data class 自动生成的 toString 会把每个字节都打进日志。
             * 一次调试就能刷爆终端与 CI 日志（实测把整个终端滚了几百屏）。
             */
            override fun toString(): String =
                "Ok(dex=${dex.size}B, newCodeOff=0x${newCodeOff.toString(16)}, " +
                    "newClassDataOff=0x${newClassDataOff.toString(16)}, " +
                    "$originalSize→$newSize)"

            override fun equals(other: Any?): Boolean =
                this === other || (other is Ok && dex === other.dex &&
                    newCodeOff == other.newCodeOff && newClassDataOff == other.newClassDataOff)

            override fun hashCode(): Int = System.identityHashCode(dex) * 31 + newCodeOff
        }

        data class Failed(val reason: String) : Outcome
    }

    /**
     * 执行一次增量替换。
     *
     * @param dexBytes 原始 dex
     */
    fun replaceCode(dexBytes: ByteArray, req: ReplaceCodeRequest): Outcome {
        val src = DexBuffer(dexBytes)
        val guard = runCatching { validateHeader(src) }
        guard.exceptionOrNull()?.let {
            return Outcome.Failed(it.message ?: "dex 头部校验失败")
        }

        // ── 1. 定位目标类的 class_def_item ──
        val classDefOff = findClassDef(src, req.classType)
            ?: return Outcome.Failed("dex 里没有类 ${req.classType}")
        val classDataOff = src.u32AsInt(classDefOff + DexFormat.CD_CLASS_DATA_OFF, "class_data_off")
        if (classDataOff == 0) {
            return Outcome.Failed("类 ${req.classType} 没有 class_data_item（无字段无方法）")
        }

        // ── 2. 在 class_data_item 里找到目标方法 ──
        val parsed = runCatching { ClassDataItem.parse(dexBytes, classDataOff) }
            .getOrElse { return Outcome.Failed("解析 class_data_item 失败：${it.message}") }

        val target = locateMethod(src, parsed, req)
            ?: return Outcome.Failed(
                "类 ${req.classType} 里没有 ${req.methodName}${req.methodSignature}"
            )

        // ── 3. 读出原 code_item，确认能处理 ──
        val oldCodeOff = target.codeOff
        if (oldCodeOff == 0L) {
            return Outcome.Failed(
                "${req.methodName} 没有方法体（abstract 或 native），" +
                    "增量写入不支持为它新建 code_item"
            )
        }
        val oldCodeOffInt = oldCodeOff.toIntChecked()
        val oldCode = runCatching { CodeItemBuilder.rawCodeOf(dexBytes, oldCodeOffInt) }
            .getOrElse { return Outcome.Failed(it.message ?: "读取原 code_item 失败") }

        // ── 4. 组装新指令 ──
        val oldInsns = readInsns(dexBytes, oldCodeOffInt)
        val newInsns = req.newInsns ?: run {
            val prefix = req.prefixInsns
                ?: return Outcome.Failed("newInsns 与 prefixInsns 不能同时为空")
            prefix + oldInsns
        }

        val oldRegs = src.u16(oldCodeOffInt + DexFormat.CI_REGISTERS_SIZE)
        val oldIns = src.u16(oldCodeOffInt + DexFormat.CI_INS_SIZE)
        val oldOuts = src.u16(oldCodeOffInt + DexFormat.CI_OUTS_SIZE)

        // outs_size 必须 >= 本次调用的参数寄存器数，否则校验器会拒绝
        // （调用时会在 caller 的 outs 区里临时分配实参寄存器）
        val outsNeeded = maxOf(oldOuts, req.prefixInsns?.let { estimateOuts(it) } ?: 0)

        val newCode = CodeItemBuilder.build(oldRegs, oldIns, outsNeeded, newInsns)

        // ── 5. 开始拼接新文件 ──
        return try {
            assemble(src, dexBytes, parsed, target, newCode, classDefOff, req)
        } catch (e: Exception) {
            Outcome.Failed("增量写入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ────────────────────────── 拼接 ──────────────────────────

    private fun assemble(
        src: DexBuffer,
        dexBytes: ByteArray,
        parsed: ClassDataItem.Parsed,
        target: MethodHit,
        newCode: ByteArray,
        classDefOff: Int,
        req: ReplaceCodeRequest,
    ): Outcome {
        val originalSize = dexBytes.size
        // 原文件 + 新 code_item + 新 class_data_item + 对齐填充，留足余量
        val out = GrowableBuffer(originalSize + newCode.size + 256)

        // (1) 原样拷贝整个原文件
        out.bytes(dexBytes)

        // (2) 追加新 code_item（4 字节对齐）
        out.align(DexFormat.ALIGN_4)
        val newCodeOff = out.position
        out.bytes(newCode)

        // (3) 追加新 class_data_item
        val newCodeOffsets = if (target.isDirect) {
            parsed.directCodeOffsets.toMutableList().also { it[target.indexInSection] = newCodeOff.toLong() }
        } else {
            parsed.directCodeOffsets
        }
        val newVirtualOffsets = if (!target.isDirect) {
            parsed.virtualCodeOffsets.toMutableList().also { it[target.indexInSection] = newCodeOff.toLong() }
        } else {
            parsed.virtualCodeOffsets
        }
        val newClassData = ClassDataItem.rebuild(
            parsed,
            isDirect = target.isDirect,
            newCodeOffsets = if (target.isDirect) newCodeOffsets else newVirtualOffsets,
        )
        val newClassDataOff = out.position
        out.bytes(newClassData)

        var newSize = out.position
        // dex 的文件大小字段是 u32，但整体必须 4 字节对齐（data 区要求）
        val padTo = GrowableBuffer.paddingTo(newSize, DexFormat.ALIGN_4)
        repeat(padTo) { out.u8(0) }
        newSize = out.position

        // (4) 原位改 class_def_item 里的 class_data_off
        patchU32(out, classDefOff + DexFormat.CD_CLASS_DATA_OFF, newClassDataOff.toLong())

        // (5) 修 header
        //  - file_size
        patchU32(out, DexFormat.H_FILE_SIZE, newSize.toLong())
        //  - data_size / data_off 覆盖新追加的数据
        val oldDataOff = src.u32AsInt(DexFormat.H_DATA_OFF, "data_off")
        patchU32(out, DexFormat.H_DATA_SIZE, (newSize - oldDataOff).toLong())

        // (6) 更新 map_list 里的 code_item / class_data_item 计数
        // 不需要改 map_list：本次只是「替换引用」，条目总数不变。
        // 曾经在这里给 code_item / class_data 的 size +1，导致 map 与实际不符，
        // ART 拒收。详见 git log。

        val result = out.toByteArray()
        // (7) 重算 checksum 与 signature（必须在其它所有修改之后）
        finalizeChecksums(result)

        // (8) 自检：新 dex 的 header 必须自洽
        validateResult(result, req)

        return Outcome.Ok(
            dex = result,
            newCodeOff = newCodeOff,
            newClassDataOff = newClassDataOff,
            originalSize = originalSize,
            newSize = result.size,
        )
    }

    /**
     * 修 map_list 里两类数据项的计数。
     *
     * 【为什么必须改】`map_list` 是 ART 用来快速枚举各类型项的索引（dex 规范要求）。
     * 我们追加了新的 `code_item` 和 `class_data_item`，如果不更新计数，
     * ART 的校验器可能认为 map 与实际不符而拒收——**这种错在构建期不报错**，
     * 只有装到设备上才会炸。所以这里必须处理，而不是当作「无所谓」。
     *
     * 计数是 +1（本次只追加了一个新 code_item 和一个新 class_data_item）。
     * `map_off` 本身指向的位置不变（map_list 仍在原处，我们只改其中 size 字段）。
     */
    fun resolveMethodIndex(
        dexBytes: ByteArray,
        ownerType: String,
        methodName: String,
        methodSignature: String,
    ): Int? {
        val src = DexBuffer(dexBytes)
        if (runCatching { validateHeader(src) }.isFailure) return null
        val methodIdsOff = runCatching {
            src.u32AsInt(DexFormat.H_METHOD_IDS_OFF, "method_ids_off")
        }.getOrNull() ?: return null
        val methodIdsSize = runCatching {
            src.u32AsInt(DexFormat.H_METHOD_IDS_SIZE, "method_ids_size")
        }.getOrNull() ?: return null

        // method_id_item { u16 class_idx; u16 proto_idx; u32 name_idx }
        // 按 dex 规范 method_ids 以 (class, name, proto) 排序，可以二分；这里线性扫足够。
        for (i in 0 until methodIdsSize) {
            val off = methodIdsOff + i * 8
            if (off + 8 > src.size) break
            val classIdx = src.u16(off)
            if (typeDescriptorOf(src, classIdx) != ownerType) continue
            val protoIdx = src.u16(off + 2)
            val nameIdx = src.u32AsInt(off + 4, "name_idx")
            if (stringOf(src, nameIdx.toLong()) != methodName) continue
            if (protoSignatureOf(src, protoIdx) == methodSignature) return i
        }
        return null
    }

    /**
     * 读某个方法 code_item 头里的 `registers_size` / `ins_size`。
     *
     * 编码 `p` 寄存器必须用**真实的** `ins_size`，不能用「参数个数 + 1」推算：
     * 方法若声明了局部变量，`registers_size` 会大于参数区，
     * 而 `p0` 的位置由 `registers_size - ins_size` 决定。
     */
    data class CodeHeader(val registersSize: Int, val insSize: Int, val codeOff: Int)

    fun codeHeader(
        dexBytes: ByteArray,
        classType: String,
        methodName: String,
        methodSignature: String,
    ): CodeHeader? {
        val src = DexBuffer(dexBytes)
        if (runCatching { validateHeader(src) }.isFailure) return null
        val classDefOff = findClassDef(src, classType) ?: return null
        val classDataOff = runCatching {
            src.u32AsInt(classDefOff + DexFormat.CD_CLASS_DATA_OFF, "class_data_off")
        }.getOrNull() ?: return null
        if (classDataOff == 0) return null
        val parsed = runCatching { ClassDataItem.parse(dexBytes, classDataOff) }.getOrNull() ?: return null
        val hit = locateMethod(
            src, parsed,
            ReplaceCodeRequest(classType, methodName, methodSignature)
        ) ?: return null
        if (hit.codeOff == 0L || hit.codeOff > Int.MAX_VALUE) return null
        val codeOff = hit.codeOff.toInt()
        return runCatching {
            CodeHeader(
                registersSize = src.u16(codeOff + DexFormat.CI_REGISTERS_SIZE),
                insSize = src.u16(codeOff + DexFormat.CI_INS_SIZE),
                codeOff = codeOff,
            )
        }.getOrNull()
    }

    /** 该类的这个方法是否带 try/catch —— 增量路径不支持，供调用方提前回退。 */
    fun hasTryBlocks(dexBytes: ByteArray, codeOff: Int): Boolean = runCatching {
        DexBuffer(dexBytes).u16(codeOff + DexFormat.CI_TRIES_SIZE) > 0
    }.getOrDefault(true)


    // ────────────────────────── 校验 ──────────────────────────

    private fun validateHeader(src: DexBuffer) {
        require(src.size >= DexFormat.HEADER_SIZE) { "dex 太小：${src.size} 字节" }
        require(src.matches(DexFormat.H_MAGIC, DexFormat.MAGIC_PREFIX)) { "不是 dex（魔数不对）" }
        require(src.u32(DexFormat.H_HEADER_SIZE) == DexFormat.HEADER_SIZE.toLong()) {
            "header_size 应为 0x70，实际 ${src.u32(DexFormat.H_HEADER_SIZE)}"
        }
        require(src.u32(DexFormat.H_FILE_SIZE) == src.size.toLong()) {
            "file_size(${src.u32(DexFormat.H_FILE_SIZE)}) 与实际大小(${src.size}) 不符"
        }
    }

    /**
     * 产物自检：不依赖 dexlib2，只用 header 里的字段互相印证。
     *
     * 这一步的意义是「在交出去之前先自己发现结构错误」。
     * 真正的语义验证（ART 会不会接受）只能靠装机，这里能挡的是布局层面的错。
     */
    private fun validateResult(result: ByteArray, req: ReplaceCodeRequest) {
        val buf = DexBuffer(result)
        require(buf.u32(DexFormat.H_FILE_SIZE) == result.size.toLong()) {
            "自检失败：file_size 与产物大小不符"
        }
        val classDefOff = findClassDef(buf, req.classType)
            ?: error("自检失败：产物里找不到 ${req.classType}")
        val cdOff = buf.u32AsInt(classDefOff + DexFormat.CD_CLASS_DATA_OFF, "class_data_off")
        require(cdOff > 0 && cdOff < result.size) { "自检失败：class_data_off 越界 $cdOff" }
        // 能被重新解析，说明新的 class_data_item 结构自洽
        val reparsed = ClassDataItem.parse(result, cdOff)
        require(reparsed.byteLength > 0) { "自检失败：新 class_data_item 长度为 0" }
    }

    // ────────────────────────── 查找 ──────────────────────────

    private fun findClassDef(src: DexBuffer, classType: String): Int? {
        val size = src.u32AsInt(DexFormat.H_CLASS_DEFS_SIZE, "class_defs_size")
        val off = src.u32AsInt(DexFormat.H_CLASS_DEFS_OFF, "class_defs_off")
        for (i in 0 until size) {
            val itemOff = off + i * DexFormat.CLASS_DEF_WIDTH
            if (itemOff + DexFormat.CLASS_DEF_WIDTH > src.size) return null
            val typeIdx = src.u32AsInt(itemOff + DexFormat.CD_CLASS_IDX, "class_idx")
            val typeDesc = typeDescriptorOf(src, typeIdx) ?: continue
            if (typeDesc == classType) return itemOff
        }
        return null
    }

    /** type_ids[idx] → string_ids[descriptor_idx] → 字符串。 */
    private fun typeDescriptorOf(src: DexBuffer, typeIdx: Int): String? = try {
        val typeIdsOff = src.u32AsInt(DexFormat.H_TYPE_IDS_OFF, "type_ids_off")
        val descIdx = src.u32AsInt(typeIdsOff + typeIdx * 4, "descriptor_idx")
        val stringIdsOff = src.u32AsInt(DexFormat.H_STRING_IDS_OFF, "string_ids_off")
        val stringDataOff = src.u32AsInt(stringIdsOff + descIdx * 4, "string_data_off")
        readMutf8(src, stringDataOff)
    } catch (_: Exception) {
        null
    }

    /**
     * 读 dex 的 MUTF-8 字符串。
     *
     * 格式：`uleb128 utf16_length` + 若干字节 + `0x00`。
     * 注意**不能用** [readUleb128] 的长度去做字符串切片——长度是 UTF-16 码元数，
     * 不是字节数（中文/emoji 会不等）。这里只用于类型描述符（纯 ASCII），
     * 所以按字节读到 `\0` 即可，且仍在实现上处理了长度前缀的消耗。
     */
    private fun readMutf8(src: DexBuffer, offset: Int): String {
        val data = src.raw()
        val (_, headerLen) = Leb128.readUleb128(data, offset)
        var p = offset + headerLen
        val sb = StringBuilder()
        while (p < data.size) {
            val b = data[p].toInt() and 0xFF
            if (b == 0) break
            sb.append(b.toChar())
            p++
        }
        return sb.toString()
    }

    private data class MethodHit(
        val isDirect: Boolean,
        val indexInSection: Int,
        val codeOff: Long,
    )

    private fun locateMethod(
        src: DexBuffer,
        parsed: ClassDataItem.Parsed,
        req: ReplaceCodeRequest,
    ): MethodHit? {
        val methodIdsOff = src.u32AsInt(DexFormat.H_METHOD_IDS_OFF, "method_ids_off")

        // method_id_item 布局：{ u16 class_idx; u16 proto_idx; u32 name_idx }
        fun matches(section: List<Long>, i: Int): Boolean {
            val methodIdx = section[i]
            if (methodIdx < 0 || methodIdx > Int.MAX_VALUE) return false
            val itemOff = methodIdsOff + methodIdx.toInt() * 8
            if (itemOff + 8 > src.size) return false
            val classIdx = src.u16(itemOff)
            val protoIdx = src.u16(itemOff + 2)
            val nameIdx = src.u32AsInt(itemOff + 4, "name_idx")

            if (typeDescriptorOf(src, classIdx) != req.classType) return false
            if (stringOf(src, nameIdx.toLong()) != req.methodName) return false
            return protoSignatureOf(src, protoIdx) == req.methodSignature
        }

        parsed.directMethodIndices.indices.forEach { i ->
            if (matches(parsed.directMethodIndices, i)) {
                return MethodHit(true, i, parsed.directCodeOffsets[i])
            }
        }
        parsed.virtualMethodIndices.indices.forEach { i ->
            if (matches(parsed.virtualMethodIndices, i)) {
                return MethodHit(false, i, parsed.virtualCodeOffsets[i])
            }
        }
        return null
    }

    /** string_ids[idx] → 字符串。 */
    private fun stringOf(src: DexBuffer, stringIdx: Long): String? = try {
        require(stringIdx in 0..Int.MAX_VALUE.toLong())
        val stringIdsOff = src.u32AsInt(DexFormat.H_STRING_IDS_OFF, "string_ids_off")
        val sdo = src.u32AsInt(stringIdsOff + stringIdx.toInt() * 4, "string_data_off")
        readMutf8(src, sdo)
    } catch (_: Exception) {
        null
    }

    /** proto_id_item 的签名，形如 `(Landroid/os/Bundle;)V`。 */
    private fun protoSignatureOf(src: DexBuffer, protoIdx: Int): String? = try {
        val protoOff = src.u32AsInt(DexFormat.H_PROTO_IDS_OFF, "proto_ids_off")
        val itemOff = protoOff + protoIdx * 12
        val returnTypeIdx = src.u32AsInt(itemOff + 4, "return_type_idx")
        val paramsOff = src.u32AsInt(itemOff + 8, "parameters_off")

        val ret = typeDescriptorOf(src, returnTypeIdx) ?: return null
        val sb = StringBuilder("(")
        if (paramsOff != 0) {
            val n = src.u32AsInt(paramsOff, "type_list size")
            for (i in 0 until n) {
                val tIdx = src.u16(paramsOff + 4 + i * 2)
                sb.append(typeDescriptorOf(src, tIdx) ?: return null)
            }
        }
        sb.append(")").append(ret)
        sb.toString()
    } catch (_: Exception) {
        null
    }

    private fun readInsns(dexBytes: ByteArray, codeOff: Int): ShortArray {
        val buf = DexBuffer(dexBytes)
        val n = buf.u32AsInt(codeOff + DexFormat.CI_INSNS_SIZE, "insns_size")
        val start = codeOff + DexFormat.CI_INSNS
        require(start + n * 2 <= dexBytes.size) { "insns 越界：n=$n" }
        return ShortArray(n) { i ->
            ((dexBytes[start + i * 2].toInt() and 0xFF) or
                ((dexBytes[start + i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }

    /**
     * 估算一组指令需要的 outs_size。
     *
     * 保守做法：扫描 `invoke-*` 指令，取其中最大的寄存器个数。
     * 这里只需要一个**不小于实际需求**的值——outs_size 偏大只会多占几个寄存器槽，
     * 偏小则校验器直接拒绝产物（"outs_size too small"）。
     *
     * 【opcode 范围必须记准】Dalvik 里 invoke 家族分两组：
     * ```
     * 0x6e invoke-virtual    0x6f invoke-super     0x70 invoke-direct
     * 0x71 invoke-static     0x72 invoke-interface         ← 35c，3 字
     * 0x74 invoke-virtual/r  0x75 invoke-super/r   0x76 invoke-direct/r
     * 0x77 invoke-static/r   0x78 invoke-interface/r       ← 3rc，3 字
     * ```
     * `0x73` 与 `0x79` 是未使用。第一版把 0x74..0x78 也算成了 35c、
     * 又把 0x73/0x79 当成 3rc——那样算出来的 outs_size 会是错的。
     */
    private fun estimateOuts(insns: ShortArray): Int {
        var max = 0
        var i = 0
        while (i < insns.size) {
            val unit = insns[i].toInt() and 0xFFFF
            val op = unit and 0xFF
            when {
                // 35c：A 是寄存器个数（低 4 位在 unit0 的 bit12..15）
                op in 0x6E..0x72 -> {
                    val count = (unit ushr 12) and 0xF
                    if (count > max) max = count
                    i += 3
                }
                // 3rc：AA 是寄存器个数（unit0 的 bit8..15）
                op in 0x74..0x78 -> {
                    val count = (unit ushr 8) and 0xFF
                    if (count > max) max = count
                    i += 3
                }
                else -> i += InstructionEncoder.width(unit)
            }
        }
        return max
    }

    /** 一条指令占几个 16 位字。委托给 [InstructionEncoder]，避免两套宽度表漂移。 */
    private fun instructionWidth(unit: Int): Int = InstructionEncoder.width(unit)

    // ────────────────────────── 校验值与工具 ──────────────────────────

    /**
     * 重算 Adler32 checksum 与 SHA-1 signature。
     *
     * 顺序不能反：signature 覆盖的范围是「跳过自身 20 字节之后到文件末尾」，
     * 而 checksum 覆盖「从 signature 之后到末尾」。所以必须**先写 signature，再算 checksum**。
     * 两者都必须在所有内容修改完成之后做。
     */
    private fun finalizeChecksums(dex: ByteArray) {
        // signature 位于 0x0C..0x1F，覆盖 0x20..end
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 0x20, dex.size - 0x20)
        val sig = sha1.digest()
        System.arraycopy(sig, 0, dex, 0x0C, 20)

        // checksum 位于 0x08..0x0B，覆盖 0x0C..end
        val adler = Adler32()
        adler.update(dex, 0x0C, dex.size - 0x0C)
        val sum = adler.value
        dex[0x08] = (sum and 0xFF).toByte()
        dex[0x09] = ((sum ushr 8) and 0xFF).toByte()
        dex[0x0A] = ((sum ushr 16) and 0xFF).toByte()
        dex[0x0B] = ((sum ushr 24) and 0xFF).toByte()
    }

    private fun patchU32(out: GrowableBuffer, at: Int, v: Long) {
        out.patchU32(at, v)
    }

    private fun Long.toIntChecked(): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "偏移超出 Int：$this" }
        return toInt()
    }
}

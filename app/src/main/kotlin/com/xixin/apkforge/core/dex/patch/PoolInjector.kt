package com.xixin.apkforge.core.dex.patch

/**
 * **池注入器**：往 dex 里新增池条目（字符串/类型/原型/方法），只重建索引数组。
 *
 * ## 为什么这样能快
 *
 * `DexPool` 慢在「重新编码一切」：8.5 万个字符串要做 MUTF-8 编码、
 * 6.1 万个方法要重新序列化、每个类都要重建对象图。实测 28.5MB 的 dex 要 8.8 秒。
 *
 * 但实际需要新增的只有 3~5 个条目。所以这里换一个做法：
 *
 * ```
 * 数据段（strings 内容 / code / class_data / annotations…）→ 一个字节都不动
 * 索引段（string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs）
 *                                                          → 重建，追加到文件末尾
 * ```
 *
 * **关键点：数据段不动，所以所有绝对偏移都保持有效。**
 * 只有「索引 → 索引」的引用需要重映射，那些都是 2/4 字节整数，
 * 改写整个数组是 memcpy 级的速度。
 *
 * ## 索引段为什么可以搬到文件末尾
 *
 * dex 的 header 里每一段都有独立的 `offset` 字段，段与段之间不要求相邻。
 * 所以新的索引数组可以放在文件末尾，只需改 header 里的 6 个 offset。
 *
 * ## 必须保持的排序约束（ART 强制校验）
 *
 * | 段 | 排序键 |
 * |---|---|
 * | `string_ids` | 字符串内容（UTF-16 码元序） |
 * | `type_ids` | `descriptor_idx` |
 * | `proto_ids` | `return_type_idx`, 参数列表 |
 * | `field_ids` | `class_idx`, `name_idx`, `type_idx` |
 * | `method_ids` | `class_idx`, `name_idx`, `proto_idx` |
 * | `class_defs` | `class_idx` |
 *
 * 乱序会被 ART 拒收：`Failed to verify dex file: Out-of-order string_ids`。
 * 所以插入新条目后必须重新排序，并同步重映射所有引用它们的地方。
 */
internal object PoolInjector {

    /** 一次需要新增的方法引用。 */
    data class MethodRefRequest(
        val owner: String,          // Lcom/foo/Bar;
        val name: String,           // inject
        val paramTypes: List<String>, // [Landroid/app/Activity;]
        val returnType: String,     // V
    )

    sealed interface Result {
        data class Ok(
            val dex: ByteArray,
            val methodIdx: Int,
            val newStringCount: Int,
            val newTypeCount: Int,
            val note: String,
        ) : Result {
            override fun toString(): String =
                "Ok(dex=${dex.size}B, methodIdx=$methodIdx, " +
                    "+$newStringCount strings, +$newTypeCount types)"
        }

        data class Failed(val reason: String) : Result
    }

    /**
     * 往 [dexBytes] 里加入 [ref] 所需的全部池条目，返回新 dex 与该方法的下标。
     */
    fun addMethodRef(dexBytes: ByteArray, ref: MethodRefRequest): Result {
        val src = DexBuffer(dexBytes)
        runCatching { validate(src) }.getOrElse {
            return Result.Failed(it.message ?: "dex 头部校验失败")
        }

        return try {
            // ── 1. 读出现有各段 ──
            val sec = Sections.read(src)

            // ── 2. 现有字符串（解码后，用于排序与查重） ──
            val oldStrings = sec.stringOffsets.map { off ->
                Mutf8Codec.readOne(dexBytes, off).first
            }

            // ── 3. 需要新增的字符串 ──
            val shorty = buildShorty(ref.returnType, ref.paramTypes)
            val wanted = LinkedHashSet<String>()
            wanted += ref.owner
            wanted += ref.name
            wanted += shorty
            wanted += ref.returnType
            wanted += ref.paramTypes
            val newStrings = wanted.filter { it !in oldStrings }.distinct()
            // 过滤掉空串（正常不会出现，防御性）
            val newStringsToAdd = newStrings.filter { it.isNotEmpty() }

            // ── 4. 合并并排序字符串，建立「旧下标 → 新下标」映射 ──
            val stringIndexMap = IntArray(oldStrings.size)
            val mergedStrings = ArrayList<String>(oldStrings.size + newStringsToAdd.size)
            run {
                // 用「旧序列 + 新项」做归并，避免整体重排（旧序列已排序）
                val adds = newStringsToAdd.sorted()
                var i = 0
                var j = 0
                var newIdx = 0
                while (i < oldStrings.size || j < adds.size) {
                    val takeNew = when {
                        i >= oldStrings.size -> true
                        j >= adds.size -> false
                        else -> adds[j] < oldStrings[i]
                    }
                    if (takeNew) {
                        mergedStrings += adds[j++]
                    } else {
                        stringIndexMap[i] = newIdx
                        mergedStrings += oldStrings[i]
                        i++
                    }
                    newIdx++
                }
            }
            // 建立「字符串 → 新下标」查找表（一次遍历，O(n)）
            val lookup = HashMap<String, Int>(mergedStrings.size * 2)
            mergedStrings.forEachIndexed { idx, s ->
                if (!lookup.containsKey(s)) lookup[s] = idx
            }

            fun strIdx(s: String): Int = lookup[s]
                ?: error("字符串 $s 不在合并后的池里")

            // ── 5. 类型：需要新增 owner 与可能的参数/返回类型 ──
            val oldTypeStrings = sec.typeDescriptorIndices.map { it }   // 旧 type[i] → 旧 string_idx
            val typeWanted = (listOf(ref.owner) + ref.paramTypes + ref.returnType).distinct()
            val oldTypeLookup = HashMap<String, Int>()   // 字符串 → 旧 type 下标
            oldTypeStrings.forEachIndexed { i, sIdx ->
                oldTypeLookup.putIfAbsent(oldStrings[sIdx], i)
            }
            val newTypes = typeWanted.filter { it !in oldTypeLookup && it != "V" }
            // "V" 通常已存在；不存在也允许新增
            val newTypesFinal = if ("V" !in oldTypeLookup && "V" in typeWanted) newTypes + "V" else newTypes

            // 合并类型：**整体重排**（不能用归并——旧 type 的 descriptor_idx
            // 经 stringIndexMap 重映射后可能不再有序，因为新字符串插在了中间）
            val oldTypeNewStringIdx = oldTypeStrings.map { stringIndexMap[it] }
            val newTypeNewStringIdx = newTypesFinal.map { strIdx(it) }
            val typeIndexMap = IntArray(oldTypeStrings.size)
            val mergedTypeStringIdx = ArrayList<Int>(oldTypeStrings.size + newTypeNewStringIdx.size)
            run {
                // 先排旧条目
                val oldOrder = oldTypeNewStringIdx.withIndex()
                    .sortedWith { a, b -> a.value - b.value }
                oldOrder.forEachIndexed { newPos, old -> typeIndexMap[old.index] = newPos }
                oldOrder.forEach { mergedTypeStringIdx += it.value }
                // 再插入新条目到正确位置（同 string_idx 视为已存在）
                newTypeNewStringIdx.distinct().sorted().forEach { nsi ->
                    if (nsi in mergedTypeStringIdx) return@forEach
                    val pos = mergedTypeStringIdx.indexOfFirst { it > nsi }
                    val insertAt = if (pos < 0) mergedTypeStringIdx.size else pos
                    mergedTypeStringIdx.add(insertAt, nsi)
                    for (i in typeIndexMap.indices) {
                        if (typeIndexMap[i] >= insertAt) typeIndexMap[i]++
                    }
                }
            }
            val typeLookup = HashMap<String, Int>()
            mergedTypeStringIdx.forEachIndexed { idx, sIdx ->
                typeLookup.putIfAbsent(mergedStrings[sIdx], idx)
            }
            fun typeIdx(t: String): Int = typeLookup[t]
                ?: error("类型 $t 不在合并后的类型池里")

            // ── 6. 原型（proto）：需要新增 (shorty, return, params) ──
            // 【为什么整体重排而不是归并】旧 proto 的 returnTypeIdx / paramTypeIdx
            // 经过 typeIndexMap 重映射后**可能不再有序**（新类型插在中间会改变下标），
            // 所以不能假设「旧序列仍然有序然后往里插」。直接全部重排最稳。
            val newReturnTypeIdx = typeIdx(ref.returnType)
            val newParamTypeIdx = ref.paramTypes.map { typeIdx(it) }
            val protoExists = sec.protos.any { p ->
                val rp = p.remap(typeIndexMap)
                rp.returnTypeIdx == newReturnTypeIdx && rp.paramTypeIdx == newParamTypeIdx
            }
            val newProtoEntry = ProtoEntry(
                shortyString = strIdx(shorty),
                returnTypeIdx = newReturnTypeIdx,
                paramTypeIdx = newParamTypeIdx,
            )            // 旧条目：先重映射、再排序
            val remappedProtos = sec.protos.map { it.remap(typeIndexMap) }
            val protoIndexMap = IntArray(sec.protos.size)
            // 用「排序后的位置」反查
            val protoOrder = remappedProtos.withIndex()
                .sortedWith { a, b -> a.value.compareTo(b.value) }
            protoOrder.forEachIndexed { newPos, old -> protoIndexMap[old.index] = newPos }
            val mergedProtos = ArrayList<ProtoEntry>(sec.protos.size + 1)
            protoOrder.forEach { mergedProtos += it.value }
            val newProtoIdx: Int
            if (protoExists) {
                newProtoIdx = mergedProtos.indexOfFirst {
                    it.returnTypeIdx == newReturnTypeIdx && it.paramTypeIdx == newParamTypeIdx
                }
            } else {
                // 插入到正确排序位置
                val pos = mergedProtos.indexOfFirst { it.compareTo(newProtoEntry) > 0 }
                newProtoIdx = if (pos < 0) mergedProtos.size else pos
                mergedProtos.add(newProtoIdx, newProtoEntry)
                // 新 proto 插在中间时，后面所有 proto 下标 +1
                for (i in protoIndexMap.indices) {
                    if (protoIndexMap[i] >= newProtoIdx) protoIndexMap[i]++
                }
            }
            if (newProtoIdx < 0) return Result.Failed("无法定位新 proto 的下标")

            // ── 7. 字段：只需重映射（不新增） ──
            // 注意：class_idx 经 typeIndexMap 重映射后**可能不再有序**
            // （新类型插入到中间会改变后续 type 下标），所以必须重排并记录映射。
            val remappedFields = sec.fields.map { it.remap(typeIndexMap, stringIndexMap) }
            val fieldOrder = remappedFields.withIndex()
                .sortedWith { a, b -> a.value.compareTo(b.value) }
            val fieldIndexMap = IntArray(sec.fields.size)
            fieldOrder.forEachIndexed { newPos, old -> fieldIndexMap[old.index] = newPos }

            // ── 8. 方法：新增一条 + 重映射 ──
            val newMethodEntry = MethodEntry(
                classIdx = typeIdx(ref.owner),
                protoIdx = newProtoIdx,
                nameString = strIdx(ref.name),
            )
            val remappedMethods = sec.methods.map {
                it.remap(typeIndexMap, protoIndexMap, stringIndexMap)
            }
            // 把「新条目」追加在末尾，index = sec.methods.size 作为标记
            val allMethodEntries = remappedMethods + newMethodEntry
            val methodOrder = allMethodEntries.withIndex()
                .sortedWith { a, b -> a.value.compareTo(b.value) }
            val methodIndexMap = IntArray(sec.methods.size)
            var newMethodIdx = -1
            methodOrder.forEachIndexed { newPos, old ->
                if (old.index < sec.methods.size) {
                    methodIndexMap[old.index] = newPos
                } else {
                    newMethodIdx = newPos
                }
            }
            if (newMethodIdx < 0) return Result.Failed("无法定位新 method_idx")
            // DIAG
            run {
                val target = allMethodEntries[sec.methods.size]
                val actual = methodOrder[methodIndexMap.getOrElse(0) { 0 }].value
                System.err.println("DIAG total=${allMethodEntries.size} newMethodIdx=$newMethodIdx")
                System.err.println("DIAG target(class=${target.classIdx},name=${target.nameString},proto=${target.protoIdx})")
                System.err.println("DIAG at189=${methodOrder.getOrNull(189)?.value}")
            }

            // ── 9. 类定义：重映射所有索引 ──
            // class_data_item 会在下面统一重写（见 10b），所以这里只需改索引。
            val mergedClasses = sec.classDefs.map { cd ->
                cd.remap(typeIndexMap, stringIndexMap)
            }
            val classOrder = mergedClasses.withIndex().sortedWith { a, b ->
                a.value.classIdx.compareTo(b.value.classIdx)
            }

            // ── 10. 拼装新文件 ──
            val out = GrowableBuffer(dexBytes.size + 64 * 1024 + newStringsToAdd.size * 64)
            out.bytes(dexBytes)          // 原文件原样拷贝（数据段一个字节不动）

            // 追加新字符串数据
            val newStringDataOffsets = HashMap<String, Int>()
            newStringsToAdd.forEach { s ->
                out.align(1)
                newStringDataOffsets[s] = out.position
                out.bytes(Mutf8Codec.encodeItem(s))
            }

            // ── 10b. 重写所有 class_data_item ──
            // 池里插了新条目，后面所有下标都偏移了，而 class_data_item 存的是下标。
            // 且它们是变长差分编码，只能整体重编码后追加到文件末尾。
            val remapMaps = ClassDataRemapper.Maps(
                fieldMap = fieldIndexMap,
                methodMap = methodIndexMap,
            )
            val newClassDataOffsets = HashMap<Int, Int>()   // 旧偏移 → 新偏移
            sec.classDefs.forEach { cd ->
                val old = cd.classDataOff
                if (old == 0L) return@forEach
                val oldOff = old.toInt()
                val cached = newClassDataOffsets[oldOff]
                if (cached != null) return@forEach
                val rewritten = ClassDataRemapper.remap(dexBytes, oldOff, remapMaps)
                out.align(4)
                newClassDataOffsets[oldOff] = out.position
                out.bytes(rewritten)
            }

            // ── 10c. 重写所有 type_list ──
            // proto_ids[i].parameters_off 与 class_def.interfaces_off 都指向 type_list，
            // 而 type_list 里存的是 type_idx（u16）—— type_ids 重排后它们全部失效。
            // 不修这一处，读出来的方法签名会完全不对，表现为「找不到目标方法」。
            val typeListOffsets = LinkedHashSet<Int>()
            sec.protos.forEach { p ->
                if (p.parametersOff > 0 && p.parametersOff < Int.MAX_VALUE) {
                    typeListOffsets += p.parametersOff.toInt()
                }
            }
            sec.classDefs.forEach { c ->
                if (c.interfacesOff > 0 && c.interfacesOff < Int.MAX_VALUE) {
                    typeListOffsets += c.interfacesOff.toInt()
                }
            }
            val newTypeListOffsets = TypeListRemapper.remapAll(
                dexBytes, typeIndexMap, typeListOffsets
            ) { bytes ->
                out.align(4)
                val pos = out.position
                out.bytes(bytes)
                pos
            }

            // 新 proto 的参数列表：旧 dex 里没有对应的 type_list，必须新建一个
            // （参数用的是**新** type_idx）。空参数时 parametersOff 保持 0。
            val newProtoParamsOff: Long = if (newProtoEntry.paramTypeIdx.isEmpty()) {
                0L
            } else {
                val tl = GrowableBuffer(4 + newProtoEntry.paramTypeIdx.size * 2)
                tl.u32(newProtoEntry.paramTypeIdx.size.toLong())
                newProtoEntry.paramTypeIdx.forEach { tl.u16(it) }
                out.align(4)
                val pos = out.position
                out.bytes(tl.toByteArray())
                pos.toLong()
            }

            // 新的 string_ids 数组
            out.align(4)
            val stringIdsOff = out.position
            // ⚠️ 这里必须用 HashMap 查偏移，不能 indexOf——
            // 8.5 万个字符串 × 线性查找 = 72 亿次比较，实测要 14 秒（比全量还慢）。
            val oldStringOffByValue = HashMap<String, Int>(oldStrings.size * 2)
            oldStrings.forEachIndexed { i, s -> oldStringOffByValue.putIfAbsent(s, sec.stringOffsets[i]) }
            mergedStrings.forEach { s ->
                val off = newStringDataOffsets[s] ?: oldStringOffByValue[s]
                    ?: error("字符串 '$s' 找不到数据偏移")
                out.u32(off.toLong())
            }

            // 新的 type_ids
            out.align(4)
            val typeIdsOff = out.position
            mergedTypeStringIdx.forEach { out.u32(it.toLong()) }

            // 新的 proto_ids
            out.align(4)
            val protoIdsOff = out.position
            mergedProtos.forEach { p ->
                out.u32(p.shortyString.toLong())
                out.u32(p.returnTypeIdx.toLong())
                // parameters_off：新 proto 用自己的新 type_list，旧 proto 用重映射后的
                val newParams: Long = if (p === newProtoEntry) {
                    newProtoParamsOff
                } else if (p.parametersOff > 0) {
                    (newTypeListOffsets[p.parametersOff.toInt()] ?: p.parametersOff.toInt()).toLong()
                } else {
                    0L
                }
                out.u32(newParams)
            }

            // 新的 field_ids
            out.align(4)
            val fieldIdsOff = out.position
            fieldOrder.forEach { e ->
                out.u16(e.value.classIdx)
                out.u16(e.value.typeIdx)
                out.u32(e.value.nameIdx.toLong())
            }

            // 新的 method_ids
            out.align(4)
            val methodIdsOff = out.position
            methodOrder.forEach { e ->
                out.u16(e.value.classIdx)
                out.u16(e.value.protoIdx)
                out.u32(e.value.nameString.toLong())
            }

            // 新的 class_defs
            out.align(4)
            val classDefsOff = out.position
            classOrder.forEach { e ->
                val c = e.value
                out.u32(c.classIdx.toLong())
                out.u32(c.accessFlags.toLong())
                // -1 表示 NO_INDEX，写回 0xFFFFFFFF
                out.u32(if (c.superIdx < 0) DexFormat.NO_INDEX else c.superIdx.toLong())
                // interfaces_off 也指向 type_list，同样要用新偏移
                val newIfaces = if (c.interfacesOff > 0) {
                    (newTypeListOffsets[c.interfacesOff.toInt()] ?: c.interfacesOff.toInt()).toLong()
                } else 0L
                out.u32(newIfaces)
                out.u32(if (c.sourceFileIdx < 0) DexFormat.NO_INDEX else c.sourceFileIdx.toLong())
                out.u32(c.annotationsOff)
                // class_data_off 指向重写后的新位置
                val newCdo = newClassDataOffsets[c.classDataOff.toInt()]
                    ?: run {
                        if (c.classDataOff != 0L) {
                            System.err.println("MISS classData ${c.classDataOff} keys=${newClassDataOffsets.size}")
                        }
                        c.classDataOff.toInt()
                    }
                out.u32(newCdo.toLong())
                out.u32(c.staticValuesOff)
            }

            // ── 11. 重建 map_list ──
            // map_list 必须按 type 升序排列，且每项记录「该类有几项、从哪开始」。
            // 池注入把 6 个索引段搬到了文件末尾、条目数也变了，
            // 所以整张表都要重写——不是只改几个 size 字段。
            val mapEntries = sortedMapOf<Int, Pair<Int, Int>>()   // type -> (size, offset)

            fun putMap(type: Int, size: Int, offset: Int) {
                if (size > 0) mapEntries[type] = size to offset
            }

            putMap(DexFormat.TYPE_HEADER_ITEM, 1, 0)
            putMap(DexFormat.TYPE_STRING_ID_ITEM, mergedStrings.size, stringIdsOff)
            putMap(DexFormat.TYPE_TYPE_ID_ITEM, mergedTypeStringIdx.size, typeIdsOff)
            putMap(DexFormat.TYPE_PROTO_ID_ITEM, mergedProtos.size, protoIdsOff)
            putMap(DexFormat.TYPE_FIELD_ID_ITEM, remappedFields.size, fieldIdsOff)
            putMap(DexFormat.TYPE_METHOD_ID_ITEM, allMethodEntries.size, methodIdsOff)
            putMap(DexFormat.TYPE_CLASS_DEF_ITEM, mergedClasses.size, classDefsOff)

            // 原 map 里的其它段（data 区里那些没动的：code / class_data / string_data…）
            // 偏移不变，但条目数要按本次新增量调整。这里从原 map 复制，再覆盖已知项。
            val oldMapOff = src.u32AsInt(DexFormat.H_MAP_OFF, "map_off")
            if (oldMapOff != 0) {
                val oldCount = src.u32AsInt(oldMapOff, "map size")
                for (i in 0 until oldCount) {
                    val itemOff = oldMapOff + 4 + i * DexFormat.MAP_ITEM_WIDTH
                    if (itemOff + DexFormat.MAP_ITEM_WIDTH > dexBytes.size) break
                    val type = src.u16(itemOff)
                    if (mapEntries.containsKey(type)) continue
                    val size = src.u32AsInt(itemOff + 4, "size")
                    val offset = src.u32AsInt(itemOff + 8, "offset")
                    // 这些段的 size/offset 因本次改动而变化的，逐个修正
                    when (type) {
                        // 新增了字符串数据项
                        DexFormat.TYPE_STRING_DATA_ITEM -> putMap(type, size + newStringsToAdd.size, offset)
                        // 新增了 type_list（新 proto 的参数表）
                        DexFormat.TYPE_TYPE_LIST ->
                            putMap(type, size + if (newProtoParamsOff != 0L) 1 else 0, offset)
                        // class_data 全部重写，数量不变但偏移变了（指向新位置）
                        DexFormat.TYPE_CLASS_DATA_ITEM -> {
                            val firstNew = newClassDataOffsets.values.minOrNull() ?: offset
                            putMap(type, size, firstNew)
                        }
                        else -> putMap(type, size, offset)
                    }
                }
            }

            // map_list 自身也要在表里，偏移就是它即将写入的位置
            out.align(4)
            val mapListOff = out.position
            putMap(DexFormat.TYPE_MAP_LIST, 1, mapListOff)

            // 写出 map_list（按 type 升序）
            val sortedEntries = mapEntries.toSortedMap()
            out.u32(sortedEntries.size.toLong())
            sortedEntries.forEach { (type, v) ->
                out.u16(type)
                out.u16(0)
                out.u32(v.first.toLong())
                out.u32(v.second.toLong())
            }

            val finalSize = out.position
            out.patchU32(DexFormat.H_MAP_OFF, mapListOff.toLong())

            // ── 12. 改 header ──
            out.patchU32(DexFormat.H_FILE_SIZE, finalSize.toLong())
            out.patchU32(DexFormat.H_STRING_IDS_SIZE, mergedStrings.size.toLong())
            out.patchU32(DexFormat.H_STRING_IDS_OFF, stringIdsOff.toLong())
            out.patchU32(DexFormat.H_TYPE_IDS_SIZE, mergedTypeStringIdx.size.toLong())
            out.patchU32(DexFormat.H_TYPE_IDS_OFF, typeIdsOff.toLong())
            out.patchU32(DexFormat.H_PROTO_IDS_SIZE, mergedProtos.size.toLong())
            out.patchU32(DexFormat.H_PROTO_IDS_OFF, protoIdsOff.toLong())
            out.patchU32(DexFormat.H_FIELD_IDS_SIZE, remappedFields.size.toLong())
            out.patchU32(DexFormat.H_FIELD_IDS_OFF, fieldIdsOff.toLong())
            out.patchU32(DexFormat.H_METHOD_IDS_SIZE, allMethodEntries.size.toLong())
            out.patchU32(DexFormat.H_METHOD_IDS_OFF, methodIdsOff.toLong())
            out.patchU32(DexFormat.H_CLASS_DEFS_SIZE, mergedClasses.size.toLong())
            out.patchU32(DexFormat.H_CLASS_DEFS_OFF, classDefsOff.toLong())

            val result = out.toByteArray()
            fixChecksums(result)

            Result.Ok(
                dex = result,
                methodIdx = newMethodIdx,
                newStringCount = newStringsToAdd.size,
                newTypeCount = newTypesFinal.size,
                note = "池注入：+${newStringsToAdd.size} 字符串 +${newTypesFinal.size} 类型 " +
                    "${dexBytes.size} → ${result.size} 字节",
            )
        } catch (e: Exception) {
            Result.Failed("池注入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ────────────────────── 数据结构 ──────────────────────

    private data class ProtoEntry(
        val shortyString: Int,
        val returnTypeIdx: Int,
        val paramTypeIdx: List<Int>,
        /** type_list 在数据段的偏移，0 表示无参数 */
        val parametersOff: Long = 0,
    ) : Comparable<ProtoEntry> {
        fun remap(typeMap: IntArray) = copy(
            returnTypeIdx = typeMap[returnTypeIdx],
            paramTypeIdx = paramTypeIdx.map { typeMap[it] },
        )

        override fun compareTo(other: ProtoEntry): Int {
            if (returnTypeIdx != other.returnTypeIdx) return returnTypeIdx - other.returnTypeIdx
            val n = minOf(paramTypeIdx.size, other.paramTypeIdx.size)
            for (i in 0 until n) {
                if (paramTypeIdx[i] != other.paramTypeIdx[i]) {
                    return paramTypeIdx[i] - other.paramTypeIdx[i]
                }
            }
            return paramTypeIdx.size - other.paramTypeIdx.size
        }
    }

    private data class FieldEntry(
        val classIdx: Int,
        val typeIdx: Int,
        val nameIdx: Int,
    ) : Comparable<FieldEntry> {
        fun remap(typeMap: IntArray, stringMap: IntArray) = FieldEntry(
            classIdx = typeMap[classIdx],
            typeIdx = typeMap[typeIdx],
            nameIdx = stringMap[nameIdx],
        )

        override fun compareTo(other: FieldEntry): Int {
            if (classIdx != other.classIdx) return classIdx - other.classIdx
            if (nameIdx != other.nameIdx) return nameIdx - other.nameIdx
            return typeIdx - other.typeIdx
        }
    }

    private data class MethodEntry(
        val classIdx: Int,
        val protoIdx: Int,
        val nameString: Int,
    ) : Comparable<MethodEntry> {
        fun remap(typeMap: IntArray, protoMap: IntArray, stringMap: IntArray) = MethodEntry(
            classIdx = typeMap[classIdx],
            protoIdx = protoMap[protoIdx],
            nameString = stringMap[nameString],
        )

        override fun compareTo(other: MethodEntry): Int {
            if (classIdx != other.classIdx) return classIdx - other.classIdx
            if (nameString != other.nameString) return nameString - other.nameString
            return protoIdx - other.protoIdx
        }
    }

    private data class ClassDefEntry(
        val classIdx: Int,
        val accessFlags: Int,
        val superIdx: Int,
        val interfacesOff: Long,
        val sourceFileIdx: Int,
        val annotationsOff: Long,
        val classDataOff: Long,
        val staticValuesOff: Long,
    ) {
        fun remap(typeMap: IntArray, stringMap: IntArray) = copy(
            classIdx = typeMap[classIdx],
            // -1 表示 NO_INDEX，不能拿去索引数组
            superIdx = if (superIdx >= 0) typeMap[superIdx] else superIdx,
            sourceFileIdx = if (sourceFileIdx >= 0) stringMap[sourceFileIdx] else sourceFileIdx,
        )
    }

    private class Sections(
        val stringOffsets: List<Int>,
        val typeDescriptorIndices: List<Int>,
        val protos: List<ProtoEntry>,
        val fields: List<FieldEntry>,
        val methods: List<MethodEntry>,
        val classDefs: List<ClassDefEntry>,
    ) {
        companion object {
            fun read(src: DexBuffer): Sections {
                val stringSize = src.u32AsInt(DexFormat.H_STRING_IDS_SIZE, "strings")
                val stringOff = src.u32AsInt(DexFormat.H_STRING_IDS_OFF, "strings_off")
                val stringOffsets = (0 until stringSize).map {
                    src.u32AsInt(stringOff + it * 4, "string_data_off")
                }

                val typeSize = src.u32AsInt(DexFormat.H_TYPE_IDS_SIZE, "types")
                val typeOff = src.u32AsInt(DexFormat.H_TYPE_IDS_OFF, "types_off")
                val typeDesc = (0 until typeSize).map { src.u32AsInt(typeOff + it * 4, "desc_idx") }

                val protoSize = src.u32AsInt(DexFormat.H_PROTO_IDS_SIZE, "protos")
                val protoOff = src.u32AsInt(DexFormat.H_PROTO_IDS_OFF, "protos_off")
                val protos = (0 until protoSize).map { i ->
                    val o = protoOff + i * 12
                    val shorty = src.u32AsInt(o, "shorty")
                    val ret = src.u32AsInt(o + 4, "ret")
                    val paramsOff = src.u32(o + 8)
                    val params = if (paramsOff == 0L) emptyList() else {
                        val n = src.u32AsInt(paramsOff.toInt(), "params_n")
                        (0 until n).map { src.u16(paramsOff.toInt() + 4 + it * 2) }
                    }
                    ProtoEntry(shorty, ret, params, paramsOff)
                }

                val fieldSize = src.u32AsInt(DexFormat.H_FIELD_IDS_SIZE, "fields")
                val fieldOff = src.u32AsInt(DexFormat.H_FIELD_IDS_OFF, "fields_off")
                val fields = (0 until fieldSize).map { i ->
                    val o = fieldOff + i * 8
                    FieldEntry(src.u16(o), src.u16(o + 2), src.u32AsInt(o + 4, "fname"))
                }

                val methodSize = src.u32AsInt(DexFormat.H_METHOD_IDS_SIZE, "methods")
                val methodOff = src.u32AsInt(DexFormat.H_METHOD_IDS_OFF, "methods_off")
                val methods = (0 until methodSize).map { i ->
                    val o = methodOff + i * 8
                    MethodEntry(src.u16(o), src.u16(o + 2), src.u32AsInt(o + 4, "mname"))
                }

                val classSize = src.u32AsInt(DexFormat.H_CLASS_DEFS_SIZE, "classes")
                val classOff = src.u32AsInt(DexFormat.H_CLASS_DEFS_OFF, "classes_off")
                val classDefs = (0 until classSize).map { i ->
                    val o = classOff + i * 32
                    // ⚠️ source_file_idx / superclass_idx 可以是 NO_INDEX(0xFFFFFFFF)
                    // 表示「无」，必须按 Long 读再判断，不能直接 u32AsInt——那会抛越界。
                    val superRaw = src.u32(o + 8)
                    val sourceRaw = src.u32(o + 16)
                    ClassDefEntry(
                        classIdx = src.u32AsInt(o, "class_idx"),
                        accessFlags = src.u32AsInt(o + 4, "flags"),
                        superIdx = if (superRaw == DexFormat.NO_INDEX) -1 else superRaw.toInt(),
                        interfacesOff = src.u32(o + 12),
                        sourceFileIdx = if (sourceRaw == DexFormat.NO_INDEX) -1 else sourceRaw.toInt(),
                        annotationsOff = src.u32(o + 20),
                        classDataOff = src.u32(o + 24),
                        staticValuesOff = src.u32(o + 28),
                    )
                }

                return Sections(stringOffsets, typeDesc, protos, fields, methods, classDefs)
            }
        }
    }

    // ────────────────────── 工具 ──────────────────────

    private fun buildShorty(returnType: String, params: List<String>): String =
        buildString {
            append(shortyChar(returnType))
            params.forEach { append(shortyChar(it)) }
        }

    private fun shortyChar(type: String): Char = when (type) {
        "V" -> 'V'
        "Z" -> 'Z'
        "B" -> 'B'
        "S" -> 'S'
        "C" -> 'C'
        "I" -> 'I'
        "J" -> 'J'
        "F" -> 'F'
        "D" -> 'D'
        else -> 'L'
    }

    private fun validate(src: DexBuffer) {
        require(src.size >= DexFormat.HEADER_SIZE) { "dex 太小" }
        require(src.matches(DexFormat.H_MAGIC, DexFormat.MAGIC_PREFIX)) { "不是 dex" }
        require(src.u32(DexFormat.H_FILE_SIZE) == src.size.toLong()) { "file_size 不符" }
    }

    private fun fixChecksums(dex: ByteArray) {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 0x20, dex.size - 0x20)
        System.arraycopy(sha1.digest(), 0, dex, 0x0C, 20)
        val adler = java.util.zip.Adler32()
        adler.update(dex, 0x0C, dex.size - 0x0C)
        val sum = adler.value
        dex[0x08] = (sum and 0xFF).toByte()
        dex[0x09] = ((sum ushr 8) and 0xFF).toByte()
        dex[0x0A] = ((sum ushr 16) and 0xFF).toByte()
        dex[0x0B] = ((sum ushr 24) and 0xFF).toByte()
    }
}

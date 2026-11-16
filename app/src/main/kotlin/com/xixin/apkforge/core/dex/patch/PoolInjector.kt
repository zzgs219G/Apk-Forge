package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.dex.DexOpcodes

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
    /**
     * 要在重建 `code_item` 时**顺便**替换掉指令的方法。
     *
     * @param classType     目标类描述符，如 `Lcom/foo/Bar;`
     * @param methodName    方法名
     * @param methodSig     方法签名
     * @param newInsns      新的指令序列（16 位字）。传 null 表示不改指令。
     */
    data class InsnPatch(
        val classType: String,
        val methodName: String,
        val methodSig: String,
        /**
         * 要插到目标方法最前面的指令（16 位字）。
         *
         * 其中 **`[methodIdxWord]` 那个字留空**，由 PoolInjector 在算出
         * `newMethodIdx` 后填入 —— 这样「登记方法」和「插入调用」一次就能做完。
         *
         * 【为什么必须这样】如果拆成两次调用（先登记拿 method_idx、再插入），
         * 每次都会产出一份**完整的新 dex**。对 30MB 的 dex，
         * 两遍产物 + 原文件同时在内存里会直接 OOM
         * （实测 `Failed to allocate a 29989720 byte allocation`）。
         */
        val newInsns: ShortArray,
        /** `newInsns` 里哪个下标是 method_idx；-1 表示不含索引 */
        val methodIdxWord: Int = 1,
    )

    fun addMethodRef(
        dexBytes: ByteArray,
        ref: MethodRefRequest,
        insnPatch: InsnPatch? = null,
    ): Result {
        val src = DexBuffer(dexBytes)
        runCatching { validate(src) }.getOrElse {
            return Result.Failed(it.message ?: "dex 头部校验失败")
        }

        // 分段计时：注入慢必须能定位到具体步骤，否则只能靠猜。
        // core/ 层不允许用 android.util.Log，用 stderr（Android 会把它转进 logcat）。
        // 分段耗时。**不能用 System.err / println**：Android App 里它们默认被
        // 重定向到 /dev/null，不进 logcat，加了也看不到。
        // 所以攒起来拼进返回值的 note，由 UI 显示给用户。
        val t0 = System.nanoTime()
        val laps = ArrayList<Pair<String, Long>>(16)
        fun lap(name: String) {
            laps += name to (System.nanoTime() - t0) / 1_000_000
        }

        return try {
            // ── 0. 幂等：这个方法已经在池里就不重复添加 ──
            // 【为什么必须有】注入分两步：第一次登记方法（拿到 method_idx 才能编码指令），
            // 第二次带着指令落盘。第二次若再添加一遍，`method_ids` 会出现两条相同的
            // `(class, name, proto)`，破坏严格递增，ART 报 `Out-of-order method_ids`。
            // 这里记下「已存在」，后面合并池时复用它而不再追加。
            val existingMethodIdx = DexPatcher.resolveMethodIndex(
                dexBytes, ref.owner, ref.name,
                "(" + ref.paramTypes.joinToString("") + ")" + ref.returnType,
            )

            lap("1-read-sections")
            // ── 1. 读出现有各段 ──
            val sec = Sections.read(src)

            lap("2-read-strings")
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

            lap("5-types")
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
                val rp = p.remap(typeIndexMap, stringIndexMap)
                rp.returnTypeIdx == newReturnTypeIdx && rp.paramTypeIdx == newParamTypeIdx
            }
            val newProtoEntry = ProtoEntry(
                shortyString = strIdx(shorty),
                returnTypeIdx = newReturnTypeIdx,
                paramTypeIdx = newParamTypeIdx,
            )            // 旧条目：先重映射、再排序
            val remappedProtos = sec.protos.map { it.remap(typeIndexMap, stringIndexMap) }
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

            lap("7-fields")
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
            // ⚠️ 方法已在池里（第二次调用）时**不再追加**，只重映射。
            // 追加会让 method_ids 出现两条相同的 (class, name, proto)，
            // 破坏严格递增，ART 报 `Out-of-order method_ids`。
            val needNewMethod = (existingMethodIdx == null)
            val allMethodEntries =
                if (needNewMethod) remappedMethods + newMethodEntry else remappedMethods
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
            if (needNewMethod && newMethodIdx < 0) {
                return Result.Failed("无法定位新 method_idx")
            }
            if (!needNewMethod) {
                // 复用已有索引：它经重映射后的位置
                newMethodIdx = methodIndexMap[existingMethodIdx!!]
            }

            lap("9-classdefs")
            // ── 9. 类定义：重映射所有索引 ──
            // class_data_item 会在下面统一重写（见 10b），所以这里只需改索引。
            val mergedClasses = sec.classDefs.map { cd ->
                cd.remap(typeIndexMap, stringIndexMap)
            }
            // ⚠️ class_defs 的排序有**两条**约束，必须同时满足：
            //   1. class_idx 升序（ART 的 CheckIntraClassDefItem 逐项校验）
            //   2. 父类与接口必须先于实现类出现（CheckClassDefOrdering）
            //
            // 只按 class_idx 排会漏掉第 2 条：池重排后 class_idx 的**数值**变了，
            // 原本「父类在前」的巧合不再成立。实测 16722 个类里有 465 处违规，
            // ART 报 `Invalid class definition ordering: class with type idx: '85'
            // defined before implemented interface with type idx: '89'`。
            //
            // 做法：在 class_idx 升序的基础上做稳定拓扑排序 —— 按 class_idx 从小到大
            // 尝试放置，若其父类/接口尚未放置则先放它们。
            val classOrder = run {
                val byIdx = LinkedHashMap<Int, IndexedValue<ClassDefEntry>>()
                mergedClasses.withIndex().forEach { byIdx[it.value.classIdx] = it }
                val placed = LinkedHashSet<Int>()
                val ordered = ArrayList<IndexedValue<ClassDefEntry>>(mergedClasses.size)

                fun place(idx: Int, visiting: MutableSet<Int>) {
                    if (idx in placed || idx !in byIdx) return
                    if (!visiting.add(idx)) return          // 环（畸形 dex）：直接放
                    val e = byIdx.getValue(idx)
                    val c = e.value
                    if (c.superIdx >= 0) place(c.superIdx, visiting)
                    // 接口列表在 interfacesOff 指向的 type_list 里。
                    // ⚠️ 里面存的是**旧** type_idx，而 byIdx 的键是**新** class_idx ——
                    // 必须先经 typeIndexMap 换算，否则永远匹配不上，
                    // 拓扑排序等于没做（实测 465 处依赖序违规一个没修掉）。
                    if (c.interfacesOff > 0) {
                        runCatching {
                            val n = src.u32AsInt(c.interfacesOff.toInt(), "ifaces_n")
                            repeat(n) {
                                val oldType = src.u16(c.interfacesOff.toInt() + 4 + it * 2)
                                if (oldType < typeIndexMap.size) {
                                    place(typeIndexMap[oldType], visiting)
                                }
                            }
                        }
                    }
                    visiting.remove(idx)
                    if (placed.add(idx)) ordered += e
                }
                byIdx.keys.sorted().forEach { place(it, HashSet()) }
                // 兜底：万一有元素没被放到（理论上不会），按原顺序补上
                mergedClasses.withIndex().forEach { if (it.value.classIdx !in placed) ordered += it }
                ordered
            }

            // ── 10. 拼装新文件 ──
            //
            // ⚠️ 这里不再是「原文件副本 + 不断追加」，而是**每个段各自缓冲**，
            // 最后由 DexSectionLayout 按规范段序拼接。
            //
            // 旧写法在原文件副本后面追加：新字符串、新 code_item、新 class_data、
            // 新 id 表全塞到末尾。这对 ART 有两条硬伤：
            //
            //   1. 搬走的内容原位置留下旧字节 → `Non-zero padding 5e at offset 0x70`
            //   2. 同一 type 被拆成「原位置一段 + 末尾一段」→ `List too large for insns size`
            //
            // map 只记录「起点 + 个数」，无法表达「同 type 分散在多处」，
            // 所以同 type 的 item 必须连续，段与段之间只能有全零填充。
            //
            // 每个段缓冲里记录的偏移是**段内相对偏移**，排版时统一回填成绝对偏移。

            // 新的 string_data（原样 + 新增）
            val stringDataBuf = GrowableBuffer(dexBytes.size / 2)
            // 新的 type_list（重写后的）
            val typeListBuf = GrowableBuffer(4096)
            // 注解相关的四个独立段
            val annotationItemBuf = GrowableBuffer(8192)
            val annotationSetBuf = GrowableBuffer(4096)
            val annotationSetRefBuf = GrowableBuffer(1024)
            val annotationDirBuf = GrowableBuffer(4096)
            // 重写后的 debug_info / code_item / class_data
            val debugInfoBuf = GrowableBuffer(16384)
            val codeItemBuf = GrowableBuffer(dexBytes.size / 2)
            val classDataBuf = GrowableBuffer(dexBytes.size / 8)
            // 静态初值 encoded_array
            val encodedArrayBuf = GrowableBuffer(2048)

            // ── 10a. string_data：原样复制所有旧项（偏移会变，稍后回填）──
            // 旧 string_data 项是变长 MUTF-8（uleb128 长度 + 字节 + 结尾 0），
            // 按 1 字节对齐，所以段内位置必须逐项累加。
            // 这里不做解码→再编码的往返，直接搬原始字节区间，避免任何编码差异。
            val oldStringDataOffsets = HashMap<Int, Int>()   // 旧偏移 → 段内偏移
            sec.stringOffsets.forEach { oldOff ->
                if (oldStringDataOffsets.containsKey(oldOff)) return@forEach
                val (_, consumed) = Mutf8Codec.readOne(dexBytes, oldOff)
                oldStringDataOffsets[oldOff] = stringDataBuf.position
                stringDataBuf.bytes(dexBytes.copyOfRange(oldOff, oldOff + consumed))
            }

            // 追加新字符串数据
            val newStringDataOffsets = HashMap<String, Int>()
            newStringsToAdd.forEach { s ->
                newStringDataOffsets[s] = stringDataBuf.position
                stringDataBuf.bytes(Mutf8Codec.encodeItem(s))
            }

            // 旧 code_off → 新 code_off（段内偏移）
            val movedCodeItems = HashMap<Int, Int>()

            // ── 10b-3. 重写 debug_info（必须先于 code_item，因为要回填 debug_info_off）──
            // debug_info 里有局部变量名（string_idx）与类型（type_idx），池重排后错位。
            // 它只影响崩溃栈的行号与变量名，但会让严格解析器（jadx）整段失败，
            // 也会让真机上的堆栈信息变成乱码。
            val evMaps = EncodedValueRemapper.Maps(
                stringMap = stringIndexMap,
                typeMap = typeIndexMap,
                fieldMap = fieldIndexMap,
                methodMap = methodIndexMap,
                protoMap = protoIndexMap,
            )
            val debugInfoCache = HashMap<Int, Int>()   // 旧偏移 → 段内偏移
            // 收集所有 code_item 的旧偏移（debug_info 与 code_item 都要重建）
            val codeItemsToBuild = LinkedHashSet<Int>()
            sec.classDefs.forEach { cd ->
                if (cd.classDataOff == 0L) return@forEach
                val parsed = runCatching {
                    ClassDataItem.parse(dexBytes, cd.classDataOff.toInt())
                }.getOrNull() ?: return@forEach
                parsed.directCodeOffsets.forEach { if (it > 0) codeItemsToBuild += it.toInt() }
                parsed.virtualCodeOffsets.forEach { if (it > 0) codeItemsToBuild += it.toInt() }
            }
            codeItemsToBuild.forEach { co ->
                if (co + DexFormat.CI_INSNS > dexBytes.size) return@forEach
                val dbgOff = src.u32AsInt(co + DexFormat.CI_DEBUG_INFO_OFF, "dbg")
                if (dbgOff == 0) return@forEach
                if (debugInfoCache.containsKey(dbgOff)) return@forEach
                val bytes = DebugInfoRemapper.remap(dexBytes, dbgOff, evMaps)
                if (bytes.isEmpty()) {
                    return Result.Failed("debug_info@$dbgOff 重写后为空，无法写入")
                }
                // ⚠️ debug_info_item 是 **1 字节对齐**（见 ART 的
                // CheckIntraSectionIterate / CheckMap 里 alignment 判定）。
                // 这里千万不能 align(4)：那会在项之间插入填充字节，
                // 而 ART 是按「起点 + 项数」**紧密**遍历整段的，
                // 填充会让后续每一项的起点都偏移，报
                // `Bad index for debug_info_item parameter_name: 3486 >= 735`
                // —— 那两个数字跟真实数据毫无关系，极难反推。
                debugInfoCache[dbgOff] = debugInfoBuf.position
                debugInfoBuf.bytes(bytes)
            }

            // 自检：每个写入的 debug_info 都必须真的被某个 code_item 引用到。
            // 写入时是按 codeItemsToBuild 收集的，理论上必然被引用；
            // 但若有 code_item 因越界被跳过，就会留下孤儿项 ——
            // ART 顺序遍历整段时会整体错位，
            // 报 `Bad index for debug_info_item parameter_name: 3486 >= 735`。
            run {
                val actuallyRef = HashSet<Int>(debugInfoCache.size * 2)
                val validCo = codeItemsToBuild.filter {
                    it + DexFormat.CI_INSNS <= dexBytes.size
                }
                validCo.forEach { co ->
                    val d = src.u32AsInt(co + DexFormat.CI_DEBUG_INFO_OFF, "dbg")
                    if (d != 0) actuallyRef += d
                }
                val orphanKeys = debugInfoCache.keys.filter { it !in actuallyRef }
                if (orphanKeys.isNotEmpty()) {
                    return Result.Failed(
                        "debug_info 里有 ${orphanKeys.size} 项对应的 code_item 已被跳过" +
                            "（如 ${orphanKeys.take(3)}），会成为孤儿项"
                    )
                }
            }

            lap("10b-3a-locate")
            // ── 10b-3a. 定位「要替换指令」的那个 code_item ──
            // 注入要做两件事：把调用方法登记进池（上面已完成），
            // 以及把 invoke 指令插进目标方法的**第一条指令之前**。
            // 后者必须在重建 code_item 时顺路做：反正每个 code_item 都要重写一遍
            // （debug_info_off 变了），单独再走一遍只会多一次段移动、多一处出错机会。
            val patchCodeOff: Int = if (insnPatch != null) {
                // 复用 DexPatcher 已有的「按类名 + 方法名 + 签名定位 code_off」逻辑，
                // 不在这里再实现一遍（重复实现必然两处不一致）。
                val header = DexPatcher.codeHeader(
                    dexBytes, insnPatch.classType, insnPatch.methodName, insnPatch.methodSig,
                ) ?: return Result.Failed(
                    "${insnPatch.classType} 里没有带方法体的 " +
                        "${insnPatch.methodName}${insnPatch.methodSig}"
                )
                if (DexPatcher.hasTryBlocks(dexBytes, header.codeOff)) {
                    // 带 try 的方法要重算所有 try 项的指令偏移，本路径不做
                    return Result.Failed(
                        "${insnPatch.methodName} 带 try/catch 块，当前注入路径不支持；" +
                            "请改用全量重写"
                    )
                }
                header.codeOff
            } else -1

            lap("10b-3b-prepare")
            // ── 10b-3b. 重建**所有** code_item ──
            //
            // ⚠️ 这里从「只重建带 try 的」改成「全部重建」。原因：
            // 段重排后 code_item 的物理位置整体变了，所有 code_off 都必须重新计算；
            // 而且 code_item 内部有 debug_info_off 要改，原地补丁也不再可行。
            //
            // 段序照抄 dexlib2 的 DexWriter.writeTo()——它写出的文件必然通过 ART 校验。
            // 这个顺序既决定物理布局，也决定 map 的排列，两处必须完全一致。
            val sectionOrder = listOf(
                DexFormat.TYPE_STRING_ID_ITEM,
                DexFormat.TYPE_TYPE_ID_ITEM,
                DexFormat.TYPE_PROTO_ID_ITEM,
                DexFormat.TYPE_FIELD_ID_ITEM,
                DexFormat.TYPE_METHOD_ID_ITEM,
                DexFormat.TYPE_CLASS_DEF_ITEM,
                DexFormat.TYPE_STRING_DATA_ITEM,
                DexFormat.TYPE_TYPE_LIST,
                DexFormat.TYPE_ENCODED_ARRAY_ITEM,
                DexFormat.TYPE_ANNOTATION_ITEM,
                DexFormat.TYPE_ANNOTATION_SET_ITEM,
                DexFormat.TYPE_ANNOTATION_SET_REF_LIST,
                DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM,
                DexFormat.TYPE_DEBUG_INFO_ITEM,
                DexFormat.TYPE_CODE_ITEM,
                DexFormat.TYPE_CLASS_DATA_ITEM,
            )
            //
            // 重建时做三件事：
            //   1. debug_info_off → 新位置
            //   2. 指令里的池索引 → 新下标（后面统一回填）
            //   3. 带 try 的：try_item 的 exception_handler.type_idx 也是池下标，
            //      而 type_idx 是 uleb128，改后长度可能变、handler_off 会失效，
            //      所以整个 tries+handlers 必须重编码
            //
            // 除这三处外，指令字节与其它头部字段原样搬运，不做任何重新编码。
            // try 的重写结果先算好（它决定 code_item 的尾部字节）
            val tryRewrites = HashMap<Int, TryBlockRemapper.Result>()
            codeItemsToBuild.forEach { co ->
                if (co + DexFormat.CI_INSNS > dexBytes.size) return@forEach
                TryBlockRemapper.remap(dexBytes, co, typeIndexMap)?.let { tryRewrites[co] = it }
            }

            codeItemsToBuild.forEach { co ->
                if (co + DexFormat.CI_INSNS > dexBytes.size) return@forEach
                val insnsSize = src.u32AsInt(co + DexFormat.CI_INSNS_SIZE, "insns")
                if (co + DexFormat.CI_INSNS + insnsSize * 2 > dexBytes.size) return@forEach

                val triesSize = src.u16(co + DexFormat.CI_TRIES_SIZE)
                val r = tryRewrites[co]

                codeItemBuf.align(4)
                val newCo = codeItemBuf.position
                movedCodeItems[co] = newCo

                // 头部 16 字节：只有 debug_info_off 需要换
                val oldDbg = src.u32AsInt(co + DexFormat.CI_DEBUG_INFO_OFF, "dbg")
                // ⚠️ 写「段内偏移 + 1」，0 表示「没有 debug_info」。
                // 段的第一项段内偏移就是 0，直接用 0 会和「无」冲突：
                // 回填时看到 0 就跳过，那第一项永远填不进去，成为孤儿项——
                // ART 顺序遍历 debug_info 段时整体错位，报
                // `Bad index for debug_info_item parameter_name: 3486 >= 735`。
                val newDbg = if (oldDbg != 0) {
                    val rel = debugInfoCache[oldDbg]
                        ?: return Result.Failed(
                            "code_item@$co 的 debug_info@$oldDbg 没有重写结果"
                        )
                    rel + 1
                } else 0
                codeItemBuf.u16(src.u16(co + DexFormat.CI_REGISTERS_SIZE))
                codeItemBuf.u16(src.u16(co + DexFormat.CI_INS_SIZE))
                codeItemBuf.u16(src.u16(co + DexFormat.CI_OUTS_SIZE))
                codeItemBuf.u16(triesSize)
                codeItemBuf.u32(newDbg.toLong())
                // 指令区：目标方法在最前面插入新指令，原指令接在后面
                val isPatchTarget = (co == patchCodeOff)
                val prefix = if (isPatchTarget) insnPatch!!.newInsns else null
                // ⚠️ insns_size 必须是**总条数**（新指令 + 原指令）。
                // 只写新指令数会让 ART 认为这个方法只剩那几条指令，
                // 于是它按错误长度推进，撞到下一项的起始字节上报
                // `Non-zero padding ... before section of type 8193`。
                val totalInsns = (prefix?.size ?: 0) + insnsSize
                codeItemBuf.u32(totalInsns.toLong())
                if (prefix != null) {
                    // 指令模板里 method_idx 那一位是空的，这里填入真实下标。
                    // 这样「登记方法」与「插入调用」在同一次池重排里完成，
                    // 不需要为拿 method_idx 先单独产出一份 dex（那会导致 OOM）。
                    prefix.forEachIndexed { i, w ->
                        val v = if (i == insnPatch!!.methodIdxWord) newMethodIdx else w.toInt()
                        codeItemBuf.u16(v and 0xFFFF)
                    }
                }
                codeItemBuf.bytes(
                    dexBytes.copyOfRange(co + DexFormat.CI_INSNS, co + DexFormat.CI_INSNS + insnsSize * 2)
                )
                // tries 区
                if (triesSize > 0) {
                    // 奇数条指令后要补 2 字节，tries 区才是 4 对齐
                    if ((totalInsns and 1) != 0) codeItemBuf.u16(0)
                    if (r != null) {
                        codeItemBuf.bytes(r.tries)
                        codeItemBuf.bytes(r.handlers)
                    } else {
                        return Result.Failed("code_item@$co 有 tries 但重映射失败，产物会非法")
                    }
                }
                // code_item 按 4 对齐，写完补对齐（下一项起点必须 4 对齐）
                codeItemBuf.align(4)
            }

            // ── 10b. class_data 的重写留到 11c ──
            // class_data 里的 `code_off` 是 uleb128 变长字段，必须写成**最终绝对偏移**，
            // 排版后无法原地补丁。而 code_item 段基址依赖后面几个段的大小，
            // 所以等各段都产出完再统一生成。

            lap("10b-codeitem-done")
            // ── 10b-2. 重写注解 ──
            // 注解里是 type_idx + 元素名的 string_idx + 元素值，池重排后全部错位。
            // 表现：@kotlin.Metadata 变成 @java.util.Iterator，Kotlin 反射与 Compose 全废。
            //
            // 四种结构必须各自成段、各自连续（ART 要求同 type 连续）：
            //   annotation_item → annotation_set_item → annotation_set_ref_list
            //   → annotations_directory_item
            val annotationCache = HashMap<Int, Int>()
            val newAnnotationsOffsets = HashMap<Int, Int>()
            var annotationItemCount = 0
            var annotationSetCount = 0
            var annotationSetRefCount = 0
            val annSink = object : AnnotationRemapper.Sink {
                override fun item(buf: ByteSink, len: Int): Int {
                    annotationItemBuf.align(1)
                    val p = annotationItemBuf.position
                    // 直接按长度写入，不走 toByteArray() —— 那是 3.5 万次多余的复制。
                    // ByteSink 的内部数组长度可能大于 len，所以必须显式传长度。
                    annotationItemBuf.bytes(buf.raw(), 0, len)
                    annotationItemCount++
                    // annotation_item 按 1 对齐（ART 规定），无需补
                    return p
                }

                override fun set(buf: GrowableBuffer, len: Int): Int {
                    annotationSetBuf.align(4)
                    val p = annotationSetBuf.position
                    annotationSetBuf.bytes(buf.raw(), 0, len)
                    annotationSetBuf.align(4)      // 每项起点必须 4 对齐
                    annotationSetCount++
                    return p
                }

                override fun refList(buf: GrowableBuffer, len: Int): Int {
                    annotationSetRefBuf.align(4)
                    val p = annotationSetRefBuf.position
                    annotationSetRefBuf.bytes(buf.raw(), 0, len)
                    annotationSetRefBuf.align(4)
                    annotationSetRefCount++
                    return p
                }
            }
            var annDirCount = 0
            val tAnn0 = System.nanoTime()
            sec.classDefs.forEach { cd ->
                if (cd.annotationsOff == 0L) return@forEach
                val dirOff = cd.annotationsOff.toInt()
                if (newAnnotationsOffsets.containsKey(dirOff)) return@forEach
                annDirCount++
                val bytes = AnnotationRemapper.remapDirectory(
                    dexBytes, dirOff, evMaps, annotationCache, annSink
                )
                annotationDirBuf.align(4)
                val p = annotationDirBuf.position
                annotationDirBuf.bytes(bytes)
                newAnnotationsOffsets[dirOff] = p
            }

            // code_item 的指令区原样保留，但里面的 string_idx 是池下标。
            // 这里只收集「哪个 code_item 的哪条指令要改」，等指令区都落到输出后
            // 再统一回填（见 10d）。
            // ── 10b-3. 收集指令里所有需要重映射的池索引 ──
            // 用 dexlib2 的 instructionStart（文件绝对偏移）定位，不自己做「宽度表 + code_off」推算
            // —— 第一版那样做时所有 patch 静默跳过，产物指令全部错位。
            val dfForInsns = com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
                .fromInputStream(DexOpcodes.forBytes(dexBytes), dexBytes.inputStream())

            // 下面两个 lambda 已废弃（collect 改为直接读指令字节里的索引），
            // 保留签名只是为了不改动 InstructionRefRemapper 的接口。

            // ⚠️ 这里**不再构建 field/proto/method 三张查找表**。
            //
            // 那三张表的 key 是拼出来的字符串（`"类|名|类型"`、`"类|名|(参数)返回"`），
            // 建表要遍历 2.5 万个字段 + 9642 个 proto + 5.9 万个方法，
            // 手机上实测光是建表就花掉 1466ms。
            //
            // 它们存在的唯一理由是：`InstructionRefRemapper.collect` 需要从
            // `ref.definingClass` / `ref.parameterTypes` 这些**对象**反查旧索引。
            // 现在 collect 直接从指令字节里读索引（`input[start+2..3]`），
            // 这三张表连同它们的 lambda 已完全无用。

            val insnPatches = InstructionRefRemapper.collect(
                dfForInsns,
                dexBytes,
                stringIdx = { -1 },
                typeIdx = { -1 },
                fieldIdx = { _, _, _ -> -1 },
                methodIdx = { _, _, _, _ -> -1 },
                protoIdx = { _, _ -> -1 },
                codeOffMap = movedCodeItems,
                maps = evMaps,
            )
            // 自检：有方法却一条都没收集到，说明定位失败（静默跳过是致命错误）
            //
            // ⚠️ 例外：池没有重排时（方法已在池里、没有新增条目），
            // 所有索引本来就不变，`collect` 会全部跳过，此时 0 条是正确的。
            // 判据是「本次是否新增了池条目」，而不是「有没有产生 patch」。
            val poolChanged = newStringsToAdd.isNotEmpty() || newTypesFinal.isNotEmpty()
            if (insnPatches.isEmpty() && poolChanged && sec.methods.isNotEmpty()) {
                return Result.Failed(
                    "指令池引用收集失败（0 条），说明 instructionStart 定位不可用；" +
                        "若继续将会产出索引全错的 dex。请改用全量写入"
                )
            }

            lap("10b-annot-done $annDirCount")
            // ── 10c. 重写所有 type_list ──
            // proto_ids[i].parameters_off 与 class_def.interfaces_off 都指向 type_list，
            // 而 type_list 里存的是 type_idx（u16）—— type_ids 重排后它们全部失效。
            // 不修这一处，读出来的方法签名会完全不对，表现为「找不到目标方法」。
            val typeListOffsets = LinkedHashSet<Int>()
            var typeListCount = 0
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
            // 自检：每个 offsets 都必须是**真正的** type_list 起点。
            // 判据：把原包 type_list 段按物理顺序逐项展开，收集所有起点。
            // 混进非起点时（例如某个 proto 的 parameters_off 越界），
            // 会读出一个荒唐的 size，写得下去但 ART 报
            // `Bad index for type_list.type`，且极难回溯。
            run {
                val oldMapOff = src.u32AsInt(DexFormat.H_MAP_OFF, "map_off")
                val mapCount = src.u32AsInt(oldMapOff, "map_count")
                var tlStart = -1
                var tlCount = 0
                for (k in 0 until mapCount) {
                    val mo = oldMapOff + 4 + k * DexFormat.MAP_ITEM_WIDTH
                    if (src.u16(mo) == DexFormat.TYPE_TYPE_LIST) {
                        tlStart = src.u32AsInt(mo + 8, "tl_off")
                        tlCount = src.u32AsInt(mo + 4, "tl_count")
                        break
                    }
                }
                if (tlStart > 0) {
                    val valid = HashSet<Int>(tlCount * 2)
                    var q = tlStart
                    repeat(tlCount) {
                        valid += q
                        val n = src.u32AsInt(q, "tl_n")
                        q += 4 + n * 2
                        q = (q + 3) and 3.inv()
                    }
                    val bogus = typeListOffsets.filter { it !in valid }
                    if (bogus.isNotEmpty()) {
                        return Result.Failed(
                            "有 ${bogus.size} 个 type_list 偏移不是真正的起点" +
                                "（如 ${bogus.take(3)}）；说明 proto.parameters_off 或 " +
                                "class_def.interfaces_off 指向了非法位置"
                        )
                    }
                }
            }
            val typeListItemOffsets = ArrayList<Int>()
            val newTypeListOffsets = TypeListRemapper.remapAll(
                dexBytes, typeIndexMap, typeListOffsets
            ) { bytes ->
                typeListBuf.align(4)
                val pos = typeListBuf.position
                typeListBuf.bytes(bytes)
                // ⚠️ type_list 的长度是 `4 + size*2`，size 为奇数时会落在 2 mod 4 上。
                // ART 的 CheckMap 要求每个 item 的 offset 按 4 对齐（data 段里
                // 除 string_data/debug_info/annotation/class_data/encoded_array 外都是 4），
                // 所以**写完立刻补对齐**，而不是等下个 item 开始前再补——
                // 后者会让「上一个 item 的尾部」把填充算进去，导致读回的大小错位。
                typeListBuf.align(4)
                typeListCount++
                typeListItemOffsets += pos
                pos
            }

            // 新 proto 的参数列表：旧 dex 里没有对应的 type_list，必须新建一个
            // （参数用的是**新** type_idx）。空参数时 parametersOff 保持 0。
            // ⚠️ 只有真的新增了 proto 才写——`protoExists` 为真时复用旧条目，
            // 多写一个没人引用的 type_list 就是孤儿项。
            val newProtoParamsOff: Long =
                if (protoExists || newProtoEntry.paramTypeIdx.isEmpty()) {
                    0L
                } else {
                    val tl = GrowableBuffer(4 + newProtoEntry.paramTypeIdx.size * 2)
                    tl.u32(newProtoEntry.paramTypeIdx.size.toLong())
                    newProtoEntry.paramTypeIdx.forEach { tl.u16(it) }
                    typeListBuf.align(4)
                    val pos = typeListBuf.position
                    typeListBuf.bytes(tl.toByteArray())
                    typeListBuf.align(4)
                    typeListCount++
                    typeListItemOffsets += pos
                    pos.toLong()
                }

            // 有孤儿说明「写了却没人引用」——ART 会报 `Bad index for type_list.type`，
            // 但那时已经很难定位是谁多写的，所以这里提前拦。
            run {
                val referenced = HashSet<Int>(newTypeListOffsets.values)
                if (newProtoParamsOff > 0) referenced += newProtoParamsOff.toInt()
                val orphans = typeListItemOffsets.filter { it !in referenced }
                if (orphans.isNotEmpty()) {
                    return Result.Failed(
                        "type_list 段里有 ${orphans.size} 个孤儿项（段内偏移 ${orphans.take(3)}），" +
                            "说明写入了没有 proto/interfaces 引用的项"
                    )
                }
            }

            run {
                // 写入项数 = remapAll 的项数 + 新增 proto 的 type_list（若有）
                val expected = newTypeListOffsets.size + if (newProtoParamsOff > 0) 1 else 0
                if (typeListItemOffsets.size != expected) {
                    return Result.Failed(
                        "type_list 写入项数 ${typeListItemOffsets.size} 与预期 $expected 不一致" +
                            "（映射表 ${newTypeListOffsets.size} + 新增 ${if (newProtoParamsOff > 0) 1 else 0}）"
                    )
                }
                // ⚠️ 每个项必须有**唯一**起点。起点重复说明后写的内容覆盖了前面的，
                // 映射表里就有多个 key 指向同一处，段内布局必然崩。
                val dup = typeListItemOffsets.groupingBy { it }.eachCount().filter { it.value > 1 }
                if (dup.isNotEmpty()) {
                    return Result.Failed(
                        "type_list 段内有 ${dup.size} 处起点重复（如 ${dup.keys.take(3)}），" +
                            "说明写入位置互相覆盖"
                    )
                }
            }

            // 自检：段内每个 type_list 的 type_idx 都必须落在 type_ids 范围内。
            run {
                val seg = typeListBuf.toByteArray()
                val typeCount = mergedTypeStringIdx.size
                newTypeListOffsets.values.sorted().forEach { rel ->
                    if (rel + 4 > seg.size) {
                        return Result.Failed("type_list 段内偏移 $rel 越界（段长 ${seg.size}）")
                    }
                    val n = (seg[rel].toInt() and 0xFF) or
                        ((seg[rel + 1].toInt() and 0xFF) shl 8) or
                        ((seg[rel + 2].toInt() and 0xFF) shl 16) or
                        ((seg[rel + 3].toInt() and 0xFF) shl 24)
                    if (n < 0 || rel + 4 + n * 2 > seg.size) {
                        return Result.Failed(
                            "type_list@段内$rel 的 size=$n 超出段长 ${seg.size}；" +
                                "说明段内布局错位"
                        )
                    }
                    for (j in 0 until n) {
                        val v = (seg[rel + 4 + j * 2].toInt() and 0xFF) or
                            ((seg[rel + 5 + j * 2].toInt() and 0xFF) shl 8)
                        if (v >= typeCount) {
                            return Result.Failed(
                                "type_list@段内$rel 第 $j 个 type_idx=$v 超过 " +
                                    "type_ids 大小 $typeCount"
                            )
                        }
                    }
                }
            }

            lap("10c-typelist-done")
            // ── 11. 产出各 id 表（写入各自的缓冲，偏移在排版时确定）──
            // ⚠️ 这里必须用 HashMap 查偏移，不能 indexOf——
            // 8.5 万个字符串 × 线性查找 = 72 亿次比较，实测要 14 秒（比全量还慢）。
            val oldStringOffByValue = HashMap<String, Int>(oldStrings.size * 2)
            oldStrings.forEachIndexed { i, s -> oldStringOffByValue.putIfAbsent(s, sec.stringOffsets[i]) }

            // string_ids：指向新 string_data 段内偏移（排版后回填绝对值）
            val stringIdsBuf = GrowableBuffer(mergedStrings.size * 4)
            mergedStrings.forEach { s ->
                val newOff = newStringDataOffsets[s]
                if (newOff != null) {
                    stringIdsBuf.u32(newOff.toLong())        // 新字符串：段内偏移
                } else {
                    val oldOff = oldStringOffByValue[s] ?: error("字符串 '$s' 找不到数据偏移")
                    stringIdsBuf.u32((oldStringDataOffsets[oldOff] ?: 0).toLong())
                }
            }
            // ⚠️ string_ids 的每项都必须非 0（string_data_off 为 0 是非法值），
            // 所以这里不需要哨兵；但段内偏移 0 是合法的第一项，
            // 回填时统一加段基址即可（见 12a，那里不做「0 跳过」判断）。

            // type_ids
            val typeIdsBuf = GrowableBuffer(mergedTypeStringIdx.size * 4)
            mergedTypeStringIdx.forEach { typeIdsBuf.u32(it.toLong()) }

            // proto_ids
            val protoIdsBuf = GrowableBuffer(mergedProtos.size * 12)
            mergedProtos.forEachIndexed { pIdx, p ->
                protoIdsBuf.u32(p.shortyString.toLong())
                protoIdsBuf.u32(p.returnTypeIdx.toLong())
                // parameters_off：新 proto 用自己的新 type_list，旧 proto 用重映射后的
                // ⚠️ 这里必须按**下标**判断是不是新 proto，不能用 `p === newProtoEntry`：
                // `protoExists` 为真时列表里没有那个对象，`===` 永不成立，
                // 结果新 type_list 写了却没人引用 → 产物里出现孤儿项，
                // ART 报 `Bad index for type_list.type`。
                val newParams: Long = if (pIdx == newProtoIdx && !protoExists) {
                    // 新 type_list 的段内偏移 +1（0 表示没有参数）
                    newProtoParamsOff + 1
                } else if (p.parametersOff > 0) {
                    val m = newTypeListOffsets[p.parametersOff.toInt()]
                        ?: return Result.Failed(
                            "proto 的 type_list@${p.parametersOff} 未重映射；" +
                                "继续写会产出越界索引"
                        )
                    m.toLong() + 1
                } else {
                    0L
                }
                protoIdsBuf.u32(newParams)
            }

            // field_ids
            val fieldIdsBuf = GrowableBuffer(fieldOrder.size * 8)
            fieldOrder.forEach { e ->
                fieldIdsBuf.u16(e.value.classIdx)
                fieldIdsBuf.u16(e.value.typeIdx)
                fieldIdsBuf.u32(e.value.nameIdx.toLong())
            }

            // method_ids
            val methodIdsBuf = GrowableBuffer(methodOrder.size * 8)
            methodOrder.forEach { e ->
                methodIdsBuf.u16(e.value.classIdx)
                methodIdsBuf.u16(e.value.protoIdx)
                methodIdsBuf.u32(e.value.nameString.toLong())
            }

            lap("11-ids-done")
            // ── 11a. static_values：静态字段初值也是池下标 ──
            // `static final String X = "..."` 的初值存在 encoded_array 里，
            // 元素是 string_idx / type_idx。不改的话常量会变成别的内容
            // —— 实测 `"android:support:activity-result"` 变成了
            // `"android.window.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE"`。
            val newStaticValuesOffsets = HashMap<Int, Int>()
            val staticValueArrays = LinkedHashSet<Int>()
            sec.classDefs.forEach { cd ->
                if (cd.staticValuesOff > 0 && cd.staticValuesOff < Int.MAX_VALUE) {
                    staticValueArrays += cd.staticValuesOff.toInt()
                }
            }
            staticValueArrays.forEach { oldOff ->
                val tmp = ByteSink(64)
                var q = oldOff
                val (cnt2, n2) = Leb128.readUleb128(dexBytes, q)
                q += n2
                tmp.uleb128(cnt2)
                repeat(cnt2.toInt()) {
                    q = EncodedValueRemapper.readAndWrite(dexBytes, q, tmp, evMaps)
                }
                // ⚠️ encoded_array_item 是 **1 字节对齐**的段（ART 的
                // CheckIntraSectionIterate 里 alignment = 1）。
                // 这里 align(4) 会在项之间插填充，而 ART 是紧密遍历整段的，
                // 于是后续每项起点都偏移，报
                // `Non-zero padding 1 before section of type 8196`。
                newStaticValuesOffsets[oldOff] = encodedArrayBuf.position
                encodedArrayBuf.bytes(tmp.toByteArray())
            }

            lap("11a-static-values")
            // ── 11c. 生成 class_data（需要 code_item 段的绝对基址）──
            // class_data 里的 `code_off` 是 uleb128 变长字段，必须是**最终绝对偏移**，
            // 排版后无法原地补丁。此时 code_item 段之前的所有段都已产出完毕，
            // 按 sectionOrder 顺序累加就能得到它的基址。
            val codeItemBase = DexSectionLayout.offsetOf(
                DexFormat.HEADER_SIZE,
                sectionOrder,
                mapOf(
                    DexFormat.TYPE_STRING_ID_ITEM to mergedStrings.size * 4,
                    DexFormat.TYPE_TYPE_ID_ITEM to mergedTypeStringIdx.size * 4,
                    DexFormat.TYPE_PROTO_ID_ITEM to mergedProtos.size * 12,
                    DexFormat.TYPE_FIELD_ID_ITEM to fieldOrder.size * 8,
                    DexFormat.TYPE_METHOD_ID_ITEM to methodOrder.size * 8,
                    DexFormat.TYPE_CLASS_DEF_ITEM to classOrder.size * DexFormat.CLASS_DEF_WIDTH,
                    DexFormat.TYPE_STRING_DATA_ITEM to stringDataBuf.size,
                    DexFormat.TYPE_TYPE_LIST to typeListBuf.size,
                    DexFormat.TYPE_ENCODED_ARRAY_ITEM to encodedArrayBuf.size,
                    DexFormat.TYPE_ANNOTATION_ITEM to annotationItemBuf.size,
                    DexFormat.TYPE_ANNOTATION_SET_ITEM to annotationSetBuf.size,
                    DexFormat.TYPE_ANNOTATION_SET_REF_LIST to annotationSetRefBuf.size,
                    DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM to annotationDirBuf.size,
                    DexFormat.TYPE_DEBUG_INFO_ITEM to debugInfoBuf.size,
                    DexFormat.TYPE_CODE_ITEM to codeItemBuf.size,
                ),
                DexFormat.TYPE_CODE_ITEM,
            )
            if (codeItemBase < 0) {
                return Result.Failed("算不出 code_item 段基址，无法确定 class_data 的 code_off")
            }
            val absCodeOffMap = HashMap<Int, Int>(movedCodeItems.size * 2)
            movedCodeItems.forEach { (oldOff, relOff) -> absCodeOffMap[oldOff] = codeItemBase + relOff }
            val remapMaps = ClassDataRemapper.Maps(
                fieldMap = fieldIndexMap,
                methodMap = methodIndexMap,
                codeOffMap = absCodeOffMap,
            )
            val newClassDataOffsets = HashMap<Int, Int>()   // 旧偏移 → 段内偏移
            sec.classDefs.forEach { cd ->
                val old = cd.classDataOff
                if (old == 0L) return@forEach
                val oldOff = old.toInt()
                if (newClassDataOffsets.containsKey(oldOff)) return@forEach
                val rewritten = ClassDataRemapper.remap(dexBytes, oldOff, remapMaps)
                // ⚠️ class_data_item 也是 **1 字节对齐**，不能 align(4)：
                // 填充字节会被 ART 当成下一个 class_data 的内容。
                newClassDataOffsets[oldOff] = classDataBuf.position
                classDataBuf.bytes(rewritten)
            }

            lap("11c-classdata")
            // ── 11b. class_defs ──
            val classDefsBuf = GrowableBuffer(classOrder.size * DexFormat.CLASS_DEF_WIDTH)
            classOrder.forEach { e ->
                val c = e.value
                classDefsBuf.u32(c.classIdx.toLong())
                classDefsBuf.u32(c.accessFlags.toLong())
                // -1 表示 NO_INDEX，写回 0xFFFFFFFF
                classDefsBuf.u32(if (c.superIdx < 0) DexFormat.NO_INDEX else c.superIdx.toLong())
                // interfaces_off 也指向 type_list（段内偏移，排版后回填）
                // ⚠️ 段内偏移可能是 0（段的第一项），与「没有」冲突，
                // 所以统一用 `值 + 1`，0 仍表示没有。回填时减 1。
                val newIfaces = if (c.interfacesOff > 0) {
                    (newTypeListOffsets[c.interfacesOff.toInt()]?.let { it + 1 })?.toLong() ?: 0L
                } else 0L
                classDefsBuf.u32(newIfaces)
                classDefsBuf.u32(if (c.sourceFileIdx < 0) DexFormat.NO_INDEX else c.sourceFileIdx.toLong())
                // annotations_off 指向重写后的 directory
                val newAnn = if (c.annotationsOff > 0) {
                    (newAnnotationsOffsets[c.annotationsOff.toInt()]?.let { it + 1 })?.toLong() ?: 0L
                } else 0L
                classDefsBuf.u32(newAnn)
                // ⚠️ 这里把「段内偏移」写成 `值 + 1`，用 0 表示「没有」。
                // 因为 class_data 段的第一项段内偏移就是 0，与「无 class_data」冲突。
                // 回填时统一减 1 并加段基址。
                val newCdo = newClassDataOffsets[c.classDataOff.toInt()]?.let { it + 1 } ?: 0
                classDefsBuf.u32(newCdo.toLong())
                // static_values_off
                val newSv = if (c.staticValuesOff > 0) {
                    (newStaticValuesOffsets[c.staticValuesOff.toInt()]?.let { it + 1 })?.toLong() ?: 0L
                } else 0L
                classDefsBuf.u32(newSv)
            }

            lap("11b-classdefs-buf")
            // ── 12. 按规范段序排版 ──
            //
            // 段序照抄 dexlib2 的 DexWriter.writeTo()：它写出的文件必然通过 ART 校验。
            // 六张 id 表在前，之后是 data 区（data_off 指向第一个 data 段），最后 map_list。
            val sections = listOf(
                // ⚠️ 用 `raw()` + 长度直接写入，**不要** `toByteArray()`。
                // `string_data` 和 `code_item` 各 15MB 级别，每段复制一次
                // 就是两次 15MB 分配；手机上这些大对象会直接进老年代，
                // 引发长时间 GC —— 实测同一份代码两次注入差了近 2 倍时间。
                DexSectionLayout.Section(DexFormat.TYPE_STRING_ID_ITEM, mergedStrings.size, stringIdsBuf.size) { t -> t.bytes(stringIdsBuf.raw(), 0, stringIdsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_TYPE_ID_ITEM, mergedTypeStringIdx.size, typeIdsBuf.size) { t -> t.bytes(typeIdsBuf.raw(), 0, typeIdsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_PROTO_ID_ITEM, mergedProtos.size, protoIdsBuf.size) { t -> t.bytes(protoIdsBuf.raw(), 0, protoIdsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_FIELD_ID_ITEM, fieldOrder.size, fieldIdsBuf.size) { t -> t.bytes(fieldIdsBuf.raw(), 0, fieldIdsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_METHOD_ID_ITEM, methodOrder.size, methodIdsBuf.size) { t -> t.bytes(methodIdsBuf.raw(), 0, methodIdsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_CLASS_DEF_ITEM, classOrder.size, classDefsBuf.size) { t -> t.bytes(classDefsBuf.raw(), 0, classDefsBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_STRING_DATA_ITEM, mergedStrings.size, stringDataBuf.size) { t -> t.bytes(stringDataBuf.raw(), 0, stringDataBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_TYPE_LIST, typeListCount, typeListBuf.size) { t -> t.bytes(typeListBuf.raw(), 0, typeListBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_ENCODED_ARRAY_ITEM, staticValueArrays.size, encodedArrayBuf.size) { t -> t.bytes(encodedArrayBuf.raw(), 0, encodedArrayBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_ANNOTATION_ITEM, annotationItemCount, annotationItemBuf.size) { t -> t.bytes(annotationItemBuf.raw(), 0, annotationItemBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_ANNOTATION_SET_ITEM, annotationSetCount, annotationSetBuf.size) { t -> t.bytes(annotationSetBuf.raw(), 0, annotationSetBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_ANNOTATION_SET_REF_LIST, annotationSetRefCount, annotationSetRefBuf.size) { t -> t.bytes(annotationSetRefBuf.raw(), 0, annotationSetRefBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM, newAnnotationsOffsets.size, annotationDirBuf.size) { t -> t.bytes(annotationDirBuf.raw(), 0, annotationDirBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_DEBUG_INFO_ITEM, debugInfoCache.size, debugInfoBuf.size) { t -> t.bytes(debugInfoBuf.raw(), 0, debugInfoBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_CODE_ITEM, codeItemsToBuild.size, codeItemBuf.size) { t -> t.bytes(codeItemBuf.raw(), 0, codeItemBuf.size) },
                DexSectionLayout.Section(DexFormat.TYPE_CLASS_DATA_ITEM, newClassDataOffsets.size, classDataBuf.size) { t -> t.bytes(classDataBuf.raw(), 0, classDataBuf.size) },
            )

            val laid = DexSectionLayout.layout(DexFormat.HEADER_SIZE, dexBytes, sections)
            val out = laid.buffer

            lap("12-layout")
            lap("12a-before-fillback")
            // ── 12a. 把「段内偏移」回填成绝对偏移 ──
            // 每个段里引用了别的段的位置，排版前只知道段内偏移，现在加上段基址。
            fun base(type: Int) = laid.base(type)

            // string_ids：每项指向 string_data 段内偏移
            var p = base(DexFormat.TYPE_STRING_ID_ITEM)
            repeat(mergedStrings.size) {
                val rel = out.peekU32(p)
                out.patchU32(p, (base(DexFormat.TYPE_STRING_DATA_ITEM) + rel).toLong())
                p += 4
            }
            // type_ids / field_ids / method_ids 只含池下标，无需回填
            // proto_ids.parameters_off → type_list（写入时是段内偏移 +1）
            p = base(DexFormat.TYPE_PROTO_ID_ITEM)
            repeat(mergedProtos.size) {
                val rel = out.peekU32(p + 8)
                if (rel != 0) out.patchU32(p + 8, (base(DexFormat.TYPE_TYPE_LIST) + rel - 1).toLong())
                p += 12
            }
            // class_defs: interfaces_off / annotations_off / class_data_off / static_values_off
            // ⚠️ 这些字段写入的是「段内偏移 + 1」，0 表示没有。回填时减 1 再加段基址。
            p = base(DexFormat.TYPE_CLASS_DEF_ITEM)
            repeat(classOrder.size) {
                val ifaces = out.peekU32(p + DexFormat.CD_INTERFACES_OFF)
                if (ifaces != 0) out.patchU32(
                    p + DexFormat.CD_INTERFACES_OFF,
                    (base(DexFormat.TYPE_TYPE_LIST) + ifaces - 1).toLong(),
                )
                val ann = out.peekU32(p + DexFormat.CD_ANNOTATIONS_OFF)
                if (ann != 0) out.patchU32(
                    p + DexFormat.CD_ANNOTATIONS_OFF,
                    (base(DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM) + ann - 1).toLong(),
                )
                val cdo = out.peekU32(p + DexFormat.CD_CLASS_DATA_OFF)
                if (cdo != 0) out.patchU32(
                    p + DexFormat.CD_CLASS_DATA_OFF,
                    (base(DexFormat.TYPE_CLASS_DATA_ITEM) + cdo - 1).toLong(),
                )
                val sv = out.peekU32(p + DexFormat.CD_STATIC_VALUES_OFF)
                if (sv != 0) out.patchU32(
                    p + DexFormat.CD_STATIC_VALUES_OFF,
                    (base(DexFormat.TYPE_ENCODED_ARRAY_ITEM) + sv - 1).toLong(),
                )
                p += DexFormat.CLASS_DEF_WIDTH
            }
            // code_item.debug_info_off → debug_info 段
            p = base(DexFormat.TYPE_CODE_ITEM)
            var co = p
            // ⚠️ 这里不能靠「累加 code_item 长度」推算每个 code_item 的位置：
            // code_item 长度依赖 tries/handlers 的重编码结果，一处算错后面全错，
            // 而且错误表现为 `Bad index for debug_info_item parameter_name`
            // （debug_info_off 指到了别处），完全不像「偏移算错」。
            // 改为在生成时把新偏移记进 movedCodeItems，直接按映射表回填。
            val codeItemBaseForDbg = base(DexFormat.TYPE_CODE_ITEM)
            val debugInfoBase = base(DexFormat.TYPE_DEBUG_INFO_ITEM)
            movedCodeItems.forEach { (_, relCo) ->
                val newCo = codeItemBaseForDbg + relCo
                val dbgSentinel = out.peekU32(newCo + DexFormat.CI_DEBUG_INFO_OFF)
                if (dbgSentinel != 0) {
                    out.patchU32(
                        newCo + DexFormat.CI_DEBUG_INFO_OFF,
                        // 写入时是「段内偏移 + 1」，这里减 1 再加段基址
                        (debugInfoBase + dbgSentinel - 1).toLong(),
                    )
                }
            }

            // 注解内部引用：annotation_set / ref_list / directory 都指向别的段的段内偏移
            // （这些在 remapDirectory 时就按段内偏移记录，现在统一加基址）
            // ⚠️ 这里必须严格按**各自段的实际长度**扫描：
            // 越界一步就会写到紧邻的其它段（实测把 type_list 覆盖成随机值，
            // 表现为 `Bad index for type_list.type`，而段级自检全部通过）。
            // item → annotation_item 段自身；set 里的条目指向 annotation_item
            run {
                val setBase = base(DexFormat.TYPE_ANNOTATION_SET_ITEM)
                val setLen = annotationSetBuf.size
                var sp = 0
                while (sp + 4 <= setLen) {
                    val n = out.peekU32(setBase + sp)
                    sp += 4
                    if (n < 0 || sp + n * 4 > setLen) {
                        return Result.Failed(
                            "annotation_set 扫描越界：第 $sp 字节处 size=$n 超出段长 $setLen"
                        )
                    }
                    repeat(n) {
                        val rel = out.peekU32(setBase + sp)
                        // 写入时是「段内偏移 + 1」，0 表示没有（见 AnnotationRemapper）
                        out.patchU32(
                            setBase + sp,
                            (base(DexFormat.TYPE_ANNOTATION_ITEM) + rel - 1).toLong(),
                        )
                        sp += 4
                    }
                }
            }
            // ref_list 里的条目指向 annotation_set
            run {
                val refBase = base(DexFormat.TYPE_ANNOTATION_SET_REF_LIST)
                val refLen = annotationSetRefBuf.size
                var rp = 0
                while (rp + 4 <= refLen) {
                    val n = out.peekU32(refBase + rp)
                    rp += 4
                    if (n < 0 || rp + n * 4 > refLen) {
                        return Result.Failed(
                            "annotation_set_ref_list 扫描越界：size=$n 超出段长 $refLen"
                        )
                    }
                    repeat(n) {
                        val rel = out.peekU32(refBase + rp)
                        if (rel != 0) {
                            out.patchU32(
                                refBase + rp,
                                (base(DexFormat.TYPE_ANNOTATION_SET_ITEM) + rel - 1).toLong(),
                            )
                        }
                        rp += 4
                    }
                }
            }
            // directory 里的 class_annotations_off / field/method/param 的 set 偏移
            run {
                val dirBase = base(DexFormat.TYPE_ANNOTATIONS_DIRECTORY_ITEM)
                val dirLen = annotationDirBuf.size
                var dp = 0
                while (dp + 16 <= dirLen) {
                    val classAnn = out.peekU32(dirBase + dp)
                    if (classAnn != 0) out.patchU32(
                        dirBase + dp,
                        (base(DexFormat.TYPE_ANNOTATION_SET_ITEM) + classAnn - 1).toLong(),
                    )
                    val fs = out.peekU32(dirBase + dp + 4)
                    val ms = out.peekU32(dirBase + dp + 8)
                    val ps = out.peekU32(dirBase + dp + 12)
                    var q = dp + 16
                    val entries = fs + ms + ps
                    if (entries < 0 || q + entries * 8 > dirLen) {
                        return Result.Failed(
                            "annotations_directory 扫描越界：fs=$fs ms=$ms ps=$ps 超出段长 $dirLen"
                        )
                    }
                    repeat(fs) {
                        val setOff = out.peekU32(dirBase + q + 4)
                        if (setOff != 0) out.patchU32(
                            dirBase + q + 4,
                            (base(DexFormat.TYPE_ANNOTATION_SET_ITEM) + setOff - 1).toLong(),
                        )
                        q += 8
                    }
                    repeat(ms) {
                        val setOff = out.peekU32(dirBase + q + 4)
                        if (setOff != 0) out.patchU32(
                            dirBase + q + 4,
                            (base(DexFormat.TYPE_ANNOTATION_SET_ITEM) + setOff - 1).toLong(),
                        )
                        q += 8
                    }
                    repeat(ps) {
                        val refOff = out.peekU32(dirBase + q + 4)
                        if (refOff != 0) out.patchU32(
                            dirBase + q + 4,
                            (base(DexFormat.TYPE_ANNOTATION_SET_REF_LIST) + refOff - 1).toLong(),
                        )
                        q += 8
                    }
                    dp = q
                }
            }

            lap("12a-fillback-done")
            // ── 12b. 回填指令里的池索引 ──
            // 指令里的索引现在是**池下标**，不需要加段基址，但要按映射表换算。
            // code_item 被整体重建过，所以 patch 位置要按「新 code_off + 区内偏移」重算。
            val insnMaps = EncodedValueRemapper.Maps(
                stringMap = stringIndexMap,
                typeMap = typeIndexMap,
                fieldMap = fieldIndexMap,
                methodMap = methodIndexMap,
                protoMap = protoIndexMap,
            )
            // ⚠️ 只处理能定位到新 code_item 的 patch。
            // 找不到映射的 patch 其 `absOffset` 是**原文件**的绝对偏移，
            // 若照它去写新文件，就会把别的段（实测是 type_list）覆盖成随机值——
            // 表现为 `Bad index for type_list.type: 56c >= 14e`，
            // 而所有段级自检都是通过的，极难定位。
            val codeBase = base(DexFormat.TYPE_CODE_ITEM)
            val codeItemRange = codeBase until (codeBase + codeItemBuf.size)
            // 目标方法前面插了几个 16 位字（用于修正它体内原指令的位置）
            val insnPrefixWords = if (patchCodeOff >= 0) (insnPatch?.newInsns?.size ?: 0) else 0
            runCatching {
                val hit = insnPatches.count { it.ownerCodeOff == patchCodeOff }
                java.io.File("/data/data/com.termux/files/home/diag-patch2.txt")
                    .writeText("patchCodeOff=$patchCodeOff prefixWords=$insnPrefixWords " +
                        "总patch=${insnPatches.size} 命中=$hit\n" +
                        "前5个 ownerCodeOff=${insnPatches.take(5).map { it.ownerCodeOff }}\n")
            }
            val shiftedPatches = ArrayList<InstructionRefRemapper.Patch>(insnPatches.size)

            insnPatches.forEach { patch ->
                // ⚠️ `collect` 已经把 absOffset 算成「新 code_item 的**段内**位置」：
                // 它用 `shift = newCodeOff - oldCodeOff` 修正过，而那个 newCodeOff
                // 来自 movedCodeItems，本来就是段内偏移。
                // 所以这里只加段基址，**不能再做一次位移** ——
                // 第一版在这里又加了 `newBase + inItem`，等于双重位移，
                // 结果指令索引被写到 type_list 段里（偏移 98374 落在 96376..99326），
                // ART 报 `Bad index for type_list.type`，段级自检全绿却查不出来。
                // ⚠️ 目标方法前面插了新指令，它体内所有原指令都后移了 prefixWords 个字。
                // 不补这个偏移，patch 会落到**前一条指令上**，把它的寄存器字段写坏
                // —— 实测把 `invoke-super {v4,v5}` 改成了 `{v0,v10}`，
                // 运行时报 `invalid reg index (10) in non-range invoke`。
                // ⚠️ 目标方法前面插了新指令，它体内的原指令全部后移。
                // `absOffset` 是按**原**区内偏移算的，所以要补上插入的**字节数**。
                //
                // 这里踩过两个坑：
                //   1. 不加 → patch 落到前一条指令上，把它的寄存器字段写坏
                //   2. 加了但按「字」而不是「字节」→ 只后移 3 字节，
                //      正好错开半个字，写到指令中间，把 op 改成了别的值
                //      （实测 `invoke-super` 的 0x6f 变成 0x00，变成 nop）
                // dex 里的偏移单位是**字节**，所以必须 ×2。
                val insertBytes =
                    if (patch.ownerCodeOff == patchCodeOff) insnPrefixWords * 2 else 0
                val shifted = codeBase + patch.absOffset + insertBytes
                if (shifted !in codeItemRange) {
                    return Result.Failed(
                        "指令 patch 段内偏移 ${patch.absOffset} 加上 code_item 基址 $codeBase " +
                            "后得到 $shifted，落在段 $codeItemRange 之外"
                    )
                }
                shiftedPatches += patch.copy(absOffset = shifted)
            }
            runCatching {
                val tgt = shiftedPatches.filter { it.ownerCodeOff == patchCodeOff }
                    .map { "${it.kind}@${it.absOffset - codeBase}" }
                java.io.File("/data/data/com.termux/files/home/diag-hit.txt")
                    .writeText("codeBase=$codeBase 目标方法段内地址=${tgt}\n" +
                        "原方法指令应为 0x206f 0x0004 0x0054 0x4007...\n")
            }
            InstructionRefRemapper.patch(
                out,
                shiftedPatches,
                insnMaps,
                codeItemRange,
            )?.let { return Result.Failed(it) }

            lap("12b-insn-patch")
            // ── 13. 写 map_list 与 header ──
            // map 必须在所有交叉引用回填之后写：它记录的是各段起点，
            // 而段内回填不改起点，所以放在最后即可。
            val mapEnd = DexSectionLayout.appendMap(laid)
            out.patchU32(DexFormat.H_MAP_OFF, laid.mapOffset.toLong())
            // file_size 用 map 的真实结束位置，而不是缓冲区长度：
            // 预留区里没被 map 项填满的零字节要裁掉，否则 map_list 不是最后一项
            val finalSize = mapEnd
            out.patchU32(DexFormat.H_FILE_SIZE, finalSize.toLong())
            out.patchU32(DexFormat.H_STRING_IDS_SIZE, mergedStrings.size.toLong())
            out.patchU32(DexFormat.H_STRING_IDS_OFF, base(DexFormat.TYPE_STRING_ID_ITEM).toLong())
            out.patchU32(DexFormat.H_TYPE_IDS_SIZE, mergedTypeStringIdx.size.toLong())
            out.patchU32(DexFormat.H_TYPE_IDS_OFF, base(DexFormat.TYPE_TYPE_ID_ITEM).toLong())
            out.patchU32(DexFormat.H_PROTO_IDS_SIZE, mergedProtos.size.toLong())
            out.patchU32(DexFormat.H_PROTO_IDS_OFF, base(DexFormat.TYPE_PROTO_ID_ITEM).toLong())
            out.patchU32(DexFormat.H_FIELD_IDS_SIZE, fieldOrder.size.toLong())
            out.patchU32(DexFormat.H_FIELD_IDS_OFF, base(DexFormat.TYPE_FIELD_ID_ITEM).toLong())
            out.patchU32(DexFormat.H_METHOD_IDS_SIZE, methodOrder.size.toLong())
            out.patchU32(DexFormat.H_METHOD_IDS_OFF, base(DexFormat.TYPE_METHOD_ID_ITEM).toLong())
            out.patchU32(DexFormat.H_CLASS_DEFS_SIZE, classOrder.size.toLong())
            out.patchU32(DexFormat.H_CLASS_DEFS_OFF, base(DexFormat.TYPE_CLASS_DEF_ITEM).toLong())
            out.patchU32(DexFormat.H_DATA_OFF, laid.dataOffset.toLong())
            out.patchU32(DexFormat.H_DATA_SIZE, laid.dataSize.toLong())

            // ⚠️ 只要一次复制。写成 `toByteArray().copyOf(finalSize)` 会复制两次 30MB：
            // 第一次 toByteArray 复制出完整长度，第二次 copyOf 再复制成 finalSize。
            // 手机上两次 30MB 分配足以触发明显的 GC 停顿。
            val raw = out.toByteArray()
            val result = if (raw.size == finalSize) raw else raw.copyOf(finalSize)
            fixChecksums(result)

            Result.Ok(
                dex = result,
                methodIdx = newMethodIdx,
                newStringCount = newStringsToAdd.size,
                newTypeCount = newTypesFinal.size,
                note = buildString {
                    append("+${newStringsToAdd.size} 字符串 +${newTypesFinal.size} 类型 ")
                    append("${dexBytes.size} → ${result.size} 字节")
                    // 分段耗时：用户看到「注入慢」时能知道慢在哪一段
                    append(" ｜ ")
                    append(laps.joinToString(" ") { "${it.first}=${it.second}ms" })
                },
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
        /**
         * 把池下标重映射到新池。
         *
         * ⚠️ `shortyString` 也必须映射——它是 **string_idx**，
         * 字符串池重排后原下标会指向别的字符串。
         * 漏掉它的表现是 ART 报 `Bad shorty character: 'T'`
         * （shorty 只允许 `VZBSCIJFD` 和 `L` 这几类字符）。
         */
        fun remap(typeMap: IntArray, stringMap: IntArray) = copy(
            shortyString = stringMap[shortyString],
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

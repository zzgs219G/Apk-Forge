package com.xixin.apkforge.core.dex.patch

/**
 * 重写 `type_list` 里的 `type_idx`。
 *
 * ## 为什么必须做
 *
 * `proto_ids[i].parameters_off` 指向一个 `type_list`，里面存的是 **`type_idx`（u16）**，
 * 指向 `type_ids` 表。`type_ids` 因为新增类而重排后，这些下标全部失效——
 * 表现是「签名读出来完全不是原来的」，进而 `locateMethod` 找不到方法。
 *
 * ## 结构
 *
 * ```
 * type_list {
 *   u32 size
 *   u16 list[size]     ← 这些需要重映射
 * }
 * ```
 *
 * `type_list` 在 dex 里是**共享**的（很多 proto 指向同一个 type_list），
 * 所以按「旧偏移 → 新偏移」缓存，避免重复写。
 *
 * 注意：`type_list` 的偏移还会被 `class_def_item.interfaces_off` 引用，
 * 所以 class_defs 那边也要用新的偏移（这里统一返回映射表）。
 */
internal object TypeListRemapper {

    /**
     * 扫描并重写所有 `type_list`。
     *
     * @param data 原 dex
     * @param typeMap 旧 type_idx → 新 type_idx
     * @param offsets 所有需要处理的 type_list 偏移（来自 proto_ids 与 class_defs.interfaces_off）
     * @param sink 追加新字节并返回其偏移的回调
     * @return 旧偏移 → 新偏移
     */
    fun remapAll(
        data: ByteArray,
        typeMap: IntArray,
        offsets: Collection<Int>,
        sink: (ByteArray) -> Int,
    ): Map<Int, Int> {
        val result = HashMap<Int, Int>(offsets.size * 2)
        val buf = DexBuffer(data)
        for (off in offsets) {
            if (off <= 0 || off >= data.size) continue
            if (result.containsKey(off)) continue
            val size = runCatching { buf.u32AsInt(off, "type_list size") }.getOrNull() ?: continue
            // ⚠️ size 必须落在 type_ids 范围内：一个 type_list 的参数个数不可能超过
            // type_ids 总数（每个参数都是一个 type）。没有这条校验时，
            // 偏移算错会读出一个巨大的 size，写出去就是越界索引，
            // ART 报 `Bad index for type_list.type: 56c >= 14e` 时已经很难回溯。
            if (size < 0 || size > 0xFFFF) continue
            if (off + 4 + size * 2 > data.size) continue

            val out = GrowableBuffer(4 + size * 2)
            out.u32(size.toLong())
            for (i in 0 until size) {
                val oldType = buf.u16(off + 4 + i * 2)
                if (oldType >= typeMap.size) {
                    throw IllegalStateException(
                        "type_list@$off 第 $i 个参数 type_idx=$oldType 超过 " +
                            "type_ids 范围 ${typeMap.size}；说明该偏移不是 type_list 起点"
                    )
                }
                out.u16(typeMap[oldType])
            }
            result[off] = sink(out.toByteArray())
        }
        return result
    }
}

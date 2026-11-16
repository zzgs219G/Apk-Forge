package com.xixin.apkforge.core.dex.patch

/**
 * 重写 `code_item` 里 `tries` 部分的异常类型下标。
 *
 * ## 结构
 *
 * `code_item` 在指令区之后（4 字节对齐）跟一组 try 块：
 *
 * ```
 * code_item {
 *   ... 头部 16 字节 ...
 *   u16 insns[insns_size]
 *   // 若 tries_size > 0 且 insns_size 是奇数，补 2 字节 padding
 *   try_item tries[tries_size] {          ← 每个 8 字节
 *     u32 start_addr      (指令单位)
 *     u16 insn_count      (指令单位)
 *     u16 handler_off     (相对 encoded_catch_handler_list 起点的字节偏移)
 *   }
 *   encoded_catch_handler_list {
 *     uleb128 size
 *     encoded_catch_handler handlers[size] {
 *       sleb128 size                    ← 正数=有 catch_all，负数=无
 *       encoded_type_addr_pair pairs[|size|] {
 *         uleb128 type_idx              ← **要改的**
 *         uleb128 addr                  (指令单位)
 *       }
 *       // size <= 0 时跟一个 catch_all_addr
 *       uleb128 catch_all_addr
 *     }
 *   }
 * }
 * ```
 *
 * ## 为什么必须改
 *
 * `type_idx` 指向 `type_ids`，而池重排后 type 下标变了。
 * 不改的表现是 try/catch 捕获到**完全无关的异常类型**——
 * 实测 `catch (IllegalAccessException)` 变成 `catch (java.lang.Float)`。
 * 这种错误不会让 dex 非法，但会彻底改变程序行为：本该被捕获的异常逃逸，不该捕获的反而被吞掉。
 *
 * ## 长度会变
 *
 * `type_idx` 是 uleb128，重映射后可能变长 1 字节，导致整个 handler 长度变化、
 * 进而 `handler_off` 全部失效。所以必须**整体重编码后追加**，
 * 并把 `code_item.tries` 的偏移指过去 —— 这就是为什么整个 code_item 都要重写。
 */
internal object TryBlockRemapper {

    /**
     * 解析并重写一个 code_item 的 tries 部分。
     *
     * @param data 原 dex
     * @param codeOff 该 code_item 的偏移
     * @param typeMap 旧 type_idx → 新 type_idx
     * @return 重写后的 (try_item 数组字节, handler_list 字节)；无 try 时返回 null
     */
    fun remap(data: ByteArray, codeOff: Int, typeMap: IntArray): Result? {
        val buf = DexBuffer(data)
        val triesSize = buf.u16(codeOff + DexFormat.CI_TRIES_SIZE)
        if (triesSize == 0) return null

        val insnsSize = buf.u32AsInt(codeOff + DexFormat.CI_INSNS_SIZE, "insns_size")
        val insnsStart = codeOff + DexFormat.CI_INSNS
        var p = insnsStart + insnsSize * 2
        // tries 必须 4 字节对齐：指令区结束时若未对齐，补 2 字节
        if ((insnsSize and 1) != 0) p += 2

        val triesStart = p
        val handlersStart = triesStart + triesSize * 8
        if (handlersStart > data.size) return null

        // 先重写 handler_list，记下「旧 handler_off → 新 handler_off」
        val (newHandlerBytes, handlerOffsetMap) = rewriteHandlers(data, handlersStart, typeMap)

        // 再重写 try_item 数组（替换 handler_off）
        val out = GrowableBuffer(triesSize * 8)
        repeat(triesSize) { i ->
            val o = triesStart + i * 8
            // ⚠️ try_item 的字段偏移：start_addr(u32) @+0，insn_count(u16) @+4，handler_off(u16) @+6。
            // 曾把 insn_count 读在 +2、handler_off 读在 +4，结果 insn_count 恒为 0、
            // handler_off 拿到的是 insn_count 的值 —— 产物里 catch 全错位。
            val startAddr = buf.u32AsInt(o, "start_addr")
            val insnCount = buf.u16(o + 4)
            val oldHandlerOff = buf.u16(o + 6)
            out.u32(startAddr.toLong())
            out.u16(insnCount)
            val newOff = handlerOffsetMap[oldHandlerOff] ?: oldHandlerOff
            require(newOff <= 0xFFFF) { "handler_off 超出 u16：$newOff" }
            out.u16(newOff)
        }

        return Result(out.toByteArray(), newHandlerBytes)
    }

    data class Result(
        /** 重写后的 try_item 数组（每项 8 字节） */
        val tries: ByteArray,
        /** 重写后的 encoded_catch_handler_list */
        val handlers: ByteArray,
    )

    /**
     * 重写 `encoded_catch_handler_list`。
     *
     * @return (新字节, 旧相对偏移 → 新相对偏移)
     */
    private fun rewriteHandlers(
        data: ByteArray,
        listStart: Int,
        typeMap: IntArray,
    ): Pair<ByteArray, Map<Int, Int>> {
        var p = listStart
        val (count, n0) = Leb128.readUleb128(data, p)
        p += n0

        val out = mutableListOf<Byte>()
        Leb128.writeUleb128(out, count)
        // ⚠️ handler_off 是相对 encoded_catch_handler_list **起点**（含 size 字段）的偏移。
        // 写入第一个 handler 时 out.size 已包含 size 字段，所以直接用 out.size 就是正确偏移。
        // 曾误以为是「相对 handler 数据起点」，导致 jadx 报
        // `Catch handler not found by byte offset: 6`。

        val offsetMap = HashMap<Int, Int>()
        repeat(count.toInt()) {
            // 这个 handler 的旧相对偏移（相对 listStart） → 新相对偏移
            offsetMap[(p - listStart)] = out.size

            val (rawSize, n1) = Leb128.readSleb128(data, p)
            p += n1
            Leb128.writeSleb128(out, rawSize)

            val pairCount = kotlin.math.abs(rawSize).toInt()
            repeat(pairCount) {
                val (oldType, n2) = Leb128.readUleb128(data, p)
                p += n2
                val newType = typeMap[oldType.toInt()]
                Leb128.writeUleb128(out, newType.toLong())

                val (addr, n3) = Leb128.readUleb128(data, p)
                p += n3
                Leb128.writeUleb128(out, addr)
            }
            // size <= 0 表示带 catch_all
            if (rawSize <= 0) {
                val (addr, n4) = Leb128.readUleb128(data, p)
                p += n4
                Leb128.writeUleb128(out, addr)
            }
        }
        return out.toByteArray() to offsetMap
    }
}

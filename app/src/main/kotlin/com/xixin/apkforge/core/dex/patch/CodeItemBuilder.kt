package com.xixin.apkforge.core.dex.patch

/**
 * 生成新的 `code_item`。
 *
 * ```
 * code_item {
 *   u16 registers_size
 *   u16 ins_size          // 入参（含 this）占的寄存器数
 *   u16 outs_size         // 本方法调用别的方法时需要的最大参数寄存器数
 *   u16 tries_size        // try/catch 块个数
 *   u32 debug_info_off    // 0 表示无调试信息
 *   u32 insns_size        // 指令个数（单位：16 位字，不是字节！）
 *   u16 insns[insns_size]
 *   // tries 与 handlers 仅当 tries_size > 0 时跟在后面，且 tries 需要 4 字节对齐
 * }
 * ```
 *
 * 【为什么不复用原 code_item 的 debug_info】注入点在最前面，插入指令会让
 * **所有**调试信息里的指令偏移失效（它们都是相对指令序号记录的）。
 * 逐条重算 debug info 需要实现完整的 debug_info_item 状态机，
 * 而收益只是「崩溃时行号准确」。这里选择写 `debug_info_off = 0`：
 *
 * - 对**注入这个方法**来说，代价是它自己的行号信息丢失（栈里显示 Unknown Source）
 * - 换来的是**结构绝对正确**，不会因为 debug 项偏移错位而让 ART 拒收
 *
 * 这是可逆的取舍：想加回来时，只需把原 debug_info 也重写一份追加到末尾，
 * 那时要处理的是「每条指令偏移 +N」的平移，不需要重写整个状态机。
 */
internal object CodeItemBuilder {

    /**
     * 构建一个不含 try/catch、不含 debug info 的 code_item 字节。
     *
     * @param registersSize 寄存器总数
     * @param insSize 入参寄存器数（实例方法 = 参数个数 + 1）
     * @param outsSize 出参寄存器数上限（本方法要调别的带参方法时需足够大）
     * @param insns 已经编码好的 16 位指令字序列。这里刻意收 `ShortArray` 而非
     *              指令对象：编码逻辑属于 [DexCallInjector] 那一层，
     *              本包只负责「把给定的指令字写进合法的 code_item」
     */
    /**
     * 组装一个 code_item。
     *
     * @param debugInfoOff 原方法的 `debug_info_off`。**必须原样保留**：
     *        写成 0 会丢掉行号与局部变量表，崩溃栈里的行号全变成 `??`，
     *        而且 debug_info 段里会多出一个没人引用的孤儿项。
     */
    fun build(
        registersSize: Int,
        insSize: Int,
        outsSize: Int,
        insns: ShortArray,
        debugInfoOff: Long = 0L,
    ): ByteArray {
        require(registersSize in 0..0xFFFF) { "registers_size 越界：$registersSize" }
        require(insSize in 0..registersSize) { "ins_size($insSize) 不能大于 registers_size($registersSize)" }
        require(outsSize in 0..0xFFFF) { "outs_size 越界：$outsSize" }
        require(insns.size <= 0x7FFFFFFF) { "insns 过长：${insns.size}" }

        val out = GrowableBuffer(DexFormat.CODE_ITEM_HEADER + insns.size * 2 + 8)
        out.u16(registersSize)
        out.u16(insSize)
        out.u16(outsSize)
        out.u16(0)                 // tries_size = 0（带 try 的方法由调用方拒绝）
        out.u32(debugInfoOff)      // debug_info_off：保留原值
        out.u32(insns.size.toLong())  // insns_size 单位是 16 位字
        insns.forEach { out.u16(it.toInt() and 0xFFFF) }
        return out.toByteArray()
    }

    /**
     * 一个 code_item 在文件里占的字节数（含 4 字节对齐填充）。
     *
     * code_item 必须是 4 字节对齐的，所以调用方在追加前要用这个值确认
     * 自己的位置计算没漏掉填充。tries_size = 0 时，指令区之后不需要额外对齐。
     */
    fun byteLength(insnsCount: Int): Int = DexFormat.CODE_ITEM_HEADER + insnsCount * 2

    /** 从原始字节里读出某个已有 code_item 的指令个数（用于「原样搬运」时算长度）。 */
    fun insnsSizeOf(data: ByteArray, codeOff: Int): Int {
        val buf = DexBuffer(data)
        return buf.u32AsInt(codeOff + DexFormat.CI_INSNS_SIZE, "insns_size")
    }

    /** 读出某个已有 code_item 的完整字节（含指令，不含 tries——本包不处理带 try 的方法）。 */
    fun rawCodeOf(data: ByteArray, codeOff: Int): ByteArray {
        val buf = DexBuffer(data)
        val insnsSize = buf.u32AsInt(codeOff + DexFormat.CI_INSNS_SIZE, "insns_size")
        val triesSize = buf.u16(codeOff + DexFormat.CI_TRIES_SIZE)
        require(triesSize == 0) {
            "目标方法带 $triesSize 个 try/catch 块，当前增量写入路径暂不支持。" +
                "请改用全量重写（DexCallInjector）"
        }
        val len = byteLength(insnsSize)
        return buf.bytes(codeOff, len)
    }
}

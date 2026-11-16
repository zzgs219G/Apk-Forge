package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.dex.SmaliCallParser

/**
 * 把一条 smali `invoke` 调用编码成 dex 的 16 位指令字序列。
 *
 * 【为什么需要单独一层】`DexCallInjector` 用 dexlib2 的
 * `ImmutableInstruction35c` / `ImmutableInstruction3rc` 对象，
 * 由 `DexPool` 负责序列化——拿不到原始的字。而增量写入器只吃 `ShortArray`。
 * 两边的「指令语义」必须一致，所以编码规则集中写在这里，并逐条单测。
 *
 * ## 两种编码格式
 *
 * ```
 * 35c（普通形式，最多 5 个寄存器，每个 4 bit，寄存器号 <= v15）
 *   unit0 = op | (G << 12) | (A << 8)     A = 寄存器个数, G = 第 5 个寄存器的高 4 位
 *   unit1 = method_idx（u16）
 *   unit2 = C | (D << 4) | (E << 8) | (F << 12)
 *
 * 3rc（/range 形式，寄存器号 16 bit，要求一段连续区间）
 *   unit0 = op | (AA << 8)                AA = 寄存器个数（最多 255）
 *   unit1 = BBBB（起始寄存器号，u16）
 *   unit2 = method_idx（u16）
 * ```
 *
 * 【为什么寄存器必须连续才能用 3rc】3rc 只记「起点 + 个数」，
 * 它隐含了 `[start, start+count)` 的连续区间。寄存器不连续时无法表达，
 * 必须报错而不是勉强产出非法 dex。
 */
internal object InstructionEncoder {

    /** smali 的 invoke 类型 → 35c 形态的 opcode。 */
    private fun opcode35c(kind: SmaliCallParser.Kind): Int = when (kind) {
        SmaliCallParser.Kind.VIRTUAL -> 0x6E
        SmaliCallParser.Kind.SUPER -> 0x6F
        SmaliCallParser.Kind.DIRECT -> 0x70
        SmaliCallParser.Kind.STATIC -> 0x71
        SmaliCallParser.Kind.INTERFACE -> 0x72
    }

    /** smali 的 invoke 类型 → 3rc（/range）形态的 opcode。 */
    private fun opcode3rc(kind: SmaliCallParser.Kind): Int = when (kind) {
        SmaliCallParser.Kind.VIRTUAL -> 0x74
        SmaliCallParser.Kind.SUPER -> 0x75
        SmaliCallParser.Kind.DIRECT -> 0x76
        SmaliCallParser.Kind.STATIC -> 0x77
        SmaliCallParser.Kind.INTERFACE -> 0x78
    }

    sealed interface Result {
        data class Ok(
            val insns: ShortArray,
            /** 是否用了 /range 形态（用于回报 warning） */
            val usedRange: Boolean,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * 编码一条调用。
     *
     * @param call 解析好的 smali 调用
     * @param methodIdx 被调用方法在**本 dex** `method_ids` 表里的下标。
     *                  增量写入不能新增池条目，所以这个下标必须由调用方
     *                  事先在本 dex 里查到；查不到就该走全量路径
     * @param registerCount 目标方法的 `registers_size`
     * @param insSize 目标方法的 `ins_size`（参数寄存器个数，实例方法含 this）
     */
    fun encode(
        call: SmaliCallParser.Call,
        methodIdx: Int,
        registerCount: Int,
        insSize: Int,
    ): Result {
        if (methodIdx !in 0..0xFFFF) {
            return Result.Failed("method_idx 超出 u16：$methodIdx")
        }

        // p/v → 物理寄存器号
        val phys = mutableListOf<Int>()
        for (r in call.registers) {
            val n = resolveRegister(r, registerCount, insSize)
                ?: return Result.Failed(
                    "寄存器 $r 超出范围（registers_size=$registerCount, ins_size=$insSize）：${call.raw}"
                )
            phys += n
        }

        val fits35c = phys.size <= 5 && phys.all { it <= 15 }
        val contiguous = phys.isEmpty() || (phys.max() - phys.min() + 1 == phys.size)

        return if (fits35c) {
            // ⚠️ 35c 的第一个 16 位字：`op | A<<12 | G<<8`。
            // 高字节拆成两半 —— **高 4 位是寄存器个数 A，低 4 位是第 5 个寄存器 G**。
            // 曾写成 `phys.size shl 8`，那会把个数塞进 G 的位置、A 恒为 0，
            // 结果是 `invoke-static {}`（空参数列表）调用一个需要 1 个参数的方法，
            // 运行时报 `expected 0 argument registers, method signature has 1 or more`。
            // 这个 bug 在 dex 结构校验（dex2oat --verify）里查不出来，只有真机运行才炸。
            val u0 = opcode35c(call.kind) or (phys.size shl 12) or (phys.getOrElse(4) { 0 } shl 8)
            val u2 = phys.getOrElse(0) { 0 } or
                (phys.getOrElse(1) { 0 } shl 4) or
                (phys.getOrElse(2) { 0 } shl 8) or
                (phys.getOrElse(3) { 0 } shl 12)
            Result.Ok(
                shortArrayOf(u0.toShort(), methodIdx.toShort(), u2.toShort()),
                usedRange = false,
            )
        } else {
            if (!contiguous) {
                return Result.Failed(
                    "寄存器需要 /range 形态但编号不连续（${phys.joinToString()}）：" +
                        "dex 的 range 指令要求寄存器连续，请调整调用：${call.raw}"
                )
            }
            if (phys.size > 0xFF) {
                return Result.Failed("/range 形态最多 255 个寄存器，实际 ${phys.size}")
            }
            val start = phys.min()
            if (start > 0xFFFF) {
                return Result.Failed("起始寄存器超出 u16：$start")
            }
            val u0 = opcode3rc(call.kind) or (phys.size shl 8)
            Result.Ok(
                shortArrayOf(u0.toShort(), start.toShort(), methodIdx.toShort()),
                usedRange = true,
            )
        }
    }

    /**
     * `p0` / `v1` → 物理寄存器号。
     *
     * ```
     * p_i = registers_size - ins_size + i    （i 必须 < ins_size）
     * v_i = i                                （i 必须 < registers_size）
     * ```
     *
     * 注意 `p` 的基准是 `ins_size`（参数个数 + this），不是 `register_count`。
     * 这两个数在方法有其他局部变量时并不相等。
     */
    fun resolveRegister(text: String, registerCount: Int, insSize: Int): Int? {
        val t = text.trim()
        Regex("""^p(\d+)$""").matchEntire(t)?.let {
            val i = it.groupValues[1].toInt()
            return if (i < insSize) registerCount - insSize + i else null
        }
        Regex("""^v(\d+)$""").matchEntire(t)?.let {
            val i = it.groupValues[1].toInt()
            return if (i < registerCount) i else null
        }
        return null
    }

    /** 一条指令占几个 16 位字。只需覆盖 invoke 与常见形态。 */
    fun width(unit0: Int): Int = when (unit0 and 0xFF) {
        0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F -> 2
        0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C,
        0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78,
        -> 3
        0x18 -> 5
        else -> 1
    }
}

package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes

/**
 * 按 dex **自己的版本头**取 `Opcodes`，而不是用 [Opcodes.getDefault]。
 *
 * 【为什么必须有这个】`Opcodes.getDefault()` 是 api=20，也就是 dex 035。
 * 拿它读写一个 039 的 dex，写出来的文件版本头会**悄悄降级成 035**——
 * 文件结构合法、dexlib2 也能读回来，但声明成了「我不含 039 特性」。
 *
 * 这不是理论风险：`docs/项目介绍.md` 里点名批评过 apktool 路线的同一个坑
 * （「dex 版本头被写回 035，导致 Compose 包 ICCE 闪退」）。本工程原本
 * 在 `DexCallInjector` / `PayloadBaker` 里犯的是同一个错，只是症状更隐蔽：
 * 宿主用的是 039-only 指令（`invoke-custom`、method handle、接口私有方法）时，
 * 034/035 的校验器会拒收整个 dex。
 *
 * 【为什么不干脆统一写成 039】宿主的 minSdk 可能低到 21。把 035 的 dex
 * 升成 039 虽然通常无害（039 是超集），但会给老设备多一层不确定性；
 * 而「保持原样」是零风险的选择——我们只改了几条指令，没有任何理由动版本号。
 *
 * 反向验证：039 的 dex 用 `forDexVersion(39)` 解析后用 `DexPool` 重写，
 * 版本头仍是 039（`DexVersionTest` 钉住）。
 */
object DexOpcodes {

    /** dex 文件头的魔数：`dex\n`，随后 3 位 ASCII 版本号 + NUL。 */
    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

    /**
     * 从字节里读 dex 版本号（35 / 37 / 38 / 39 / 40 …）。
     *
     * 返回 null 表示「认不出来」——不是 dex、太短、或版本位不是三位数字。
     * 调用方应当退回 [Opcodes.getDefault]，而不是猜一个。
     */
    fun dexVersion(bytes: ByteArray): Int? {
        if (bytes.size < 8) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        // 版本位是 3 位 ASCII 数字 + NUL。用 US_ASCII 解，不用 String 默认字符集：
        // 后者在非 ASCII 默认字符集的环境下会解出乱七八糟的东西。
        val digits = String(bytes, 4, 3, Charsets.US_ASCII)
        if (!digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()
    }

    /**
     * 取用于**解析与重写**该 dex 的 `Opcodes`。
     *
     * 解析时用的 api 会决定 dexlib2 认哪些指令；写回时同一个 api 决定版本头。
     * 两者必须是同一个值，否则会写出「头是 035、内容含 039 指令」的非法文件。
     */
    fun forBytes(bytes: ByteArray): Opcodes {
        val v = dexVersion(bytes) ?: return Opcodes.getDefault()
        // forDexVersion 对不认识的版本抛 RuntimeException（不是返回 null），
        // 未来出现 041 之后的版本时不能让整个注入流程崩在这里。
        return runCatching { Opcodes.forDexVersion(v) }.getOrDefault(Opcodes.getDefault())
    }

    /** 人类可读的版本号，用于日志（`039` → `39`）。 */
    fun label(bytes: ByteArray): String = dexVersion(bytes)?.toString() ?: "未知"
}

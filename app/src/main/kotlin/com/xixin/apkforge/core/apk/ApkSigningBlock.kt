package com.xixin.apkforge.core.apk

import java.io.File
import java.io.RandomAccessFile

/**
 * APK Signing Block（v2/v3）的原样搬运。
 *
 * ## 为什么需要它
 *
 * 去签名校验的 native 层把 `open(base.apk)` 重定向到 `origin.apk`，让目标
 * App 的 so 从里头读签名块拿证书。所以 **origin.apk 必须带签名块**，
 * 否则 native 层读到空 → 重定向等于没做。
 *
 * 而 `java.util.zip` 写出来的包**不含**签名块（它不是条目，是中央目录之前
 * 的一段独立数据）。中间包是 `ZipOutputStream` 写的 → 没有签名块。
 * 所以必须从**原始输入 APK** 里把它抠出来，再塞进中间包。
 *
 * ## 顺序铁律（云端 2026-11-24 实测）
 *
 * ```
 * rebuild 中间包 → zipalign → 注入签名块 → 数据复用
 *                              ↑ 必须在 zipalign 之后
 * ```
 *
 * 反过来的话（先注入再对齐），`zipalign` 会重写整个 ZIP、丢弃它不认识的
 * Signing Block，注入白做。本模块不做 zipalign（[ZipReuse] 自己保证对齐），
 * 但仍必须在**复用之前**注入 —— 因为 origin.apk 的数据区就是中间包字节。
 *
 * ## 签名块里的签名对产物无效，这是预期
 *
 * 块里的签名是对**原包内容**签的，塞进改过的包不可能通过系统校验
 * （装机时本来就要重签）。保留它只为满足「App/native 从自己 APK 里读证书」
 * 这条**运行期**路径 —— 证书 DER 与包内容无关，取到的值是正确的。
 *
 * ## 只支持 ZIP32
 *
 * 产物要 < 4GB。APK 达不到，不做 ZIP64 支持（多一层复杂度换不来任何东西）。
 */
object ApkSigningBlock {

    private const val MAGIC = "APK Sig Block 42"
    private const val EOCD_SIG = 0x06054b50
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val BLOCK_FOOTER = 24   // size(8) + magic(16)

    class SignBlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 取出签名块的原始字节（含头尾的 8 字节 size 字段与 16 字节 magic）。
     *
     * @return 签名块字节；源包没有签名块时返回 null（v1-only 或未签名包）
     */
    fun extract(apk: File): ByteArray? {
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            if (len < EOCD_SIZE + BLOCK_FOOTER) return null

            val eocd = findEocd(raf, len) ?: return null
            val cdOffset = readU32(raf, eocd + 16)
            if (cdOffset < 32 || cdOffset >= len) return null

            // 签名块紧邻中央目录：末尾 16 字节是固定魔数
            val magic = ByteArray(16)
            raf.seek(cdOffset - 16)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) return null

            // size_total 位于 magic 之前 8 字节；块起点 = cdOffset - 8 - size_total
            val sizeTotal = readU64(raf, cdOffset - 24)
            if (sizeTotal < 0 || sizeTotal + 8 > cdOffset) {
                throw SignBlockException("签名块大小自相矛盾: size=$sizeTotal, cdOffset=$cdOffset")
            }
            val start = cdOffset - 8 - sizeTotal
            val total = (cdOffset - start).toInt()
            val out = ByteArray(total)
            raf.seek(start)
            raf.readFully(out)
            return out
        }
    }

    /**
     * 把 [block] 插到 [apk] 的中央目录之前，并修正 EOCD 里的 CD offset。
     *
     * 幂等：已经有签名块时直接返回 false（不重复插入）。
     *
     * @return 是否发生了插入
     */
    fun inject(apk: File, block: ByteArray): Boolean {
        if (block.size < BLOCK_FOOTER) {
            throw SignBlockException("签名块过短（${block.size} B），不是合法结构")
        }
        RandomAccessFile(apk, "rw").use { raf ->
            val len = raf.length()
            if (len < EOCD_SIZE) throw SignBlockException("文件太小，不是合法 ZIP")

            val eocd = findEocd(raf, len) ?: throw SignBlockException("找不到 EOCD")
            val cdOffset = readU32(raf, eocd + 16)
            if (cdOffset < 32) throw SignBlockException("中央目录偏移异常: $cdOffset")

            // 幂等：已经有签名块就不动
            val magic = ByteArray(16)
            raf.seek(cdOffset - 16)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) == MAGIC) return false

            val newCdOffset = cdOffset + block.size
            if (newCdOffset > 0xFFFFFFFFL) {
                throw SignBlockException("注入后 CD offset 超过 32 位，不支持 >4GB 包")
            }

            // 布局：[条目...][中央目录][EOCD]
            // → 插入后：[条目...][签名块][中央目录][EOCD]
            // 做法：把 [cdOffset, eof) 整段后移 block.size，再回填签名块与 EOCD 的 offset。
            val tailLen = (len - cdOffset).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(cdOffset)
            raf.readFully(tail)

            raf.seek(cdOffset)
            raf.write(block)
            raf.write(tail)

            // tail 里的 EOCD 位置相对 cdOffset 是 eocd - cdOffset，
            // 落地后它的绝对位置变成 cdOffset + block.size + 同样的相对量
            val newEocdAbs = eocd + block.size
            raf.seek(newEocdAbs + 16)
            writeU32(raf, newCdOffset)
            return true
        }
    }

    /** 判断是否带签名块（只读尾部，不解内容）。 */
    fun hasBlock(apk: File): Boolean = try {
        extract(apk) != null
    } catch (_: Exception) {
        false
    }

    // ── 内部 ─────────────────────────────────────────────────

    /** 从尾部往前找 EOCD，并校验注释长度与文件尾一致（防误命中）。 */
    private fun findEocd(raf: RandomAccessFile, len: Long): Long? {
        val window = minOf(len, EOCD_SIZE.toLong() + MAX_COMMENT).toInt()
        val tail = ByteArray(window)
        raf.seek(len - window)
        raf.readFully(tail)
        for (i in window - EOCD_SIZE downTo 0) {
            val sig = (tail[i].toInt() and 0xFF) or
                ((tail[i + 1].toInt() and 0xFF) shl 8) or
                ((tail[i + 2].toInt() and 0xFF) shl 16) or
                ((tail[i + 3].toInt() and 0xFF) shl 24)
            if (sig == EOCD_SIG) {
                val commentLen = (tail[i + 20].toInt() and 0xFF) or
                    ((tail[i + 21].toInt() and 0xFF) shl 8)
                if (i + EOCD_SIZE + commentLen == window) return len - window + i
            }
        }
        return null
    }

    private fun readU32(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        val b = ByteArray(4)
        raf.readFully(b)
        return ((b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24))
    }

    private fun readU64(raf: RandomAccessFile, at: Long): Long {
        raf.seek(at)
        val b = ByteArray(8)
        raf.readFully(b)
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[i].toLong() and 0xFF)
        return r
    }

    private fun writeU32(raf: RandomAccessFile, v: Long) {
        raf.write(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            )
        )
    }
}

package com.xixin.apkforge.core.uncheck

import java.io.File
import java.io.RandomAccessFile

/**
 * CertExtractor —— 从 APK 的 APK Signing Block 里提取第一张签名证书 DER。
 *
 * ## 为什么要它
 *
 * 去签名校验的载荷必须知道**原版证书**长什么样 —— 它要伪造出一个
 * `Signature` 让目标 App 的哈希比对通过。
 *
 * ## 为什么不用 apksigner / keytool
 *
 * 那两个都要外部工具。我们只需要**一个字段**：证书 DER 字节。
 * APK Signing Block 的格式是公开且稳定的，纯 Java 解析即可，
 * 顺带让 ApkForge 保持"零外部依赖"。
 *
 * ## 格式（AOSP `ApkSigningBlockUtils`）
 *
 * ```
 * [size_payload 8B][ID-value pairs...][size_total 8B]["APK Sig Block 42" 16B]
 *   块起点 = cdOffset - size_total - 8
 * ```
 *
 * ID-value pair：`[pair_len 8B][id 4B][value (pair_len-4) B]`
 *   · v3 块 id = 0xF05368C0（优先）
 *   · v2 块 id = 0x7109871A
 *
 * value 内是 length-prefixed 嵌套结构：
 * ```
 * signers_len 4B
 *   signer_len 4B
 *     signed_data_len 4B
 *       digests_len 4B + digests
 *       certificates_len 4B + [cert_len 4B + cert DER]...
 * ```
 *
 * ## 只支持 v2/v3
 *
 * v1（JAR 签名）的证书在 META-INF 下的 .RSA 文件里，是 PKCS#7 结构，
 * 解析它需要 ASN.1 库。而 v1-only 的包现在几乎绝迹（Android 7+ 强制 v2），
 * 遇到时直接报错并提示用户，不做半吊子兼容。
 */
object CertExtractor {

    private const val SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID = 0x7109871A
    private const val V3_BLOCK_ID = 0xF05368C0.toInt()
    private const val EOCD_SIG = 0x06054b50
    private const val EOCD_SIZE = 22

    class CertException(message: String) : Exception(message)

    /** 提取结果。 */
    data class Result(
        /** 证书 DER（直接喂给 `Signature` 构造器） */
        val certDer: ByteArray,
        /** 来自哪个签名方案：2 或 3 */
        val schemeVersion: Int,
    )

    /**
     * 从 APK 文件提取第一张签名证书。
     *
     * @throws CertException 不是合法 APK / 没有 v2+v3 签名 / 结构异常
     */
    fun extract(apk: File): Result {
        if (!apk.isFile) throw CertException("文件不存在: ${apk.absolutePath}")
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            if (len < EOCD_SIZE + 16) throw CertException("文件太小，不是合法 APK")

            // 1) 找 EOCD（从尾部往前，注释最长 65535）
            val window = minOf(len, (EOCD_SIZE + 0xFFFF).toLong()).toInt()
            val tail = ByteArray(window)
            raf.seek(len - window)
            raf.readFully(tail)
            var eocd = -1
            for (i in window - EOCD_SIZE downTo 0) {
                if (le32(tail, i) == EOCD_SIG) {
                    // 校验注释长度字段与文件尾一致，防误命中
                    val commentLen = le16(tail, i + 20)
                    if (i + EOCD_SIZE + commentLen == window) {
                        eocd = i
                        break
                    }
                }
            }
            if (eocd < 0) throw CertException("找不到 EOCD（不是合法 ZIP/APK）")

            val cdOffset = le32(tail, eocd + 16)
            if (cdOffset <= 0 || cdOffset >= len) {
                throw CertException("central directory 偏移异常: $cdOffset")
            }
            if (cdOffset < 32) throw CertException("文件过小，没有 Signing Block")

            // 2) Signing Block 紧邻 central directory
            val magicBytes = ByteArray(16)
            raf.seek((cdOffset - 16).toLong())
            raf.readFully(magicBytes)
            if (String(magicBytes, Charsets.US_ASCII) != SIG_BLOCK_MAGIC) {
                throw CertException(
                    "没有 APK Signing Block（无 v2/v3 签名）。\n" +
                        "  v1-only 的包无法提取证书 —— 请先用 apksigner 以 v2+ 重签一次。"
                )
            }

            val sizeTotalBytes = ByteArray(8)
            raf.seek((cdOffset - 24).toLong())
            raf.readFully(sizeTotalBytes)
            val sizeTotal = le64(sizeTotalBytes, 0)
            if (sizeTotal + 8 > cdOffset.toLong()) {
                throw CertException("Signing Block 大小异常: $sizeTotal")
            }
            val blockStart = cdOffset.toLong() - 8 - sizeTotal

            // 3) 遍历 ID-value pairs，v3 优先
            var pos = blockStart + 8
            val pairsEnd = cdOffset.toLong() - 24
            var v2Value: ByteArray? = null
            var v3Value: ByteArray? = null
            while (pos + 12 <= pairsEnd) {
                val head = ByteArray(12)
                raf.seek(pos)
                raf.readFully(head)
                val pairLen = le64(head, 0)
                val id = le32(head, 8)
                if (pairLen < 4 || pos + 8 + pairLen > pairsEnd) break
                if (id == V3_BLOCK_ID || id == V2_BLOCK_ID) {
                    val value = ByteArray((pairLen - 4).toInt())
                    raf.seek(pos + 12)
                    raf.readFully(value)
                    if (id == V3_BLOCK_ID) {
                        v3Value = value
                        break          // v3 优先，找到就不再看
                    } else {
                        v2Value = value
                    }
                }
                pos += (8 + pairLen).toInt()
            }

            val (value, scheme) = when {
                v3Value != null -> v3Value to 3
                v2Value != null -> v2Value to 2
                else -> throw CertException(
                    "Signing Block 里没有 v2/v3 签名数据（可能是特殊签名工具生成的包）"
                )
            }

            val der = firstCertOf(value)
                ?: throw CertException("v$scheme 签名块里取不到证书")
            return Result(der, scheme)
        }
    }

    /** 从 v2/v3 块 value 里取第一张证书 DER。 */
    private fun firstCertOf(value: ByteArray): ByteArray? {
        var p = 0

        fun readLenPrefixed(): ByteArray? {
            if (p + 4 > value.size) return null
            val n = le32(value, p)
            p += 4
            if (n < 0 || p + n > value.size) return null
            val out = value.copyOfRange(p, p + n)
            p += n
            return out
        }

        val signers = readLenPrefixed() ?: return null
        p = 0
        val v = signers
        var q = 0

        fun readFrom(src: ByteArray): ByteArray? {
            if (q + 4 > src.size) return null
            val n = le32(src, q)
            q += 4
            if (n < 0 || q + n > src.size) return null
            val out = src.copyOfRange(q, q + n)
            q += n
            return out
        }

        val firstSigner = readFrom(v) ?: return null
        q = 0
        val signedData = readFrom(firstSigner) ?: return null
        q = 0
        readFrom(signedData) ?: return null          // digests，跳过
        val certs = readFrom(signedData) ?: return null
        q = 0
        return readFrom(certs)                       // 第一张证书
    }

    // ── 小端读取 ─────────────────────────────────────────────
    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) {
            r = (r shl 8) or (b[o + i].toLong() and 0xFF)
        }
        return r
    }
}

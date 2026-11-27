package com.xixin.apkforge.core.apk

import com.xixin.apkforge.core.Bytes

import java.io.File
import java.io.RandomAccessFile

/**
 * ZIP 中央目录解析（只读，纯 JVM）。
 *
 * ## 为什么单独一个文件
 *
 * 数据复用（[com.xixin.apkforge.core.uncheck.ZipReuse]）需要**每个条目的
 * 完整元数据**：名字、压缩方式、CRC、压缩前后大小、local header 偏移。
 * `java.util.zip.ZipFile` 能给出这些，但它会按条目解压数据——复用场景要的是
 * 「原样搬 offset」，不能让它碰数据。
 *
 * 而 [ApkRepackager.LocalHeaderIndex] 只记了「数据偏移 + 是否 STORED」，
 * 对齐判据够用，复用不够。两者职责不同，故不合并：
 *
 * ```
 * ApkRepackager.LocalHeaderIndex  → 回答「这个条目原来对齐到几字节」
 * ZipCentralDirectory             → 回答「这个条目在包里的完整描述」
 * ```
 *
 * ## 为什么必须走中央目录，不能顺 local header 往前爬
 *
 * `ZipOutputStream` 写 DEFLATED 条目时用 data descriptor（local header 的
 * flag bit3 置位、csize 写 0，真实大小在数据之后）。顺 local header 链走，
 * `pos += csize` 会立刻算错位置 —— 这正是 [ApkRepackager] 早年对齐自检
 * **假绿**的原因（索引读到 0 条，于是「未对齐数 = 0」）。
 *
 * 中央目录里每个条目都写着完整 csize 与 local header 偏移，是唯一可靠来源。
 */
object ZipCentralDirectory {

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8

    private const val EOCD_SIG = 0x06054b50
    private const val CD_SIG = 0x02014b50
    private const val LFH_SIG = 0x04034b50
    private const val EOCD_SIZE = 22
    private const val LFH_SIZE = 30
    /** EOCD 之后最多跟 65535 字节注释 */
    private const val MAX_COMMENT = 0xFFFF

    /**
     * 一个条目的完整描述。
     *
     * [headerOffset] / [dataOffset] 都是**相对 zip 起点**的偏移
     * （已加上自解压前缀，可直接用于文件定位）。
     */
    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val headerOffset: Long,
        val dataOffset: Long,
    ) {
        val isStored: Boolean get() = method == METHOD_STORED
        val isDex: Boolean get() = Regex("""^classes\d*\.dex$""").matches(name)
    }

    /**
     * 整个包的布局。
     *
     * [cdOffset] 是中央目录在**文件里**的实际起点（含前缀修正）；
     * [prefix] 是自解压 / 前置数据的长度，正常 APK 为 0。
     */
    data class Layout(
        val entries: List<Entry>,
        val cdOffset: Long,
        val cdSize: Long,
        val eocdOffset: Long,
        val prefix: Long,
    ) {
        fun byName(name: String): Entry? = entries.firstOrNull { it.name == name }
        val names: List<String> get() = entries.map { it.name }
        fun dexNames(): List<String> = entries.filter { it.isDex }.map { it.name }
    }

    /**
     * 读取中央目录。不是合法 ZIP 时抛 [IllegalArgumentException]。
     *
     * 调用方若只想要「能用就用」，自行 `runCatching`——这个函数刻意
     * fail-fast：静默返回空布局会让下游产出残缺包而不自知。
     */
    fun read(apk: File): Layout {
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            if (len < EOCD_SIZE) throw IllegalArgumentException("文件太小，不是合法 ZIP: ${apk.name}")

            // 1) 从尾部找 EOCD，并校验注释长度与文件尾一致（防误命中）
            val window = minOf(len, EOCD_SIZE.toLong() + MAX_COMMENT).toInt()
            val tail = ByteArray(window)
            raf.seek(len - window)
            raf.readFully(tail)

            var eocdRel = -1
            for (i in window - EOCD_SIZE downTo 0) {
                if (Bytes.i32(tail, i) == EOCD_SIG) {
                    val commentLen = Bytes.u16(tail, i + 20)
                    if (i + EOCD_SIZE + commentLen == window) {
                        eocdRel = i
                        break
                    }
                }
            }
            if (eocdRel < 0) throw IllegalArgumentException("找不到 EOCD: ${apk.name}")

            val eocdAbs = len - window + eocdRel
            val cdSize = Bytes.u32(tail, eocdRel + 12)
            val cdOffsetRaw = Bytes.u32(tail, eocdRel + 16)
            if (cdSize == 0L || cdSize > Int.MAX_VALUE) {
                throw IllegalArgumentException("中央目录大小异常 ($cdSize): ${apk.name}")
            }

            // 自解压包：EOCD 记的 CD offset 相对 zip 起点，与实际文件位置差一个前缀。
            // 用「EOCD 位置 - CD 大小」反推真实起点，这是 zipalign / apksig 的判据。
            val cdAbsStart = eocdAbs - cdSize
            val prefix = cdAbsStart - cdOffsetRaw
            if (prefix < 0) throw IllegalArgumentException("中央目录偏移自相矛盾: ${apk.name}")

            // 2) 解析 CD 条目
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdAbsStart)
            raf.readFully(cd)

            val entries = ArrayList<Entry>()
            var p = 0
            while (p + 46 <= cd.size) {
                if (Bytes.i32(cd, p) != CD_SIG) break
                val method = Bytes.u16(cd, p + 10)
                val crc = Bytes.u32(cd, p + 16)
                val csize = Bytes.u32(cd, p + 20)
                val fsize = Bytes.u32(cd, p + 24)
                val nameLen = Bytes.u16(cd, p + 28)
                val extraLen = Bytes.u16(cd, p + 30)
                val commentLen = Bytes.u16(cd, p + 32)
                val headerOffset = Bytes.u32(cd, p + 42)
                if (p + 46 + nameLen > cd.size) break
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)

                // 回读 local header，只为拿它自己的 nameLen / extraLen
                // （local 与中央目录的 extraLen 可以不同，数据偏移必须以 local 为准）
                val lh = ByteArray(LFH_SIZE)
                raf.seek(prefix + headerOffset)
                raf.readFully(lh)
                if (Bytes.i32(lh, 0) != LFH_SIG) {
                    throw IllegalArgumentException("local header 非法 ($name): ${apk.name}")
                }
                val dataOffset = prefix + headerOffset + LFH_SIZE + Bytes.u16(lh, 26) + Bytes.u16(lh, 28)

                entries += Entry(
                    name = name,
                    method = method,
                    crc = crc,
                    compressedSize = csize,
                    size = fsize,
                    headerOffset = prefix + headerOffset,
                    dataOffset = dataOffset,
                )
                p += 46 + nameLen + extraLen + commentLen
            }

            return Layout(
                entries = entries,
                cdOffset = cdAbsStart,
                cdSize = cdSize,
                eocdOffset = eocdAbs,
                prefix = prefix,
            )
        }
    }

    /** 按 CD 记的 [Entry.dataOffset] / 大小读出条目原始（可能已压缩）字节。 */
    fun readRaw(apk: File, entry: Entry): ByteArray {
        if (entry.compressedSize > Int.MAX_VALUE) {
            throw IllegalArgumentException("条目过大，不支持一次读入: ${entry.name}")
        }
        val out = ByteArray(entry.compressedSize.toInt())
        RandomAccessFile(apk, "r").use { raf ->
            raf.seek(entry.dataOffset)
            raf.readFully(out)
        }
        return out
    }
}

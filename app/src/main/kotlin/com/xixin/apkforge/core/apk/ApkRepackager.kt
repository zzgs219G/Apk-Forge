package com.xixin.apkforge.core.apk

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 重打包：只做「替换 dex + 追加 payload dex」，其余条目**原样二进制拷贝**。
 *
 * 设计取舍：
 * - 不用 apkzlib：它的 `bytestorage` 包引用 `java.nio.file`（Android 上被裁剪），
 *   而这里只需要 zip 读写，`java.util.zip` 在 Android 上完全够用。
 * - 不用 apksig：目前不重签名（用户明确「先不搞重签名」）。
 * - **不碰 AndroidManifest.xml**：原样二进制搬运。任何「解码 Manifest 再写回」
 *   的路径都会产出系统不认的灰包（artifact-pipeline 报错四/五的教训）。
 * - 保留原 entry 的压缩方式与时间戳。
 * - **保留原 entry 的数据对齐**：见下方 [alignmentOf] 的长注释。
 */
object ApkRepackager {

    data class Stats(
        val totalEntries: Int,
        val replacedDex: String,
        val addedDex: String?,
        val originalSize: Long,
        val outputSize: Long,
        /**
         * 写完后复核出的「STORED 但数据偏移没落在它原有对齐上」的条目数。
         * 正常应为 0；非 0 说明产物会被 `zipalign -c` 判失败，
         * 装机或运行时可能出问题。**不静默吞掉**：Pipeline 会把它报成 warning。
         */
        val misalignedEntries: Int = 0,
        /**
         * 源包是否带 APK Signing Block（v2/v3 签名）。
         *
         * 重写 zip 后签名块必然失效——`java.util.zip` 不会把它写回去，
         * 写回去也没用（dex 变了，签名不再匹配）。所以这里只用来**如实告知**：
         * 产物是无签名包，任何设备都装不上，需要用户自己签。
         */
        val sourceHadSignature: Boolean = false,
    )

    /**
     * 把 [targetDexName] 替换成 [newDexBytes]；若 [payloadDexBytes] 非空，
     * 追加成下一个可用的 `classesN.dex`。
     */
    fun repack(
        sourceApk: File,
        outputApk: File,
        targetDexName: String,
        newDexBytes: ByteArray,
        payloadDexBytes: ByteArray?,
        /** 每写一个条目回调一次（index 从 1 开始，total 含目录条目）。用于进度与日志。 */
        onEntry: ((index: Int, total: Int, name: String) -> Unit)? = null,
    ): Stats {
        val zip = ZipFile(sourceApk)
        var entryCount = 0
        var addedName: String? = null
        // ZipFile.entries() 是 Enumeration，先物化成 List 才能同时拿到总数与逐个遍历
        val allEntries = zip.entries().toList()
        val existingNames = allEntries.map { it.name }.toSet()
        // ⚠️ 括号不能省：`a + if (c) 1 else 0 + n` 会被解析成 `a + if (c) 1 else (0 + n)`
        val total = allEntries.size + (if (payloadDexBytes != null) 1 else 0)
        // 源包里每个条目的**真实数据偏移**。java.util.zip 不暴露它，
        // 而「这个条目当初被对齐到几字节」只能从偏移里反推。
        val sourceLayout = LocalHeaderIndex.read(sourceApk)
        val payloadAlign = sourceLayout.maxDexAlignment()

        try {
            // 找下一个空闲的 classesN.dex 名字
            if (payloadDexBytes != null) {
                var n = 2
                while (existingNames.contains("classes$n.dex")) n++
                // classes.dex 存在时从 classes2 起；若原包只有 classes.dex，仍从 classes2 起
                addedName = "classes$n.dex"
            }

            outputApk.parentFile?.mkdirs()
            val counter = CountingOutputStream(outputApk.outputStream().buffered())
            ZipOutputStream(counter).use { out ->
                // 1) 原样拷贝所有条目，途中替换目标 dex
                for (entry in allEntries) {
                    if (entry.isDirectory) {
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        out.closeEntry()
                        entryCount++
                        onEntry?.invoke(entryCount, total, entry.name)
                        continue
                    }

                    val bytes: ByteArray = if (entry.name == targetDexName) {
                        newDexBytes
                    } else {
                        zip.getInputStream(entry).use { it.readBytesCompat() }
                    }

                    writeEntry(out, entry, bytes, sourceLayout.alignmentOf(entry.name), counter.count)
                    entryCount++
                    onEntry?.invoke(entryCount, total, entry.name)
                }

                // 2) 追加 payload dex
                if (payloadDexBytes != null && addedName != null) {
                    val e = ZipEntry(addedName).apply {
                        method = ZipEntry.DEFLATED
                        time = 0L   // 与 AGP 产物一致：固定时间戳，保证可复现
                    }
                    // 新增的 dex 没有「原有对齐」可抄，跟随包里已有 dex 的对齐
                    writeEntry(out, e, payloadDexBytes, payloadAlign, counter.count)
                    entryCount++
                    onEntry?.invoke(entryCount, total, addedName)
                }
            }
        } finally {
            zip.close()
        }

        // 写完自己复核一遍，不等用户拿 zipalign 才发现。
        // 注意比较的基准是**源包**的对齐要求——用产物自己的偏移反推必然自证成功。
        val outLayout = LocalHeaderIndex.read(outputApk)

        return Stats(
            totalEntries = entryCount,
            replacedDex = targetDexName,
            addedDex = addedName,
            originalSize = sourceApk.length(),
            outputSize = outputApk.length(),
            misalignedEntries = outLayout.countMisalignedAgainst(sourceLayout),
            sourceHadSignature = hasApkSignatureBlock(sourceApk),
        )
    }

    /**
     * 写一个条目。
     *
     * 【[wireOffset] 是干什么的】它是**这条目的 local header 即将写入的位置**，
     * 也就是当前输出流的字节数。数据偏移 = wireOffset + 30 + 名字长度 + extra 长度。
     * 对齐只能靠调 extra 长度来做，这是 zip 格式里唯一的自由度。
     *
     * 【为什么 extra 是裸 0 字节而不是合法 TLV】AGP 自己就是这么干的
     * （实测本工程产物：`classes2.dex` 的 extraLen=2、内容 `0000`），
     * `zipalign -c` 也认。硬凑一个 TLV 头反而会和对齐计算打架。
     */
    private fun writeEntry(
        out: ZipOutputStream,
        src: ZipEntry,
        data: ByteArray,
        align: Int,
        wireOffset: Long,
    ) {
        val nameLen = src.name.toByteArray(Charsets.UTF_8).size
        val e = ZipEntry(src.name).apply {
            time = src.time
            // 压缩方式跟随原条目：STORED 的别改成 DEFLATED
            method = if (src.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            if (method == ZipEntry.STORED) {
                size = data.size.toLong()
                val crc = CRC32().apply { update(data) }
                compressedSize = data.size.toLong()
                this.crc = crc.value
            }
        }
        if (e.method == ZipEntry.STORED) {
            // 先按 extraLen = 0 算出数据偏移，再看差多少补多少
            val base = wireOffset + LOCAL_HEADER_SIZE + nameLen
            val pad = paddingFor(base, align)
            if (pad > 0) e.extra = ByteArray(pad)   // 裸 0 填充，与 AGP 一致
        }
        out.putNextEntry(e)
        out.write(data)
        out.closeEntry()
    }

    /** 让 [base] + pad 落到 [align] 的整数倍上；[align] <= 1 表示不要求对齐。 */
    internal fun paddingFor(base: Long, align: Int): Int {
        if (align <= 1) return 0
        val mod = (base % align).toInt()
        return if (mod == 0) 0 else align - mod
    }

    private fun InputStream.readBytesCompat(): ByteArray {
        val buf = java.io.ByteArrayOutputStream(8192)
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    /** APK 内所有 `classes*.dex` 的条目名，按 classes.dex, classes2.dex, ... 排序。 */
    fun listDexNames(apk: File): List<String> {
        ZipFile(apk).use { zip ->
            return zip.entries().toList()
                .map { it.name }
                .filter { Regex("""^classes\d*\.dex$""").matches(it) }
                .sortedBy { name ->
                    val n = name.removePrefix("classes").removeSuffix(".dex")
                    if (n.isEmpty()) 1 else n.toIntOrNull() ?: Int.MAX_VALUE
                }
        }
    }

    fun readEntry(apk: File, name: String): ByteArray? {
        ZipFile(apk).use { zip ->
            val e = zip.getEntry(name) ?: return null
            return zip.getInputStream(e).use { it.readBytesCompat() }
        }
    }

    // ────────────────────────── 对齐 ──────────────────────────

    private const val LOCAL_HEADER_SIZE = 30
    private const val EOCD_SIZE = 22
    private const val EOCD_SIG = 0x06054b50
    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"

    /**
     * 判断 APK 是否带 v2/v3 的 APK Signing Block。
     *
     * 签名块位于中央目录之前，末尾 16 字节是固定魔数 `APK Sig Block 42`。
     * 只读文件尾部，不解签名内容——我们只是要如实告诉用户「产物没有签名」。
     */
    internal fun hasApkSignatureBlock(apk: File): Boolean = try {
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            if (len < EOCD_SIZE) {
                false
            } else {
                // 从尾部往前找 EOCD（注释最多 65535 字节）
                val window = minOf(len, EOCD_SIZE.toLong() + 0xFFFF).toInt()
                val tail = ByteArray(window)
                raf.seek(len - window)
                raf.readFully(tail)
                var eocd = -1
                for (i in window - EOCD_SIZE downTo 0) {
                    if (le32(tail, i) == EOCD_SIG) { eocd = i; break }
                }
                if (eocd < 0) {
                    false
                } else {
                    val cdOffset = le32u(tail, eocd + 16)
                    if (cdOffset < 16) {
                        false
                    } else {
                        raf.seek(cdOffset - 16)
                        val magic = ByteArray(16)
                        raf.readFully(magic)
                        String(magic, Charsets.US_ASCII) == APK_SIG_BLOCK_MAGIC
                    }
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le32u(b: ByteArray, at: Int): Long = le32(b, at).toLong() and 0xFFFFFFFFL

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /**
     * 源包里每个 STORED 条目的「原有对齐」。
     *
     * 【为什么不写死一张「哪些条目要几字节对齐」的表】
     * 因为那张表永远比现实少几项，而且漏项时不报错——正是 `踩坑记录` 坑 5
     * （白名单是负债）说的那类错。这里改成**从源包反推**：
     *
     * ```
     * resources.arsc  原始偏移 % 16384 == 0 → 它当初按页对齐
     *                 原始偏移 % 4     == 0 → 它当初按 4 字节对齐
     *                 都不沾              → 本来就没对齐，不要凭空发明
     * ```
     *
     * 好处是三种载体一次覆盖：`.arsc` 的 4 字节、`.so` 的页对齐（4KB 或 16KB
     * 取决于设备与打包器）、`.dex` 的 4 字节，不需要在这里枚举任何文件名。
     *
     * 【为什么这是必须的】`java.util.zip.ZipEntry` 只搬名字/时间/压缩方式，
     * **extra field 会被丢掉**——而 AGP 正是把对齐填充塞在 extra 里的。
     * 丢掉之后所有 STORED 条目的数据偏移都会前移，实测本工程 67MB 的包
     * 重打包后 78 个 STORED 条目不再 4 字节对齐，`resources.arsc` 从
     * off%4=0 变成 off%4=2，`lib/` 下的 `.so` 页对齐也一起没了。
     * `resources.arsc` 未对齐 + targetSdk≥30 是「装得上但资源读不出」；
     * `.so` 未页对齐在 16KB 页设备上直接不让装。
     */
    internal class LocalHeaderIndex private constructor(
        /** 条目名 → 原始数据偏移 */
        private val dataOffsets: Map<String, Long>,
        /** 条目名 → 是否 STORED */
        private val stored: Map<String, Boolean>,
    ) {
        /**
         * 该条目在源包里被对齐到几字节。返回 1 表示「原本就没对齐，别发明」。
         *
         * 判据是「偏移能被多大整除」，从大到小取第一个命中的：
         * 16KB（16KB 页设备上的 .so）→ 4KB（传统页 .so）→ 4（.arsc/.dex）。
         */
        fun alignmentOf(name: String): Int {
            if (stored[name] != true) return 1
            val off = dataOffsets[name] ?: return 1
            return when {
                off % 16384L == 0L -> 16384
                off % 4096L == 0L -> 4096
                off % 4L == 0L -> 4
                else -> 1
            }
        }

        /**
         * 包里已有 dex 的对齐（新增 payload dex 沿用它）。
         *
         * 若源包一个 4 字节对齐的 dex 都没有（说明打包器不管对齐），
         * 退回 4：新条目至少不会比现状更差，而 4 字节对齐对 dex 永远无害。
         */
        fun maxDexAlignment(): Int {
            val dexAlign = dataOffsets.keys
                .filter { Regex("""^classes\d*\.dex$""").matches(it) }
                .map { alignmentOf(it) }
                .maxOrNull() ?: 4
            return maxOf(dexAlign, 4)
        }

        /**
         * 复核：拿**源包**的每个条目的对齐要求，检查**本索引（产物）**的偏移是否满足。
         *
         * 注意不能用自己的偏移反推对齐——那必然自证成功（偏移是几的倍数，
         * 就推出来「要求是几」）。对齐要求必须来自源包，产物的偏移只是被检查的对象。
         */
        fun countMisalignedAgainst(source: LocalHeaderIndex): Int = dataOffsets.keys.count { name ->
            val align = source.requiredAlignOf(name)
            val off = dataOffsets[name] ?: return@count false
            align > 1 && off % align != 0L
        }

        /** 该条目在**本包**里要求对齐到几字节（只对 STORED 有意义）。 */
        internal fun requiredAlignOf(name: String): Int {
            if (stored[name] != true) return 1
            return alignmentOf(name)
        }

        companion object {
            /**
             * 收集每个条目的真实数据偏移。
             *
             * 【为什么必须走中央目录，不能顺着 local header 往前爬】
             * `ZipOutputStream` 写 DEFLATED 条目时用的是 data descriptor
             * （local header 的 flag bit3 置位，size/csize 全写 0，真实大小在数据之后）。
             * 顺着 local header 链走，`pos += csize` 会立刻算错位置、读不到第二个条目——
             * 那正是对齐自检一开始**假绿**的原因：索引读到 0 条，于是「未对齐数 = 0」。
             *
             * 中央目录里每个条目都写着完整的 csize 与 local header 偏移，
             * 数据偏移 = localOffset + 30 + nameLen + **local 的 extraLen**
             * （local 与中央目录的 extraLen 可以不同，所以 extraLen 仍要从 local header 读）。
             */
            fun read(apk: File): LocalHeaderIndex {
                val offsets = LinkedHashMap<String, Long>()
                val storedMap = LinkedHashMap<String, Boolean>()
                try {
                    RandomAccessFile(apk, "r").use { raf ->
                        val cd = centralDirectory(raf) ?: return empty()
                        raf.seek(cd.offset)
                        val buf = ByteArray(cd.size)
                        raf.readFully(buf)

                        var p = 0
                        while (p + 46 <= buf.size) {
                            if (le32(buf, p) != CENTRAL_SIG) break
                            val method = le16(buf, p + 10)
                            val nameLen = le16(buf, p + 28)
                            val extraLen = le16(buf, p + 30)
                            val commentLen = le16(buf, p + 32)
                            val localOffset = le32u(buf, p + 42)
                            val name = String(buf, p + 46, nameLen, Charsets.UTF_8)

                            // 回读 local header 只为拿它自己的 extraLen
                            val saved = raf.filePointer
                            raf.seek(localOffset)
                            val lh = ByteArray(LOCAL_HEADER_SIZE)
                            raf.readFully(lh)
                            val localExtraLen = le16(lh, 28)
                            val localNameLen = le16(lh, 26)
                            raf.seek(saved)

                            offsets[name] = localOffset + LOCAL_HEADER_SIZE + localNameLen + localExtraLen
                            storedMap[name] = method == 0
                            p += 46 + nameLen + extraLen + commentLen
                        }
                    }
                } catch (_: Exception) {
                    return empty()
                }
                return LocalHeaderIndex(offsets, storedMap)
            }

            private const val CENTRAL_SIG = 0x02014b50

            private class CentralDir(val offset: Long, val size: Int)

            /** 从尾部找 EOCD，取出中央目录的偏移与大小，并处理 ZIP64 与前置自解压前缀。 */
            private fun centralDirectory(raf: RandomAccessFile): CentralDir? {
                val len = raf.length()
                if (len < EOCD_SIZE) return null
                val window = minOf(len, EOCD_SIZE.toLong() + 0xFFFF).toInt()
                val tail = ByteArray(window)
                raf.seek(len - window)
                raf.readFully(tail)

                var eocd = -1
                for (i in window - EOCD_SIZE downTo 0) {
                    if (le32(tail, i) == EOCD_SIG) { eocd = i; break }
                }
                if (eocd < 0) return null

                val cdSize = le32u(tail, eocd + 12)
                val cdOffsetRaw = le32u(tail, eocd + 16)
                if (cdSize == 0L || cdSize > Int.MAX_VALUE) return null

                // 自解压 / 前置数据的包里，EOCD 记的偏移是相对 zip 起点的，
                // 与实际文件位置差一个前缀长度。用「EOCD 位置 - 中央目录大小」反推真实起点，
                // 这是 zipalign / apksig 处理同类问题用的判据。
                val eocdAbs = len - window + eocd
                val actualCdEnd = eocdAbs
                val guess = actualCdEnd - cdSize
                val prefix = if (guess >= 0) guess - cdOffsetRaw else 0L
                return CentralDir(cdOffsetRaw + prefix, cdSize.toInt())
            }

            /** 索引不出来时的空壳：所有 alignmentOf 都是 1，行为退回「不做对齐」。 */
            private fun empty() = LocalHeaderIndex(LinkedHashMap(), LinkedHashMap())
        }
    }

    /** 单纯数字节的输出流。`ZipOutputStream` 不给位置，而对齐必须知道位置。 */
    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}

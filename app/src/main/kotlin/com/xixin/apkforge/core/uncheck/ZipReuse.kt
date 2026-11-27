package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.Bytes

import com.xixin.apkforge.core.apk.ZipCentralDirectory
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * ZipReuse —— 让 `assets/nc_sigfix/origin.apk` 与整包**共享物理字节**。
 *
 * ## 解决的问题
 *
 * 去签名校验的 native 层要把 `open(base.apk)` 重定向到 `origin.apk`（原签名
 * APK 的完整副本）。若按普通条目投放，产物体积直接翻倍：
 *
 * ```
 * 122 MB 原包  →  244 MB 产物      ← 不可接受
 * ```
 *
 * ## 做法
 *
 * `origin.apk` 的数据区 = **整个产物从某个偏移到末尾的全部字节**。
 * 物理上只存一份，被两个 ZIP「视图」共享：
 *
 * ```
 * 输出 APK 布局：
 * ┌──────────────────────────────────────────┐
 * │ origin.apk 的 local header (补到 4096)    │ offset 0
 * ├──────────────────────────────────────────┤
 * │ 原 APK 的完整字节（逐字节副本）            │ offset = hdrLen
 * │   ↑ 这一段同时就是 origin.apk 的「数据」   │   ← 复用点
 * ├──────────────────────────────────────────┤
 * │ 额外条目：boot dex / .so / sigfix.json    │
 * ├──────────────────────────────────────────┤
 * │ 中央目录（origin + 原包所有条目 + 额外）   │
 * ├──────────────────────────────────────────┤
 * │ EOCD                                     │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 读 `assets/nc_sigfix/origin.apk` → 拿到**完整可用的原 APK**（不是片段）。
 * 原包里那份旧的中央目录 + EOCD 会出现在文件中间，但 ZIP 解析器从**文件末尾**
 * 找 EOCD，读到的是我们新写的那个，中间那份不会被误用。
 *
 * ## 为什么这不违反 ZIP 规范
 *
 * ZIP 只规定「条目由 local header + 数据描述，offset 记在中央目录」，
 * **没有任何「条目之间不得重叠」的约束**。两个条目指向重叠区域完全合法。
 * （Python 的 `zipfile` 有 zip-bomb 防护会拒绝重叠条目，那是它的策略，
 * 不是规范限制 —— Android 的解析器不受影响，已实测。）
 *
 * ## ⚠️ 三条实测红线（违反即「包装不上」或「装了没效果」）
 *
 * ### ① header 必须补齐到 4096，不能只补到 4
 *
 * 复用把 origin 的 local header 放在文件最前，**其后所有条目 offset 整体平移**
 * 这个 header 的长度。对齐要求来自 Android R+：
 *
 * ```
 * Failed parse during installPackageLI: Targeting R+ (version 30 and above)
 * requires the resources.arsc of installed APKs to be stored uncompressed
 * and aligned on a 4-byte boundary
 * ```
 *
 * 平移量是 4 的倍数时，原本对齐的条目平移后仍对齐。用 4096 而非 4，
 * 是为了同时满足未压缩 `.so` 的页对齐（`extractNativeLibs=false` 时）。
 *
 * ### ② local header 与中央目录的 extra 必须**长度一致**
 *
 * 只给 LFH 加、CD 不加时，读取方从 CD 得到 `extraLen=0` → 实际平移量还是
 * 原始 header 长度 → 等于没修。artifact-pipeline 在此踩过，且当时的单测
 * 只查 LFH 而误判成功。本实现的 [build] 用同一个 `originExtra` 写两处。
 *
 * ### ③ 产物之后**绝不能**再跑 apksigner / zipalign
 *
 * 两者都会重写整个 ZIP，把「重叠共享」展开成两份独立数据
 * （实测 122MB → 244MB，复用完全失效）。签名必须用**手写 ZIP 的工具**
 * （如 MT 管理器）。ApkForge 本身不签名，但这条要告知用户。
 *
 * ## 与 artifact-pipeline 实现的差异
 *
 * | | `zip-reuse.py` | 本实现 |
 * |---|---|---|
 * | 数据来源 | 中间包文件 → ZIP 里再读一遍 | 直接字节级拼接，不回读 |
 * | 额外条目 | 由参数传入 | 由 [ExtraEntry] 传入，保留原压缩方式 |
 * | 自检 | `verify()` 手工解析 | 同（[verify]），判据逐条对齐 |
 */
object ZipReuse {

    const val ORIGIN_ENTRY = "assets/nc_sigfix/origin.apk"

    private const val LFH_SIG = 0x04034b50
    private const val CD_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val LFH_SIZE = 30

    /**
     * 对齐粒度。
     *
     * 必须同时满足 `resources.arsc` 的 4 字节与未压缩 `.so` 的页对齐。
     * 4096 是传统页大小；16KB 页设备上 `.so` 的真实要求更高，但源包若本来
     * 就按 16KB 对齐，平移 4096 的倍数**不破坏**它的对齐（4096 | 16384）。
     */
    private const val ALIGN = 4096

    /** extra field 的占位 ID。解析器遇到未知 ID 会跳过，内容全是填充字节。 */
    private const val EXTRA_ID = 0x0000

    class ReuseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 要追加到产物里的额外条目。
     *
     * [method] 传 [ZipCentralDirectory.METHOD_STORED] 或 `METHOD_DEFLATED`。
     * `.so` 必须 STORED（运行时 `System.load` 需要 mmap，压缩过的不行）。
     */
    data class ExtraEntry(
        val name: String,
        val data: ByteArray,
        val method: Int = ZipCentralDirectory.METHOD_DEFLATED,
    )

    data class Stats(
        val sourceSize: Long,
        val outputSize: Long,
        val originHeaderBytes: Long,
        val entryCount: Int,
        /** 若不复用则要多存的字节数（= 原包大小 - header） */
        val savedBytes: Long,
    ) {
        val overheadBytes: Long get() = outputSize - sourceSize
    }

    /**
     * 把一个「已含全部改动、但**不含** origin.apk」的中间包，变成复用包。
     *
     * ## 调用方必须保证的顺序（云端的实测顺序，照抄）
     *
     * ```
     * ① rebuild 中间包（改 Manifest + 加 dex / assets / .so）
     * ② ApkSigningBlock.inject(中间包, 原包的签名块)   ← 必须在这之前注入
     * ③ ZipReuse.build(中间包 → 产物)                  ← 本函数
     * ```
     *
     * **第 ② 步不能省**。origin.apk 的数据区就是中间包的全字节，
     * 所以 origin.apk 里有没有签名块，取决于中间包里有没有。
     * 中间包是 `ZipOutputStream` 写的，天然不含签名块 —— 不注入的话
     * native 层从 origin.apk 读不到证书，整个 native 重定向白做。
     *
     * （这解释了为什么不需要「origin 用原包字节、布局用中间包」这种折中：
     *  把原包的签名块搬进中间包，两者就一致了。云端就是这么做的。）
     *
     * ## 对齐由本函数自己保证，不依赖外部 zipalign
     *
     * 云端调 `zipalign` 是因为它的中间包由 `apktool b` / `zipfile` 产生，
     * 不管对齐。本函数的 header 补齐到 4096 后，**平移量是 4096 的倍数**，
     * 原包/中间包里本来就对齐的条目平移后依然对齐 —— 不需要外部工具。
     *
     * 前提是**中间包自身是对齐的**：[UncheckPipeline] 用
     * [com.xixin.apkforge.core.apk.ApkRepackager] 的写法产出中间包，
     * 它已经按源包反推对齐要求并补齐 extra。
     *
     * @param sourceApk 中间包（已改 Manifest、已加 dex/assets/.so、已注入签名块）
     * @param outputApk 输出
     * @param originApk origin.apk 的**内容来源**。常规用法传 `sourceApk`
     *   （origin 数据区 = 中间包全字节，实现零拷贝复用）。
     */
    fun build(
        sourceApk: File,
        outputApk: File,
        originApk: File = sourceApk,
        extraEntries: List<ExtraEntry> = emptyList(),
    ): Stats {
        if (!sourceApk.isFile) throw ReuseException("中间包不存在: ${sourceApk.absolutePath}")
        if (!originApk.isFile) throw ReuseException("origin 源不存在: ${originApk.absolutePath}")

        val layout = try {
            ZipCentralDirectory.read(sourceApk)
        } catch (e: IllegalArgumentException) {
            throw ReuseException("中间包不是合法 ZIP：${e.message}", e)
        }
        if (layout.entries.any { it.name == ORIGIN_ENTRY }) {
            throw ReuseException("中间包里已经有 $ORIGIN_ENTRY 了，不能重复复用")
        }
        extraEntries.firstOrNull { it.name == ORIGIN_ENTRY }?.let {
            throw ReuseException("额外条目里不能含 $ORIGIN_ENTRY（它由复用机制生成）")
        }

        val originBytes = originApk.readBytes()
        if (originBytes.size < 4 || Bytes.i32(originBytes, 0) != LFH_SIG) {
            throw ReuseException(
                "origin 内容不是 ZIP/APK（开头应为 PK\\x03\\x04）：${originApk.name}"
            )
        }

        // ── 1. origin.apk 的 local header，补到 4096 ──────────────────
        val originExtra = alignExtra(lfhLen(ORIGIN_ENTRY))
        val hdrLen = lfhLen(ORIGIN_ENTRY) + originExtra.size
        require(hdrLen % 4 == 0) { "header 未对齐到 4: $hdrLen" }

        val originCrc = crc32(originBytes)

        outputApk.parentFile?.mkdirs()
        RandomAccessFile(outputApk, "rw").use { out ->
            out.setLength(0)

            // ── 2. [header][原包字节] ← 复用点 ─────────────────────────
            out.write(
                makeLfh(
                    name = ORIGIN_ENTRY,
                    method = ZipCentralDirectory.METHOD_STORED,
                    crc = originCrc,
                    csize = originBytes.size.toLong(),
                    fsize = originBytes.size.toLong(),
                    extra = originExtra,
                )
            )
            check(out.filePointer.toInt() == hdrLen) {
                "header 长度不符：期望 $hdrLen，实际 ${out.filePointer}"
            }

            out.write(originBytes)
            val originDataEnd = out.filePointer

            // ── 3. 额外条目（放在 origin 数据之后，避免污染 origin 内容）──
            data class ExtraMeta(
                val name: String,
                val method: Int,
                val crc: Long,
                val csize: Long,
                val fsize: Long,
                val offset: Long,
                val extra: ByteArray,
            )

            val extraMeta = ArrayList<ExtraMeta>(extraEntries.size)
            for (e in extraEntries) {
                val stored = e.method == ZipCentralDirectory.METHOD_STORED
                val payload = if (stored) e.data else deflateRaw(e.data)
                val method = if (stored) ZipCentralDirectory.METHOD_STORED
                else ZipCentralDirectory.METHOD_DEFLATED

                // 未压缩条目也要对齐到 4（resources.arsc 类要求；.so 更严但这里
                // 新增的 .so 只有 libusign，它对页对齐无要求——它由 System.load
                // 从 nativeLibraryDir 加载，位置由系统决定，与包内偏移无关）
                val extra = if (stored) {
                    alignExtra(LFH_SIZE + utf8Len(e.name))
                } else {
                    ByteArray(0)
                }

                val offset = out.filePointer
                out.write(
                    makeLfh(
                        name = e.name,
                        method = method,
                        crc = crc32(e.data),
                        csize = payload.size.toLong(),
                        fsize = e.data.size.toLong(),
                        extra = extra,
                    )
                )
                out.write(payload)
                extraMeta += ExtraMeta(
                    name = e.name,
                    method = method,
                    crc = crc32(e.data),
                    csize = payload.size.toLong(),
                    fsize = e.data.size.toLong(),
                    offset = offset,
                    extra = extra,
                )
            }

            // ── 4. 中央目录 ──────────────────────────────────────────
            val cdStart = out.filePointer
            val cd = ArrayList<ByteArray>(1 + layout.entries.size + extraMeta.size)

            // 4a. origin.apk 本身（offset = 0）
            // ⚠️ CD 的 extra 必须与 LFH 一致，否则读取方从 CD 拿 extraLen=0，
            //    实际平移量恢复成原始 header 长度 → 等于没对齐（红线 ②）。
            cd += makeCd(
                name = ORIGIN_ENTRY,
                method = ZipCentralDirectory.METHOD_STORED,
                crc = originCrc,
                csize = originBytes.size.toLong(),
                fsize = originBytes.size.toLong(),
                offset = 0,
                extra = originExtra,
            )

            // 4b. 原包所有条目：offset 整体平移 hdrLen
            for (e in layout.entries) {
                cd += makeCd(
                    name = e.name,
                    method = e.method,
                    crc = e.crc,
                    csize = e.compressedSize,
                    fsize = e.size,
                    offset = e.headerOffset + hdrLen,
                    extra = ByteArray(0),   // 源包的 extra 已含在原字节里，这里不重复
                )
            }

            // 4c. 额外条目
            for (m in extraMeta) {
                cd += makeCd(
                    name = m.name,
                    method = m.method,
                    crc = m.crc,
                    csize = m.csize,
                    fsize = m.fsize,
                    offset = m.offset,
                    extra = m.extra,
                )
            }

            var cdBytes = 0L
            for (b in cd) {
                out.write(b)
                cdBytes += b.size
            }

            // ── 5. EOCD ────────────────────────────────────────────
            val total = 1 + layout.entries.size + extraMeta.size
            out.write(makeEocd(total, cdBytes, cdStart))
        }

        val outLen = outputApk.length()
        return Stats(
            sourceSize = sourceApk.length(),
            outputSize = outLen,
            originHeaderBytes = hdrLen.toLong(),
            entryCount = 1 + layout.entries.size + extraEntries.size,
            savedBytes = originBytes.size.toLong() - hdrLen,
        )
    }

    /**
     * 自检：复用包的结构是否真的成立。
     *
     * ## 为什么必须自己解析，不能用 `ZipFile`
     *
     * `java.util.zip.ZipFile` 对重叠条目是**容忍的**（不像 Python 的
     * `zipfile` 会抛 `BadZipFile`），但仍不够：它无法验证「origin.apk 按
     * offset 读出来是不是完整 APK」。而这是复用成立的核心判据 ——
     * 复用写错时产物照样能被 `ZipFile` 正常打开，只有 origin.apk 内容是坏的。
     *
     * ## 判据（每一条都对应一次实测失败）
     *
     * ① origin.apk 按 offset 读出必须是**完整 APK**（能当 ZIP 打开且有条目）
     *    —— 防止「只留签名块」那类破坏 ART 懒加载的做法
     * ② 所有 `classes*.dex` magic 正确
     * ③ `resources.arsc` 必须 STORED 且 4 字节对齐（Android R+ 装机硬检查）
     * ④ CD 条目数与 EOCD 声明一致
     *
     * @return 问题列表，空表示通过
     */
    fun verify(apk: File): List<String> {
        val problems = ArrayList<String>()
        val layout = try {
            ZipCentralDirectory.read(apk)
        } catch (e: Exception) {
            return listOf("无法解析复用包: ${e.message}")
        }

        if (layout.prefix != 0L) {
            problems += "复用包不该有自解压前缀（prefix=${layout.prefix}）"
        }

        // ① origin.apk 必须是完整可打开的 APK
        val origin = layout.byName(ORIGIN_ENTRY)
        if (origin == null) {
            problems += "找不到 $ORIGIN_ENTRY"
        } else {
            val bytes = try {
                ZipCentralDirectory.readRaw(apk, origin)
            } catch (e: Exception) {
                problems += "读取 $ORIGIN_ENTRY 失败: ${e.message}"
                null
            }
            if (bytes != null) {
                if (bytes.size < 4 || Bytes.i32(bytes, 0) != LFH_SIG) {
                    problems += "$ORIGIN_ENTRY 开头不是 ZIP local header"
                } else {
                    val tmp = File.createTempFile("origin-check", ".apk")
                    try {
                        tmp.writeBytes(bytes)
                        val inner = runCatching { ZipCentralDirectory.read(tmp) }.getOrNull()
                        when {
                            inner == null -> problems += "$ORIGIN_ENTRY 不是合法 ZIP"
                            inner.entries.isEmpty() -> problems += "$ORIGIN_ENTRY 里没有任何条目"
                            inner.entries.none { it.name == "AndroidManifest.xml" } ->
                                problems += "$ORIGIN_ENTRY 缺 AndroidManifest.xml（不是完整 APK）"
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }

        // ② 所有 dex magic
        for (e in layout.entries.filter { it.isDex }) {
            val head = runCatching {
                RandomAccessFile(apk, "r").use { raf ->
                    raf.seek(e.dataOffset)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    b
                }
            }.getOrNull()
            if (head == null) {
                problems += "${e.name} 数据读不出"
            } else if (String(head, Charsets.US_ASCII) != "dex\n") {
                // DEFLATED 的 dex 不能直接读头部，只能跳过
                if (e.isStored) problems += "${e.name} magic 非法（STORED 应能直接读）"
            }
        }

        // ③ resources.arsc
        val arsc = layout.byName("resources.arsc")
        if (arsc == null) {
            problems += "缺少 resources.arsc"
        } else {
            if (!arsc.isStored) {
                problems += "resources.arsc 未压缩（method=${arsc.method}），Android R+ 要求 STORED"
            }
            if (arsc.dataOffset % 4L != 0L) {
                problems += "resources.arsc 未 4 字节对齐（dataOffset=${arsc.dataOffset}, " +
                    "mod4=${arsc.dataOffset % 4}）—— 装机会被拒"
            }
        }

        return problems
    }

    // ── 字节级构造 ────────────────────────────────────────────

    private fun lfhLen(name: String): Int = LFH_SIZE + utf8Len(name)

    private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * 生成填充用 extra field，使 `baseLen + len(extra)` 是 [ALIGN] 的倍数。
     *
     * extra field 结构：`[id 2B][size 2B][body]`，body 长 = size。
     * 一个 extra field 至少 4 字节（id + size），不足时补一整轮 [ALIGN]。
     *
     * ⚠️ 调用方必须把它**同时**写进 local header 与中央目录（红线 ②）。
     */
    private fun alignExtra(baseLen: Int, align: Int = ALIGN): ByteArray {
        var need = (align - baseLen % align) % align
        if (need == 0) return ByteArray(0)
        if (need < 4) need += align
        val body = need - 4
        val out = ByteArray(need)
        out[0] = (EXTRA_ID and 0xFF).toByte()
        out[1] = ((EXTRA_ID shr 8) and 0xFF).toByte()
        out[2] = (body and 0xFF).toByte()
        out[3] = ((body shr 8) and 0xFF).toByte()
        // body 全 0：解析器按 size 跳过，内容无意义
        return out
    }

    private fun makeLfh(
        name: String,
        method: Int,
        crc: Long,
        csize: Long,
        fsize: Long,
        extra: ByteArray,
    ): ByteArray {
        val nb = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(LFH_SIZE + nb.size + extra.size)
        Bytes.putI32(out, 0, LFH_SIG)
        Bytes.putU16(out, 4, if (method == ZipCentralDirectory.METHOD_DEFLATED) 20 else 10)
        Bytes.putU16(out, 6, 0)                       // flags：不用 data descriptor
        Bytes.putU16(out, 8, method)
        Bytes.putU16(out, 10, 0)                      // mtime
        Bytes.putU16(out, 12, 0x21)                   // mdate 1980-01-01（ZIP 最小合法值）
        Bytes.putI32(out, 14, crc.toInt())
        Bytes.putI32(out, 18, csize.toInt())
        Bytes.putI32(out, 22, fsize.toInt())
        Bytes.putU16(out, 26, nb.size)
        Bytes.putU16(out, 28, extra.size)
        System.arraycopy(nb, 0, out, LFH_SIZE, nb.size)
        System.arraycopy(extra, 0, out, LFH_SIZE + nb.size, extra.size)
        return out
    }

    private fun makeCd(
        name: String,
        method: Int,
        crc: Long,
        csize: Long,
        fsize: Long,
        offset: Long,
        extra: ByteArray,
    ): ByteArray {
        val nb = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(46 + nb.size + extra.size)
        Bytes.putI32(out, 0, CD_SIG)
        Bytes.putU16(out, 4, 20)                      // version made by
        Bytes.putU16(out, 6, if (method == ZipCentralDirectory.METHOD_DEFLATED) 20 else 10)
        Bytes.putU16(out, 8, 0)                       // flags
        Bytes.putU16(out, 10, method)
        Bytes.putU16(out, 12, 0)                      // mtime
        Bytes.putU16(out, 14, 0x21)                   // mdate
        Bytes.putI32(out, 16, crc.toInt())
        Bytes.putI32(out, 20, csize.toInt())
        Bytes.putI32(out, 24, fsize.toInt())
        Bytes.putU16(out, 28, nb.size)
        Bytes.putU16(out, 30, extra.size)
        Bytes.putU16(out, 32, 0)                      // comment len
        Bytes.putU16(out, 34, 0)                      // disk start
        Bytes.putU16(out, 36, 0)                      // internal attr
        Bytes.putI32(out, 38, 0)                        // external attr
        Bytes.putI32(out, 42, offset.toInt())
        System.arraycopy(nb, 0, out, 46, nb.size)
        System.arraycopy(extra, 0, out, 46 + nb.size, extra.size)
        return out
    }

    private fun makeEocd(total: Int, cdSize: Long, cdOffset: Long): ByteArray {
        val out = ByteArray(22)
        Bytes.putI32(out, 0, EOCD_SIG)
        Bytes.putU16(out, 4, 0)                       // disk num
        Bytes.putU16(out, 6, 0)                       // cd start disk
        Bytes.putU16(out, 8, total)
        Bytes.putU16(out, 10, total)
        Bytes.putI32(out, 12, cdSize.toInt())
        Bytes.putI32(out, 16, cdOffset.toInt())
        Bytes.putU16(out, 20, 0)                      // comment len
        return out
    }

    private fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    /** raw deflate（无 zlib 头），与 ZIP 的 method=8 一致。 */
    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }



}

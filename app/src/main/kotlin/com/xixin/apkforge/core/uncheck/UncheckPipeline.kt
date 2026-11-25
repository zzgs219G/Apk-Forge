package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ApkSigningBlock
import com.xixin.apkforge.core.apk.ZipCentralDirectory
import com.xixin.apkforge.core.axml.AxmlBoolean
import com.xixin.apkforge.core.axml.AxmlEditor
import com.xixin.apkforge.core.axml.AxmlManifest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 去签名校验流水线：**原始 APK → 未签名 APK**。
 *
 * ## 与 dex 注入的关系
 *
 * 两条并列的线，唯一的共同点是「都产出未签名 APK」：
 * ```
 * dex 注入（ApkInjectPipeline）  改行为：插 invoke-static
 * 去校验（本类）                改环境：改 android:name + 塞载荷
 * ```
 * 刻意不共用 dex 注入那套（不插指令、不碰原有 dex）。
 *
 * ## 七个落点，缺一即为静默失效
 *
 * | # | 目标包里的位置 | 来源 | 漏了会怎样 |
 * |---|---|---|---|
 * | 1 | `classes{N+1}.dex` | boot.dex | 没有任何代码被注入 |
 * | 2 | `assets/nc_sigfix/payload.dex` | payload.dex | 引导器找不到实现类 |
 * | 3 | `assets/nc_sigfix/sigfix.json` | 运行时生成 | 没证书，`install()` 直接 return |
 * | 4 | `assets/nc_sigfix/origin.apk` | 原包全字节 | native 层没重定向目标 |
 * | 5 | `lib/{abi}/libusign.so` | 载荷 .so | native hook 没库可加载 |
 * | 6 | Manifest `android:name` | 改写 | 载荷永不执行（最隐蔽）|
 * | 7 | `extractNativeLibs` = true | 改写 | .so 不解压，`System.load` 失败 |
 *
 * 3 与 4 的区别常被搞混——都是「把原签名灌进去」，但用途不同：
 * ```
 * cert（几百字节）  → 写进 sigfix.json → Java 层伪造 PackageInfo.signatures
 * origin.apk（整个原包）→ 写进 assets  → native 层把 open(base.apk) 重定向到它
 * ```
 *
 * ## 步骤顺序（每一步的顺序都是硬要求）
 *
 * ```
 * ① 读原包：Manifest / 包名 / 原 Application / dex 清单
 * ② 提原证书（Signing Block → DER）
 * ③ 取原签名块（v2/v3 原始字节，后面要搬进中间包）
 * ④ 决定新 dex 名（classes{最大序号+1}.dex）
 * ⑤ 改写 Manifest：android:name = SigFixApp；extractNativeLibs = true
 * ⑥ 建中间包：原包逐条复制 + 替换 Manifest + 追加 dex/assets/.so
 * ⑦ 注入签名块（必须在 ⑥ 之后：ZipOutputStream 重写会丢掉它）
 * ⑧ 数据复用（必须在 ⑦ 之后：origin.apk 的数据区 = 中间包全字节）
 * ⑨ 自检 → 输出
 * ```
 *
 * ⑦⑧ 的顺序在云端有过一次真机事故（SIGBUS），`UncheckAssemblyOrderTest` 钉住。
 *
 * ## 为什么产物不签名
 *
 * 与仓库既有契约一致：签名要用**用户自己的** keystore，工具碰 keystore
 * 既有安全问题也没必要。但必须告知用户：**数据复用包只能用「手写 ZIP」
 * 的工具签**（如 MT 管理器），apksigner 会展开重叠条目让体积翻倍。
 */
object UncheckPipeline {

    /** android:extractNativeLibs 的 framework 资源 ID */
    private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104EA.toInt()

    class PipelineException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** 进度回调（UI 用）。stage 是粗粒度阶段名，detail 是细节。 */
    fun interface Progress {
        fun on(stage: String, detail: String)
    }

    data class Result(
        val output: File,
        val version: String,
        /** 原包的 Application 类名（null = 原包没写，用系统默认） */
        val originalApp: String?,
        val newDex: String,
        val certSha256Short: String,
        /** 产物体积与源包体积 */
        val outputSize: Long,
        val sourceSize: Long,
        /** origin.apk 复用省下的字节（若不复用会多这么多） */
        val savedByReuse: Long,
        /** 原包是否带 v2/v3 签名块（决定 native 层能否读到证书） */
        val hadSignature: Boolean,
        /** extractNativeLibs 的改写结果 */
        val extractNativeLibs: AxmlBoolean.Outcome,
    ) {
        /** 是否具备 native 层能力（需要签名块 + .so 都到位） */
        val nativeCapable: Boolean get() = hadSignature
    }

    /**
     * 执行去签名校验。
     *
     * @param input 原始 APK
     * @param output 输出 APK（**未签名**）
     * @param payload 由 `data.uncheck.PayloadFetcher` 提供
     * @param workDir 中间文件目录（调用方负责清理）。缺省用 output 同级的临时目录。
     */
    fun run(
        input: File,
        output: File,
        payload: UncheckPayload.Payload,
        workDir: File? = null,
        progress: Progress = Progress { _, _ -> },
    ): Result {
        if (!input.isFile) throw PipelineException("输入不存在：${input.absolutePath}")

        val work = workDir ?: File(output.absolutePath + ".work").apply { mkdirs() }
        work.mkdirs()

        try {
            // ── ① 读原包 ──────────────────────────────────────
            progress.on("读取 APK", input.name)
            val sourceLayout = try {
                ZipCentralDirectory.read(input)
            } catch (e: IllegalArgumentException) {
                throw PipelineException("输入不是合法 APK：${e.message}", e)
            }
            val dexNames = sourceLayout.dexNames()
            if (dexNames.isEmpty()) {
                throw PipelineException("APK 里没有任何 classes*.dex，不是可处理的包")
            }
            progress.on("dex 清单", dexNames.joinToString(", "))

            val manifestBytes = readEntry(input, UncheckPayload.MANIFEST)
                ?: throw PipelineException("APK 里没有 ${UncheckPayload.MANIFEST}")

            val parsed = try {
                AxmlManifest.parseBytes(manifestBytes)
            } catch (e: Exception) {
                throw PipelineException("解析 Manifest 失败：${e.message}", e)
            }
            progress.on("包名", parsed.packageName ?: "(未知)")
            progress.on("原 Application", parsed.applicationName ?: "(系统默认)")

            // ── ② 提证书 ──────────────────────────────────────
            progress.on("提取原证书", "")
            val cert = try {
                CertExtractor.extract(input)
            } catch (e: CertExtractor.CertException) {
                throw PipelineException(
                    "提取证书失败：${e.message}\n" +
                        "  去校验必须知道原包证书才能伪造签名；" +
                        "v1-only 的包请先用 apksigner 以 v2+ 重签一次。",
                    e,
                )
            }
            progress.on("证书", "v${cert.schemeVersion} / ${cert.certDer.size} 字节")

            // ── ③ 原签名块 ────────────────────────────────────
            val sigBlock = ApkSigningBlock.extract(input)
            val hadSignature = sigBlock != null
            progress.on(
                "签名块",
                if (hadSignature) "${sigBlock.size} 字节（将搬进 origin.apk）"
                else "原包没有 v2/v3 签名块 —— native 层读不到证书，将只剩 Java 层",
            )

            // ── ④ 新 dex 名 ───────────────────────────────────
            val newDex = nextDexName(dexNames)
            progress.on("新 dex", newDex)

            // ── ⑤ 改写 Manifest ───────────────────────────────
            progress.on("改写 Manifest", UncheckPayload.SIGFIX_APP)
            var newManifest = try {
                AxmlEditor.setAppName(manifestBytes, UncheckPayload.SIGFIX_APP)
            } catch (e: AxmlEditor.AxmlException) {
                throw PipelineException("改写 android:name 失败：${e.message}", e)
            }
            // 回读校验：写的时候对、读出来不对，等于没写
            val writtenApp = runCatching { AxmlEditor.getAppName(newManifest) }.getOrNull()
            if (writtenApp != UncheckPayload.SIGFIX_APP) {
                throw PipelineException(
                    "Manifest 改写后回读不一致：期望 ${UncheckPayload.SIGFIX_APP}，实际 $writtenApp"
                )
            }

            progress.on("改写 Manifest", "extractNativeLibs → true")
            val (withLibs, libsOutcome) = try {
                AxmlBoolean.setExtractNativeLibsTrue(newManifest)
            } catch (e: Exception) {
                throw PipelineException(
                    "改写 extractNativeLibs 失败：${e.message}\n" +
                        "  这一步决定 .so 能否被解压到 nativeLibraryDir，" +
                        "缺失时 native 层加载必然失败。",
                    e,
                )
            }
            newManifest = withLibs
            progress.on(
                "extractNativeLibs",
                when (libsOutcome) {
                    AxmlBoolean.Outcome.ALREADY_ON -> "原本就是 true"
                    AxmlBoolean.Outcome.FLIPPED -> "false → true（原位）"
                    AxmlBoolean.Outcome.ADDED -> "新增并设为 true"
                },
            )

            // ── ⑥ 中间包 ──────────────────────────────────────
            progress.on("组装中间包", "")
            val intermediate = File(work, "intermediate.apk")
            val config = buildConfigJson(
                certB64 = java.util.Base64.getEncoder().encodeToString(cert.certDer),
                originalApp = parsed.applicationName ?: "",
            )
            val extra = buildList {
                add(ZipReuse.ExtraEntry(newDex, payload.bootDex, ZipCentralDirectory.METHOD_DEFLATED))
                add(
                    ZipReuse.ExtraEntry(
                        UncheckPayload.ASSET_PAYLOAD_DEX, payload.payloadDex,
                        ZipCentralDirectory.METHOD_DEFLATED,
                    )
                )
                add(
                    ZipReuse.ExtraEntry(
                        UncheckPayload.ASSET_CONFIG, config,
                        ZipCentralDirectory.METHOD_DEFLATED,
                    )
                )
                // .so 必须 STORED：System.load 需要直接映射，压缩过的加载不了
                add(
                    ZipReuse.ExtraEntry(
                        UncheckPayload.LIB_ARM64, payload.usignArm64,
                        ZipCentralDirectory.METHOD_STORED,
                    )
                )
                add(
                    ZipReuse.ExtraEntry(
                        UncheckPayload.LIB_ARM32, payload.usignArm32,
                        ZipCentralDirectory.METHOD_STORED,
                    )
                )
            }
            buildIntermediate(input, intermediate, newManifest, extra)
            progress.on("中间包", "${intermediate.length()} 字节，+${extra.size} 个条目")

            // ── ⑦ 注入签名块 ──────────────────────────────────
            // 必须在 ⑥ 之后：ZipOutputStream 重写整个 ZIP，签名块不是条目必被丢弃。
            // 也必须在 ⑧ 之前：origin.apk 的数据区就是中间包字节，
            // 中间包里没签名块，origin.apk 里就没有。
            if (sigBlock != null) {
                progress.on("注入签名块", "")
                try {
                    ApkSigningBlock.inject(intermediate, sigBlock)
                } catch (e: ApkSigningBlock.SignBlockException) {
                    throw PipelineException("注入签名块失败：${e.message}", e)
                }
            }

            // ── ⑧ 数据复用 ────────────────────────────────────
            progress.on("数据复用", "让 origin.apk 与整包共享字节")
            val stats = try {
                ZipReuse.build(sourceApk = intermediate, outputApk = output)
            } catch (e: ZipReuse.ReuseException) {
                throw PipelineException("数据复用失败：${e.message}", e)
            }
            progress.on(
                "数据复用",
                "${stats.sourceSize} → ${stats.outputSize} 字节" +
                    "（若不复用要多存 ${stats.savedBytes} 字节）",
            )

            // ── ⑨ 自检 ────────────────────────────────────────
            progress.on("自检", "")
            try {
                selfCheck(output, newDex, config.size, hadSignature)
            } catch (e: Exception) {
                // 半成品必须删掉：留个几百字节的 .apk 会让人以为成功了
                output.delete()
                throw PipelineException("产物自检失败：${e.message}", e)
            }

            val certSha = UncheckPayload.sha256(cert.certDer).take(16)
            progress.on("完成", output.absolutePath)

            return Result(
                output = output,
                version = payload.version,
                originalApp = parsed.applicationName,
                newDex = newDex,
                certSha256Short = certSha,
                outputSize = output.length(),
                sourceSize = input.length(),
                savedByReuse = stats.savedBytes,
                hadSignature = hadSignature,
                extractNativeLibs = libsOutcome,
            )
        } finally {
            // 中间文件不留给用户：它们的体积可达原包两倍
            if (workDir == null) work.deleteRecursively()
        }
    }

    // ── 中间包 ────────────────────────────────────────────────

    /**
     * 建中间包：原包逐条复制 + 替换 Manifest + 追加额外条目。
     *
     * ## 为什么逐条复制而不是直接改原包
     *
     * 原包的条目数据是一个整体字节流，改一个条目就要重排所有 offset。
     * 用 `ZipOutputStream` 重写是唯一不依赖外部工具的做法 —— 代价是
     * **签名块会丢**（它不是一个条目），所以调用方必须在之后补注（步骤 ⑦）。
     *
     * ## STORED 条目必须显式给 size/crc
     *
     * `ZipOutputStream` 对 STORED 条目要求预先知道大小与 CRC，不给会抛
     * `invalid entry size`。而 APK 里大量资源（res/ 下的图、resources.arsc）
     * 本来就是 STORED —— 照搬 method 却不给这两项，会在写到第一个未压缩
     * 条目时失败，产物只剩几百字节残壳，且异常信息与「压缩」毫无关联。
     */
    private fun buildIntermediate(
        source: File,
        target: File,
        newManifest: ByteArray,
        extra: List<ZipReuse.ExtraEntry>,
    ) {
        ZipFile(source).use { zin ->
            ZipOutputStream(target.outputStream().buffered()).use { zout ->
                for (entry in zin.entries()) {
                    if (entry.isDirectory) {
                        zout.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        zout.closeEntry()
                        continue
                    }
                    val data = when (entry.name) {
                        UncheckPayload.MANIFEST -> newManifest
                        else -> zin.getInputStream(entry).use { it.readBytes() }
                    }
                    writeEntry(zout, entry, data)
                }

                for (x in extra) {
                    val e = ZipEntry(x.name).apply {
                        method = if (x.method == ZipCentralDirectory.METHOD_STORED) ZipEntry.STORED
                        else ZipEntry.DEFLATED
                        time = 0L    // 固定时间戳，让同输入产出稳定
                    }
                    writeEntry(zout, e, x.data)
                }
            }
        }
    }

    /**
     * 写一个条目。
     *
     * ## 为什么这里**不**做对齐补 extra
     *
     * 第一版试图给 STORED 条目补 extra 对齐，但 `ZipOutputStream` 不暴露
     * 「当前写到哪里」，只能拿到 `30 + nameLen` 这个**相对值**，算不出绝对
     * 偏移 → 补出来的 extra 长度是错的。
     *
     * 真正保证对齐的是后面的 [ZipReuse]：它把 origin 的 header 补到 4096，
     * **平移量是 4096 的倍数**，所以中间包里「已经对齐的条目」平移到产物后
     * 依然对齐。中间包自身不需要额外对齐 —— 我们只要求它**不破坏**原包的
     * 相对布局，而逐条原样拷贝（保留 method 与 extra）正好满足。
     *
     * 产物最终是否真的对齐，由 [ZipReuse.verify] 的 `resources.arsc`
     * 判据兜底检查，装不上会在这里被拦住，而不是留给用户试装。
     */
    private fun writeEntry(zout: ZipOutputStream, src: ZipEntry, data: ByteArray) {
        val e = ZipEntry(src.name).apply {
            time = src.time
            method = if (src.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            if (method == ZipEntry.STORED) {
                // STORED 必须显式给这三项，否则 ZipOutputStream 抛 invalid entry size
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = java.util.zip.CRC32().apply { update(data) }.value
            }
        }
        zout.putNextEntry(e)
        zout.write(data)
        zout.closeEntry()
    }

    // ── 自检 ──────────────────────────────────────────────────

    /**
     * 产物自检。**每一条都对应一次「装不上 / 装上没用」的真实失败**。
     */
    private fun selfCheck(apk: File, newDex: String, configSize: Int, hadSignature: Boolean) {
        val layout = ZipCentralDirectory.read(apk)

        // 1) 七个落点齐全
        val required = listOf(
            newDex,
            UncheckPayload.ASSET_PAYLOAD_DEX,
            UncheckPayload.ASSET_CONFIG,
            UncheckPayload.ASSET_ORIGIN,
            UncheckPayload.LIB_ARM64,
            UncheckPayload.LIB_ARM32,
        )
        val missing = required.filter { layout.byName(it) == null }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("产物缺条目：${missing.joinToString(", ")}")
        }

        // 2) Manifest 必须真的指向 SigFixApp
        val mf = readEntry(apk, UncheckPayload.MANIFEST)
            ?: throw IllegalStateException("产物里没有 Manifest")
        val app = AxmlEditor.getAppName(mf)
        if (app != UncheckPayload.SIGFIX_APP) {
            throw IllegalStateException(
                "Manifest 的 android:name 不是 ${UncheckPayload.SIGFIX_APP}（实际 $app）—— " +
                    "载荷永远不会被执行"
            )
        }
        // 3) extractNativeLibs 必须是 true（否则 .so 不解压）
        if (AxmlBoolean.getExtractNativeLibs(mf) != true) {
            throw IllegalStateException(
                "extractNativeLibs 不是 true —— .so 不会被解压到 nativeLibraryDir，" +
                    "System.load 必然失败，native 层完全无效"
            )
        }

        // 4) 新 dex 必须是合法 dex
        val dex = readEntry(apk, newDex) ?: ByteArray(0)
        if (dex.size < 8 || String(dex, 0, 4, Charsets.US_ASCII) != "dex\n") {
            throw IllegalStateException("$newDex 不是合法 dex")
        }

        // 5) 配置大小与写入的一致
        val cfg = readEntry(apk, UncheckPayload.ASSET_CONFIG) ?: ByteArray(0)
        if (cfg.size != configSize) {
            throw IllegalStateException(
                "${UncheckPayload.ASSET_CONFIG} 大小不符：期望 $configSize，实际 ${cfg.size}"
            )
        }

        // 6) .so 必须是 STORED（压缩过的加载不了）
        for (lib in listOf(UncheckPayload.LIB_ARM64, UncheckPayload.LIB_ARM32)) {
            val e = layout.byName(lib)!!
            if (!e.isStored) {
                throw IllegalStateException("$lib 不是 STORED，System.load 会失败")
            }
        }

        // 7) 复用结构自检（origin.apk 完整、arsc 对齐）
        val problems = ZipReuse.verify(apk)
        if (problems.isNotEmpty()) {
            throw IllegalStateException("复用结构异常：\n  ${problems.joinToString("\n  ")}")
        }

        // 8) 原包带签名块时，origin.apk 里也必须带
        //    这是 native 层读证书的唯一来源
        if (hadSignature) {
            val originEntry = layout.byName(UncheckPayload.ASSET_ORIGIN)!!
            val tmp = File.createTempFile("origin", ".apk")
            try {
                tmp.writeBytes(ZipCentralDirectory.readRaw(apk, originEntry))
                if (!ApkSigningBlock.hasBlock(tmp)) {
                    throw IllegalStateException(
                        "原包有签名块，但 origin.apk 里没有 —— native 层将读不到证书。" +
                            "检查「注入签名块」与「数据复用」的先后顺序。"
                    )
                }
            } finally {
                tmp.delete()
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────

    private fun readEntry(apk: File, name: String): ByteArray? {
        ZipFile(apk).use { zf ->
            val e = zf.getEntry(name) ?: return null
            return zf.getInputStream(e).use { it.readBytes() }
        }
    }

    /** `classes.dex` 视为 1，取最大序号 +1，绝不占已有名字。 */
    internal fun nextDexName(existing: List<String>): String {
        var max = 1
        for (n in existing) {
            val m = Regex("""^classes(\d*)\.dex$""").find(n) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: 1
            if (idx > max) max = idx
        }
        return "classes${max + 1}.dex"
    }

    /**
     * 生成 `sigfix.json`。
     *
     * 只有两个字段，都是载荷运行期需要的目标相关数据：
     * - `cert`：原证书 DER 的 Base64（Java 层伪造签名用）
     * - `originalApp`：原 Application 类名（代理链要反射创建它）
     *
     * 为什么放 JSON 而不是烘焙进 dex：证书 Base64 长度随 keystore 变
     * （888~1152 字符），改 dex 字符串池要重建；放一份小 JSON 则改数据不碰 dex，
     * dex 保持静态资产（可入 git、可下载、可缓存）。
     */
    internal fun buildConfigJson(certB64: String, originalApp: String): ByteArray {
        fun esc(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
        return """{"cert":${esc(certB64)},"originalApp":${esc(originalApp)}}"""
            .toByteArray(Charsets.UTF_8)
    }
}

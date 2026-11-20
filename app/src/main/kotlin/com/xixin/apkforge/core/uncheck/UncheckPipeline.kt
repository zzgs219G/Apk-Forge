package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.axml.AxmlEditor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * UncheckPipeline —— 去签名校验的完整流程（本地，无网络、无外部工具）。
 *
 * ## 六个步骤
 *
 * ```
 * ① 读输入 APK：包名 / 原 Application / 原证书 DER / dex 清单
 * ② 取载荷（PayloadRepo：缓存优先，下载兜底，sha256 校验）
 * ③ 决定新 dex 名（classes{N+1}.dex，不碰任何已有 dex）
 * ④ 组装：原包逐条复制 + 替换 Manifest + 新增 dex 与 assets
 * ⑤ 自检：dex 结构 / Manifest 指向 / assets 在位
 * ⑥ 输出未签名 APK
 * ```
 *
 * ## 三个设计决策（各有实测依据）
 *
 * **为什么载荷放独立 dex，不改原 dex**
 *   载荷类与原包毫无关系，没有理由混进 `classes.dex`。独立 dex 的好处：
 *   原 dex 逐字节不变 → 出问题时删掉那一个 dex 就回到原始状态，可二分排查。
 *
 * **为什么可变数据放 sigfix.json 而不是烘焙进 dex**
 *   证书 Base64 长度随 keystore 变（888~1152 字符），改 dex 字符串池要重建，
 *   风险高；放一份小 JSON 则改数据不碰 dex，dex 保持静态资产。
 *
 * **为什么产物不签名**
 *   与仓库既有契约一致。签名要用**用户自己的** keystore，
 *   工具碰 keystore 既有安全问题也没必要。
 */
object UncheckPipeline {

    private const val MANIFEST = "AndroidManifest.xml"
    private const val SIGFIX_APP = "com.nc.uncheck.SigFixApp"
    private const val ASSET_DIR = "assets/nc_sigfix"
    private const val PAYLOAD_ASSET = "$ASSET_DIR/payload.dex"
    private const val CONFIG_ASSET = "$ASSET_DIR/sigfix.json"

    class PipelineException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class Result(
        val output: File,
        val version: String,
        val originalApp: String?,
        val newDex: String,
        val certSha256Short: String,
    )

    /** 进度回调（UI 用）。 */
    fun interface Progress {
        fun on(stage: String, detail: String)
    }

    /**
     * 执行去签名校验。
     *
     * @param input 原始 APK
     * @param output 输出 APK（**未签名**）
     */
    fun run(
        input: File,
        output: File,
        payload: PayloadRepo.Payload,
        progress: Progress = Progress { _, _ -> },
    ): Result {
        if (!input.isFile) throw PipelineException("输入不存在: ${input.absolutePath}")

        // ① 读输入
        progress.on("读取 APK", "")
        val manifestBytes: ByteArray
        val dexNames: List<String>
        ZipFile(input).use { zf ->
            val me = zf.getEntry(MANIFEST)
                ?: throw PipelineException("APK 里没有 $MANIFEST")
            manifestBytes = zf.getInputStream(me).use { it.readBytes() }
            dexNames = zf.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .toList()
        }
        if (dexNames.isEmpty()) throw PipelineException("APK 里没有任何 classes*.dex")

        val packageName = runCatching { readPackageName(manifestBytes) }.getOrNull()
        val originalApp = runCatching { AxmlEditor.getAppName(manifestBytes) }.getOrNull()
        progress.on("包名", packageName ?: "(未知)")
        progress.on("原 Application", originalApp ?: "(无)")

        // ② 原证书
        progress.on("提取原证书", "")
        val cert = try {
            CertExtractor.extract(input)
        } catch (e: CertExtractor.CertException) {
            throw PipelineException("提取证书失败：${e.message}", e)
        }
        val certB64 = android.util.Base64.encodeToString(cert.certDer, android.util.Base64.NO_WRAP)
        progress.on("证书", "v${cert.schemeVersion} / ${cert.certDer.size} 字节")

        // ③ 新 dex 名：取最大序号 +1，绝不占已有名字
        val newDex = nextDexName(dexNames)
        progress.on("新 dex", newDex)

        // ④ 改 Manifest
        progress.on("改写 Manifest", "")
        val newManifest = try {
            AxmlEditor.setAppName(manifestBytes, SIGFIX_APP)
        } catch (e: AxmlEditor.AxmlException) {
            throw PipelineException("改写 AndroidManifest 失败：${e.message}", e)
        }
        val writtenApp = AxmlEditor.getAppName(newManifest)
        if (writtenApp != SIGFIX_APP) {
            throw PipelineException("Manifest 改写后回读不一致：期望 $SIGFIX_APP，实际 $writtenApp")
        }

        // ⑤ 组装
        progress.on("组装 APK", "")
        val cfg = """{"cert":${jsonStr(certB64)},"originalApp":${jsonStr(originalApp ?: "")}}"""
        val cfgBytes = cfg.toByteArray(Charsets.UTF_8)

        output.parentFile?.mkdirs()
        try {
            assemble(input, output, newManifest, newDex, payload, cfgBytes)
        } catch (e: Exception) {
            // 半成品必须删掉：残留一个几百字节的 .apk 会让人误以为成功了
            output.delete()
            throw PipelineException("组装 APK 失败：${e.message ?: e::class.simpleName}", e)
        }

        // ⑥ 自检
        progress.on("自检", "")
        try {
            selfCheck(output, newDex, cfgBytes.size)
        } catch (e: Exception) {
            output.delete()
            throw PipelineException("产物自检失败：${e.message}", e)
        }

        val certSha = PayloadRepo.sha256(cert.certDer).take(16)
        progress.on("完成", output.absolutePath)
        return Result(output, payload.version, originalApp, newDex, certSha)
    }

    /** 真正写 zip 的部分，抽出来便于上面统一兜住删除半成品。 */
    private fun assemble(
        input: File,
        output: File,
        newManifest: ByteArray,
        newDex: String,
        payload: PayloadRepo.Payload,
        cfgBytes: ByteArray,
    ) {
        ZipFile(input).use { zin ->
            ZipOutputStream(output.outputStream().buffered()).use { zout ->
                for (entry in zin.entries()) {
                    val name = entry.name
                    when (name) {
                        MANIFEST -> {
                            writeEntry(zout, name, newManifest, entry)
                        }
                        newDex, PAYLOAD_ASSET, CONFIG_ASSET -> {
                            // 原包里若已有同名条目，跳过（稍后统一写）
                        }
                        else -> {
                            val data = zin.getInputStream(entry).use { it.readBytes() }
                            writeEntry(zout, name, data, entry)
                        }
                    }
                }
                // 新增条目
                writeNew(zout, newDex, payload.bootDex)
                writeNew(zout, PAYLOAD_ASSET, payload.payloadDex)
                writeNew(zout, CONFIG_ASSET, cfgBytes)
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────

    private fun readPackageName(manifest: ByteArray): String? {
        // 复用 AxmlManifest 的解析（它已有实现），这里只取 package
        val tmp = File.createTempFile("mf", ".xml")
        return try {
            tmp.writeBytes(manifest)
            com.xixin.apkforge.core.axml.AxmlManifest.parse(tmp).packageName
        } finally {
            tmp.delete()
        }
    }

    private fun nextDexName(existing: List<String>): String {
        var max = 1   // classes.dex 视为 1
        for (n in existing) {
            val m = Regex("classes(\\d*)\\.dex").find(n) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: 1
            if (idx > max) max = idx
        }
        return "classes${max + 1}.dex"
    }

    /**
     * 写一个原包条目。
     *
     * ⚠️ STORED 条目必须显式给 size 与 crc，否则 `ZipOutputStream` 会抛
     * `invalid entry size`。APK 里大量资源（尤其 res/ 下的图）本来就是
     * STORED，照搬 method 却不给这两项，会在写到第一个未压缩条目时失败
     * —— 症状是产物只有几百字节的残壳，且异常信息与"压缩"毫无关联。
     */
    private fun writeEntry(zout: ZipOutputStream, name: String, data: ByteArray, src: ZipEntry) {
        val e = ZipEntry(name).apply {
            method = src.method
            time = src.time
            if (method == ZipEntry.STORED) {
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = crc32(data)
            }
        }
        zout.putNextEntry(e)
        zout.write(data)
        zout.closeEntry()
    }

    private fun crc32(data: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(data)
        return c.value
    }

    private fun writeNew(zout: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = 0L   // 固定时间戳，让同输入产出逐字节稳定的包
        }
        zout.putNextEntry(e)
        zout.write(data)
        zout.closeEntry()
    }

    private fun selfCheck(apk: File, newDex: String, cfgSize: Int) {
        ZipFile(apk).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            if (newDex !in names) throw IllegalStateException("产物缺 $newDex")
            if (PAYLOAD_ASSET !in names) throw IllegalStateException("产物缺 $PAYLOAD_ASSET")
            if (CONFIG_ASSET !in names) throw IllegalStateException("产物缺 $CONFIG_ASSET")

            // Manifest 必须指向 SigFixApp
            val mf = zf.getInputStream(zf.getEntry(MANIFEST)).use { it.readBytes() }
            val app = AxmlEditor.getAppName(mf)
            if (app != SIGFIX_APP) throw IllegalStateException("Manifest 的 android:name 不是 $SIGFIX_APP")

            // 新 dex 的 magic 必须合法
            val dex = zf.getInputStream(zf.getEntry(newDex)).use { it.readBytes() }
            if (dex.size < 8 || String(dex, 0, 4, Charsets.US_ASCII) != "dex\n") {
                throw IllegalStateException("$newDex 不是合法 dex")
            }
            val cfg = zf.getInputStream(zf.getEntry(CONFIG_ASSET)).use { it.readBytes() }
            if (cfg.size != cfgSize) throw IllegalStateException("$CONFIG_ASSET 大小不符")
        }
    }

    /** 极简 JSON 字符串转义（只处理引号/反斜杠/控制字符）。 */
    private fun jsonStr(s: String): String {
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
}

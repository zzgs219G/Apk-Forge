package com.xixin.apkforge.data.uncheck

import android.content.Context
import android.util.Log
import com.xixin.apkforge.core.uncheck.UncheckPayload
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 去签名校验载荷的**获取与缓存**（唯一需要 Android API 的地方）。
 *
 * ## 分层原因
 *
 * `core/uncheck/UncheckPayload` 负责「解析索引 + 校验 sha256 + 格式自检」，
 * 全是纯 JVM 逻辑，能在手机上跑单测。本类只负责「把字节弄到本地」：
 * 网络、`Context.filesDir`、`Log`。
 *
 * 这条分界是 `AGENTS.md` §1.1 的要求 —— 下载逻辑放 `core/` 会让
 * 「索引解析错不错」这件事只能靠点 UI 验证，而出错时只看到「载荷获取失败」。
 *
 * ## 安全前提：必须校验 sha256
 *
 * 这四个文件会被**注入到别人的 App 里并执行**。不校验 = 信任整条网络链路。
 * 所以：下载后先算 sha256，与索引比对不过就**直接丢弃**，绝不「先用着再说」。
 * 校验失败时报清楚是哪个文件、期望与实际值，不要只说「校验失败」。
 *
 * ## 缓存策略
 *
 * 按索引的 `version` 分目录：`filesDir/uncheck/<version>/`。
 * 同版本不重复下载；版本变了才重拉。version 由 json 仓库的同步脚本
 * **按内容派生**，所以「载荷改了」必然导致「version 变了」→ 缓存自动失效。
 * 手写版本号会漏，那样用户会一直用旧载荷（2026-11 加 native 层就是这么漏的）。
 */
class PayloadFetcher(private val context: Context) {

    private val app = context.applicationContext
    private val root = File(app.filesDir, "uncheck")

    /** 载荷获取结果。 */
    data class Fetched(
        val payload: UncheckPayload.Payload,
        /** true = 走本地缓存，没有联网 */
        val fromCache: Boolean,
    )

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 拿到可用载荷。缓存优先，缺失或不完整才联网。
     *
     * @param forceRefresh 忽略缓存强制重下
     */
    fun acquire(forceRefresh: Boolean = false): Fetched {
        if (!forceRefresh) {
            cachedOrNull()?.let { return Fetched(it, fromCache = true) }
        }

        Log.i(TAG, "开始拉取索引: $BASE/$INDEX")
        val indexText = httpGetText("$BASE/$INDEX")
        val index = UncheckPayload.parseIndex(indexText)
        Log.i(TAG, "索引版本 ${index.version}，文件 ${index.files.keys}")

        val dir = File(root, index.version)

        // 先尝试「目录已在且校验通过」——forceRefresh 时也复用已下好的文件，
        // 只重新拉索引。这避免了「点了刷新就重下 290KB」这种无意义流量。
        readFromDir(dir, index)?.let {
            Log.i(TAG, "本地目录 ${dir.name} 校验通过，直接使用")
            return Fetched(it, fromCache = true)
        }

        // 需要下载：清掉半旧半新的文件
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }

        // 索引副本也落盘：离线启动时要用它校验 sha256，
        // 没这份副本就只能在「不校验」和「不能用」之间二选一。
        File(dir, INDEX).writeText(indexText)

        val downloaded = HashMap<String, ByteArray>(UncheckPayload.REQUIRED.size)
        for (name in UncheckPayload.REQUIRED) {
            Log.i(TAG, "下载 $name …")
            val bytes = httpGetBytes("$BASE/$name")
            if (bytes.isEmpty()) {
                throw FetchException("$name 下载到 0 字节")
            }
            File(dir, name).writeBytes(bytes)
            downloaded[name] = bytes
        }

        val payload = buildPayload(index, downloaded)

        // 落盘后再校验一次（防止写到一半被截断 / 磁盘问题）
        val problems = UncheckPayload.validate(index, payload)
        if (problems.isNotEmpty()) {
            // 校验不过就整个目录清掉：留着半坏的文件只会让下次又「命中缓存」
            dir.deleteRecursively()
            throw FetchException(
                "载荷校验失败（已丢弃，不会使用）：\n  " + problems.joinToString("\n  ")
            )
        }

        Log.i(TAG, "载荷就绪 v${index.version}：" +
            payload.sizes().entries.joinToString { "${it.key}=${it.value}B" })
        return Fetched(payload, fromCache = false)
    }

    /**
     * 只读缓存，不联网。离线场景下这是唯一路径。
     *
     * 扫 `uncheck/` 下所有版本目录，取**最新修改**的那个能通过校验的。
     * 不联网就无法知道「最新版本是谁」，所以按修改时间取 —— 刚下载的目录
     * mtime 最新，符合直觉。
     */
    fun cachedOrNull(): UncheckPayload.Payload? {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        if (dirs.isEmpty()) return null

        // 没有索引就没法校验 sha256。缓存目录里存一份索引副本，避免联网。
        for (d in dirs.sortedByDescending { it.lastModified() }) {
            val idxFile = File(d, INDEX)
            if (!idxFile.isFile) continue
            val index = runCatching {
                UncheckPayload.parseIndex(idxFile.readText())
            }.getOrNull() ?: continue
            // 目录名必须与索引版本一致，否则可能是半成品
            if (d.name != index.version) continue
            readFromDir(d, index)?.let { return it }
        }
        return null
    }

    /** 从目录读四个文件并校验；任一缺失/不符返回 null（不抛异常）。 */
    private fun readFromDir(dir: File, index: UncheckPayload.Index): UncheckPayload.Payload? {
        if (!dir.isDirectory) return null
        val bytes = HashMap<String, ByteArray>(UncheckPayload.REQUIRED.size)
        for (name in UncheckPayload.REQUIRED) {
            val f = File(dir, name)
            if (!f.isFile) return null
            bytes[name] = try {
                f.readBytes()
            } catch (_: IOException) {
                return null
            }
        }
        val payload = buildPayload(index, bytes)
        return if (UncheckPayload.validate(index, payload).isEmpty()) payload else null
    }

    private fun buildPayload(
        index: UncheckPayload.Index,
        byName: Map<String, ByteArray>,
    ): UncheckPayload.Payload {
        fun need(name: String): ByteArray = byName[name]
            ?: throw FetchException("载荷缺文件 $name（这个不该发生，请反馈）")
        return UncheckPayload.Payload(
            version = index.version,
            bootDex = need(UncheckPayload.BOOT_DEX),
            payloadDex = need(UncheckPayload.PAYLOAD_DEX),
            usignArm64 = need(UncheckPayload.USIGN_ARM64),
            usignArm32 = need(UncheckPayload.USIGN_ARM32),
        )
    }

    private fun httpGetText(url: String): String =
        String(httpGetBytes(url), Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                // 部分 CDN 会拦默认 UA
                setRequestProperty("User-Agent", "ApkForge")
            }
        } catch (e: Exception) {
            throw FetchException("网络请求失败（${e::class.simpleName}）：${e.message}", e)
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw FetchException(
                    when (code) {
                        404 -> "载荷文件不存在（HTTP 404）：$url\n" +
                            "  可能 json 仓库还没同步。去 artifact-pipeline 改完载荷后，" +
                            "要等 uncheck-payload 工作流跑完。"
                        else -> "下载失败 HTTP $code：$url"
                    }
                )
            }
            return conn.inputStream.use { it.readBytesCompat() }
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException("下载异常（${e::class.simpleName}）：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesCompat(): ByteArray {
        val buf = java.io.ByteArrayOutputStream(64 * 1024)
        val tmp = ByteArray(64 * 1024)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    private companion object {
        const val TAG = "UncheckPayload"

        /**
         * 载荷镜像地址。
         *
         * 镜像到 `zzgs219/json` 而不是直连 artifact-pipeline：
         * ① 源仓库的可见性不由本工程控制（旧名 apk-protect-action 转私有
         *    导致匿名拉取 404 就是前车之鉴）；
         * ② 载荷会注入到别人的 App 里执行，分发链路越少越可控。
         */
        const val BASE = "https://raw.giteeusercontent.com/zzgs219/json/raw/main/backend/uncheck"

        const val INDEX = "index.json"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

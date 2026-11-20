package com.xixin.apkforge.core.uncheck

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * PayloadRepo —— 去签名校验载荷（两份 dex）的获取、校验与缓存。
 *
 * ## 为什么载荷要"下载"而不是打进 App
 *
 * 载荷决定"怎么绕过签名校验"。它会随目标 App 的行为、Android 版本
 * 不断调整；打进 ApkForge 意味着**每改一次载荷就要发一次 App 版本**。
 * 改成下载后，只需往仓库推新 dex，所有已装用户立刻拿到。
 *
 * ## 安全前提：必须校验 sha256
 *
 * 这两份 dex 会被**注入到别人的 App 里并执行**。如果下载链路被劫持，
 * 等于把任意代码塞进用户处理的所有 APK。所以：
 *
 * - `index.json` 里带每个文件的 sha256，下载后先校验再落盘；
 * - 校验不过直接丢弃，绝不"先用着再说"；
 * - 这一点不能为了省事跳过 —— 它是整个远程载荷方案成立的前提。
 *
 * ## 缓存策略
 *
 * 按 `index.json` 的 `version` 分目录缓存：
 *
 * ```
 * filesDir/uncheck/<version>/boot.dex
 * filesDir/uncheck/<version>/payload.dex
 * ```
 *
 * 同版本不重复下载；版本变了才重拉。这样既省流量，又保证
 * "换载荷后用户真的会拿到新版"。
 */
object PayloadRepo {

    private const val TAG = "UncheckPayload"

    /** 镜像（与 App 拉 schema 同一套基础设施） */
    private const val BASE =
        "https://raw.giteeusercontent.com/zzgs219/json/raw/main/backend/uncheck"

    private const val INDEX = "index.json"
    private const val BOOT_DEX = "boot.dex"
    private const val PAYLOAD_DEX = "payload.dex"

    class PayloadException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** 索引内容。 */
    data class Index(
        val version: String,
        val files: Map<String, Entry>,
    ) {
        data class Entry(val sha256: String, val size: Long)
    }

    /** 已就绪的载荷。 */
    data class Payload(
        val version: String,
        val bootDex: ByteArray,
        val payloadDex: ByteArray,
    )

    /**
     * 拿到可用载荷。优先用缓存，缓存缺失或损坏才下载。
     *
     * @param forceRefresh 忽略缓存强制重下（调试用）
     */
    fun acquire(ctx: Context, forceRefresh: Boolean = false): Payload {
        val index = fetchIndex()
        Log.i(TAG, "索引版本 ${index.version}，文件 ${index.files.keys}")
        val dir = File(ctx.filesDir, "uncheck/${index.version}")

        if (!forceRefresh && cachedOk(dir, index)) {
            return readPayload(dir, index)
        }

        dir.mkdirs()
        // 清掉同目录旧文件，避免半旧半新
        dir.listFiles()?.forEach { it.delete() }

        download(BOOT_DEX, File(dir, BOOT_DEX))
        download(PAYLOAD_DEX, File(dir, PAYLOAD_DEX))

        // 落盘后再校验一次（防止写到一半被截断）
        if (!cachedOk(dir, index)) {
            // 逐个文件报清楚，别只说"校验失败"
            val detail = index.files.entries.joinToString("; ") { (name, e) ->
                val f = File(dir, name)
                val actual = if (f.isFile) sha256(f.readBytes()).take(12) else "缺失"
                "$name 期望 ${e.sha256.take(12)} 实际 $actual"
            }
            throw PayloadException("下载完成但校验失败：$detail")
        }
        return readPayload(dir, index)
    }

    /** 仅检查缓存是否可用，不联网。 */
    fun cachedPayloadOrNull(ctx: Context): Payload? {
        val root = File(ctx.filesDir, "uncheck")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        for (d in dirs) {
            val boot = File(d, BOOT_DEX)
            val pl = File(d, PAYLOAD_DEX)
            if (boot.isFile && pl.isFile) {
                return try {
                    Payload(d.name, boot.readBytes(), pl.readBytes())
                } catch (_: IOException) {
                    null
                }
            }
        }
        return null
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private fun fetchIndex(): Index {
        Log.i(TAG, "拉取索引: $BASE/$INDEX")
        val text = try {
            httpGet("$BASE/$INDEX")
        } catch (e: Exception) {
            Log.e(TAG, "索引拉取失败", e)
            throw e
        }
        Log.i(TAG, "索引内容: ${text.take(300)}")
        // 只做最小解析：不引第三方 JSON 库，字段固定且简单
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?: throw PayloadException("index.json 里没有 version 字段")

        val files = mutableMapOf<String, Index.Entry>()
        for (name in listOf(BOOT_DEX, PAYLOAD_DEX)) {
            val block = Regex(
                "\"${Regex.escape(name)}\"\\s*:\\s*\\{([^}]*)\\}"
            ).find(text)?.groupValues?.get(1)
                ?: throw PayloadException("index.json 里没有 $name 的条目")
            val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                .find(block)?.groupValues?.get(1)
                ?: throw PayloadException("index.json 里 $name 缺 sha256")
            val size = Regex("\"size\"\\s*:\\s*(\\d+)")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            files[name] = Index.Entry(sha.lowercase(), size)
        }
        return Index(version, files)
    }

    private fun cachedOk(dir: File, index: Index): Boolean {
        for ((name, entry) in index.files) {
            val f = File(dir, name)
            if (!f.isFile) return false
            if (entry.size >= 0 && f.length() != entry.size) return false
            if (sha256(f.readBytes()) != entry.sha256) return false
        }
        return true
    }

    private fun readPayload(dir: File, index: Index): Payload {
        val boot = File(dir, BOOT_DEX).readBytes()
        val pl = File(dir, PAYLOAD_DEX).readBytes()
        return Payload(index.version, boot, pl)
    }

    private fun download(name: String, dest: File) {
        Log.i(TAG, "下载 $name ...")
        val bytes = httpGetBytes("$BASE/$name")
        Log.i(TAG, "下载完成 $name: ${bytes.size} B")
        if (bytes.isEmpty()) throw PayloadException("$name 下载到 0 字节")
        dest.writeBytes(bytes)
    }

    private fun httpGet(url: String): String = String(httpGetBytes(url), Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        try {
            return httpGetBytesInner(url)
        } catch (e: PayloadException) {
            Log.e(TAG, "下载失败: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e::class.simpleName}: ${e.message}", e)
            throw PayloadException("网络请求失败（${e::class.simpleName}）：${e.message}", e)
        }
    }

    private fun httpGetBytesInner(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            // 某些 CDN 会拦默认 UA，显式给一个
            setRequestProperty("User-Agent", "ApkForge")
        }
        try {
            val code = conn.responseCode
            Log.i(TAG, "HTTP $code $url")
            if (code != HttpURLConnection.HTTP_OK) {
                throw PayloadException("下载失败 HTTP $code: $url")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

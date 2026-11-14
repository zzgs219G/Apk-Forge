package com.xixin.apkforge.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务记录 = 「这个包 + 这套配置」跑过一次留下的凭据。
 *
 * 【为什么要有它】用户真正的痛点是「同一个包反复选、反复配」，不是「看一眼日志」。
 * 记录里同时存了 **APK 私有副本** 和 **配置快照**，所以：
 *  · 换一套配置再注入，不用重新找文件；
 *  · 配置后来被改名/删除，历史记录仍然说得清当时注的是什么；
 *  · 产物路径一直在，随时 MT 定位 / 分享 / 重装。
 *
 * 【为什么 APK 要复制进 files/apks/】用户从 MT 选的包在 `cacheDir`，
 * 系统清缓存就没了，而 cacheDir 恰恰是最容易被清的地方。记录里存原路径
 * 等于存了个随时失效的指针——这和 `ConfigStore` 对 dex 的处理是同一个道理。
 * 复制按「文件名 + 字节数」去重，同一个包注入十次只占一份空间。
 *
 * 【为什么不用 Room】同 `ConfigStore`：几条记录、只按时间倒序列出，`org.json` 足够。
 */
class TaskStore(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "records").apply { mkdirs() }
    private val apkDir = File(app.filesDir, "apks").apply { mkdirs() }
    private val indexFile = File(dir, "records.json")

    data class Record(
        val id: String,
        val apkName: String,
        /** 私有副本路径；文件可能被用户清掉，用 [apkExists] 判断 */
        val apkPath: String,
        val apkSize: Long,
        val packageName: String? = null,
        val targetClass: String = "",
        /** 配置 id；配置被删后为 null，但 [configName] 快照仍在 */
        val configId: String? = null,
        val configName: String = "未套用配置",
        val targetMethod: String = "onCreate",
        val targetSignature: String = "(Landroid/os/Bundle;)V",
        val outputPath: String? = null,
        val ok: Boolean = false,
        val injectedCount: Int = 0,
        val durationMs: Long = 0L,
        val at: Long = System.currentTimeMillis(),
    ) {
        val apkFile: File? get() = apkPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val apkExists: Boolean get() = apkFile?.isFile == true
        val outputFile: File? get() = outputPath?.let { File(it) }
        val outputExists: Boolean get() = outputFile?.isFile == true
        val apkSizeMb: String get() = String.format("%.1f", apkSize / 1048576.0)
    }

    fun list(): List<Record> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Record? = list().firstOrNull { it.id == id }

    /**
     * 把用户选的 APK 收进私有目录并返回副本。
     *
     * 去重键是「文件名 + 字节数」：同名同大小基本就是同一个包，
     * 重复注入不再多占一份几十 MB；内容变了（同名不同大小）会另存一份。
     */
    fun importApk(source: File): File {
        val key = "${source.nameWithoutExtension}_${source.length()}"
            .replace(Regex("""[^\w.\-]"""), "_")
        val dst = File(apkDir, "$key.apk")
        if (dst.isFile && dst.length() == source.length()) return dst
        runCatching {
            source.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        }
        return if (dst.isFile) dst else source
    }

    fun add(record: Record): Record {
        persist(list().filterNot { it.id == record.id } + record)
        return record
    }

    /**
     * 删除记录。私有 APK 副本仅在没有其它记录引用时才删——
     * 同一个包的多条记录（换配置重跑）共用一份副本。
     */
    fun delete(id: String) {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return
        val rest = all.filterNot { it.id == id }
        persist(rest)
        val stillUsed = rest.any { it.apkPath == target.apkPath }
        if (!stillUsed && target.apkPath.startsWith(apkDir.absolutePath)) {
            File(target.apkPath).delete()
        }
    }

    /** 清空记录（不动产物文件，产物在 Download 里属于用户资产）。 */
    fun clear() {
        persist(emptyList())
    }

    private fun persist(items: List<Record>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        indexFile.writeText(arr.toString())
    }

    private fun toJson(r: Record): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("apkName", r.apkName)
        put("apkPath", r.apkPath)
        put("apkSize", r.apkSize)
        put("packageName", r.packageName ?: JSONObject.NULL)
        put("targetClass", r.targetClass)
        put("configId", r.configId ?: JSONObject.NULL)
        put("configName", r.configName)
        put("targetMethod", r.targetMethod)
        put("targetSignature", r.targetSignature)
        put("outputPath", r.outputPath ?: JSONObject.NULL)
        put("ok", r.ok)
        put("injectedCount", r.injectedCount)
        put("durationMs", r.durationMs)
        put("at", r.at)
    }

    private fun fromJson(o: JSONObject): Record = Record(
        id = o.getString("id"),
        apkName = o.optString("apkName", "unknown.apk"),
        apkPath = o.optString("apkPath", ""),
        apkSize = o.optLong("apkSize", 0L),
        packageName = if (o.isNull("packageName")) null else o.optString("packageName"),
        targetClass = o.optString("targetClass", ""),
        configId = if (o.isNull("configId")) null else o.optString("configId"),
        configName = o.optString("configName", "未套用配置"),
        targetMethod = o.optString("targetMethod", "onCreate"),
        targetSignature = o.optString("targetSignature", "(Landroid/os/Bundle;)V"),
        outputPath = if (o.isNull("outputPath")) null else o.optString("outputPath"),
        ok = o.optBoolean("ok", false),
        injectedCount = o.optInt("injectedCount", 0),
        durationMs = o.optLong("durationMs", 0L),
        at = o.optLong("at", 0L),
    )
}

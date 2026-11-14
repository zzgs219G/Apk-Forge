package com.xixin.apkforge.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「注入配置」= 一组调用 + 对应的 payload dex，作为一个可复用单元。
 *
 * 【为什么不用 Room】Room 要走 KSP 注解处理器，手机端构建时间明显变长，
 * 而这里只需要「存几条、列出来、改名、删掉」。`org.json` 是 Android 内置的，
 * 零依赖零处理器。数据量到几百条或需要按字段查询时再换数据库不迟。
 *
 * 【dex 必须复制进私有目录】用户从 MT 选的 dex 在 cacheDir 里，系统清缓存就没了。
 * 配置里存原路径等于存了个随时失效的指针。所以保存时复制到 `files/configs/<id>.dex`。
 *
 * 【为什么要单独一套 CRUD】配置页需要「改名 / 只看内容 / 单独删除」，
 * 这些操作不应该依赖主流程的当前状态。所以 store 层的接口按 id 操作，
 * 与 UI 当前编辑的内容完全解耦。
 */
class ConfigStore(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "configs").apply { mkdirs() }
    private val indexFile = File(dir, "configs.json")

    data class Config(
        val id: String,
        val name: String,
        val calls: String,
        /**
         * 注入模式。配置只管「注入什么 + 怎么铺」，不管「注到哪个类的哪个方法」——
         * 后者是包自己的属性（每个包的 Lifecycle 方法集合都不一样）。
         */
        val mode: com.xixin.apkforge.core.dex.InjectionMode =
            com.xixin.apkforge.core.dex.InjectionMode.ONCE,
        val dexPath: String? = null,
        /**
         * 要随 dex 一起写进宿主的额外资产：宿主包内路径 → 本机文件路径。
         *
         * 目前唯一的用途是弹窗载荷的 `assets/apkforge_dialog.json`。
         * 它是 JSON 而不是代码，所以不能交给「调用行」表达——
         * 没有这条，载荷会在运行时找不到配置，表现为「注入成功、不弹窗」。
         */
        val assets: Map<String, String> = emptyMap(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        val dexFile: File? get() = dexPath?.let { File(it) }
        val hasDex: Boolean get() = dexPath != null && File(dexPath).isFile

        /** 有效调用行数（忽略注释与空行） */
        val callCount: Int
            get() = calls.lineSequence().count { it.substringBefore('#').isNotBlank() }

        val dexSizeKb: Long
            get() = dexFile?.takeIf { it.isFile }?.length()?.div(1024) ?: 0

        /** 有几份资产文件还在磁盘上（丢了的要在 UI 上明说） */
        val assetsReady: Int
            get() = assets.count { (_, path) -> File(path).isFile }
    }

    fun list(): List<Config> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Config? = list().firstOrNull { it.id == id }

    /**
     * 新建或更新。
     * - [id] 为 null → 新建
     * - [id] 非 null → 更新；[dexSource] 为 null 时**保留原 dex**（用户可能只改名字）
     */
    fun upsert(
        id: String?,
        name: String,
        calls: String,
        mode: com.xixin.apkforge.core.dex.InjectionMode,
        assets: Map<String, String> = emptyMap(),
        dexSource: File?,
        keepExistingDexIfNull: Boolean = true,
    ): Config {
        val existing = id?.let { list().firstOrNull { c -> c.id == it } }
        val newId = id ?: "cfg_${System.currentTimeMillis()}_${(0..999).random()}"

        val storedDex: String? = when {
            dexSource != null -> {
                val dst = File(dir, "$newId.dex")
                runCatching {
                    // 若来源就是目标文件（从配置里选回去的），不重复拷
                    if (dexSource.absolutePath == dst.absolutePath) {
                        dst.absolutePath
                    } else {
                        dexSource.inputStream().use { i ->
                            dst.outputStream().use { o -> i.copyTo(o) }
                        }
                        dst.absolutePath
                    }
                }.getOrNull()
            }
            keepExistingDexIfNull && existing?.dexPath != null -> existing.dexPath
            else -> null
        }

        val cfg = Config(
            id = newId,
            name = name.ifBlank { "未命名配置" },
            calls = calls,
            mode = mode,
            assets = assets.ifEmpty { existing?.assets ?: emptyMap() },
            dexPath = storedDex,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        persist(list().filterNot { it.id == newId } + cfg)
        return cfg
    }

    /** 只改名字，不动其它字段（配置页的「重命名」）。 */
    fun rename(id: String, newName: String) {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return
        persist(
            all.map {
                if (it.id == id) {
                    it.copy(name = newName.ifBlank { "未命名配置" },
                        updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        )
        // target 仅用于确认存在
        check(target.id == id)
    }

    fun delete(id: String) {
        File(dir, "$id.dex").delete()
        persist(list().filterNot { it.id == id })
    }

    private fun persist(items: List<Config>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        indexFile.writeText(arr.toString())
    }

    private fun toJson(c: Config): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("calls", c.calls)
        put("mode", c.mode.name)
        put("assets", JSONObject().apply {
            c.assets.forEach { (k, v) -> put(k, v) }
        })
        put("dexPath", c.dexPath ?: JSONObject.NULL)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
    }

    private fun fromJson(o: JSONObject): Config = Config(
        id = o.getString("id"),
        name = o.optString("name", "未命名配置"),
        calls = o.optString("calls", ""),
        mode = com.xixin.apkforge.core.dex.InjectionMode.entries
            .firstOrNull { it.name == o.optString("mode", "ONCE") }
            ?: com.xixin.apkforge.core.dex.InjectionMode.ONCE,
        assets = o.optJSONObject("assets")?.let { obj ->
            buildMap { obj.keys().forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } } }
        } ?: emptyMap(),
        dexPath = if (o.isNull("dexPath")) null
        else o.optString("dexPath").takeIf { it.isNotBlank() },
        createdAt = o.optLong("createdAt", 0L),
        updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0L)),
    )
}

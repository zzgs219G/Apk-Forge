package com.xixin.apkforge.feature.config

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.SamplePayload
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.data.ConfigStore

/**
 * 配置页状态。
 *
 * 与主注入页完全独立：配置页只负责「增删改查一套配置」，
 * 不关心当前选的是哪个 APK。这样才能做到「先攒配置，再挑包注入」。
 */
data class ConfigEditorState(
    val configs: List<ConfigStore.Config> = emptyList(),

    /** 正在编辑的配置 id；null = 新建 */
    val editingId: String? = null,
    val name: String = "",
    val calls: String = "",
    /** 注入模式。注入点（哪个类的哪个方法）不在这里配——那是包自己的属性 */
    val mode: InjectionMode = InjectionMode.ONCE,
    val dexPath: String? = null,

    val callErrors: List<String> = emptyList(),
    val callCount: Int = 0,

    val message: String? = null,
    val dirty: Boolean = false,
) {
    val editingNew: Boolean get() = editingId == null
    val canSave: Boolean get() = calls.isNotBlank() && callErrors.isEmpty()
    val dexFile: File? get() = dexPath?.let { File(it) }
    val dexExists: Boolean get() = dexFile?.isFile == true
}

class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(ConfigEditorState())
        private set

    private val store = ConfigStore(app.applicationContext)

    fun refresh() {
        state = state.copy(configs = store.list())
    }

    fun startNew() {
        state = ConfigEditorState(
            configs = store.list(),
            editingId = null,
            name = "",
            calls = "",
            mode = InjectionMode.ONCE,
            dexPath = null,
            dirty = false,
        )
    }

    fun edit(cfg: ConfigStore.Config) {
        state = state.copy(
            editingId = cfg.id,
            name = cfg.name,
            calls = cfg.calls,
            mode = cfg.mode,
            dexPath = cfg.dexPath?.takeIf { File(it).isFile },
            callErrors = emptyList(),
            callCount = cfg.callCount,
            dirty = false,
            message = if (cfg.dexPath != null && !File(cfg.dexPath).isFile) {
                "该配置的 dex 文件已丢失，请重新选择"
            } else {
                null
            },
        )
    }

    fun setName(v: String) {
        state = state.copy(name = v, dirty = true)
    }

    fun setCalls(v: String) {
        val (calls, errors) = SmaliCallParser.parseAll(v)
        state = state.copy(
            calls = v,
            callErrors = errors.messages,
            callCount = calls.size,
            dirty = true,
        )
    }

    fun setMode(m: InjectionMode) {
        state = state.copy(mode = m, dirty = true)
    }

    fun setDex(file: File?) {
        state = state.copy(dexPath = file?.absolutePath, dirty = true)
    }

    fun loadSampleDex() {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val d = File(getApplication<Application>().cacheDir, "sample").apply { mkdirs() }
                val out = File(d, "DemoDialog.dex")
                getApplication<Application>().assets.open("sample/DemoDialog.dex").use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                out
            }
            state = state.copy(
                dexPath = f.absolutePath,
                calls = if (state.calls.isBlank()) SamplePayload.CALL else state.calls,
                callCount = if (state.calls.isBlank()) 1 else state.callCount,
                dirty = true,
            )
        }
    }

    /** 保存当前编辑内容。返回保存后的配置。 */
    fun save(onDone: (ConfigStore.Config) -> Unit = {}) {
        if (!state.canSave) {
            state = state.copy(message = "调用行不能为空，且不能有语法错误")
            return
        }
        viewModelScope.launch {
            val cfg = withContext(Dispatchers.IO) {
                store.upsert(
                    id = state.editingId,
                    name = state.name,
                    calls = state.calls,
                    mode = state.mode,
                    dexSource = state.dexFile?.takeIf { it.isFile },
                    // 编辑已有配置时，用户没重新选 dex → 保留原 dex
                    keepExistingDexIfNull = true,
                )
            }
            state = state.copy(
                configs = store.list(),
                editingId = cfg.id,
                name = cfg.name,
                dirty = false,
                message = "已保存：${cfg.name}",
            )
            onDone(cfg)
        }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, newName) }
            state = state.copy(configs = store.list(), message = "已重命名")
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            val wasEditing = state.editingId == id
            state = state.copy(
                configs = store.list(),
                editingId = if (wasEditing) null else state.editingId,
                message = "已删除",
            )
        }
    }

    fun duplicate(cfg: ConfigStore.Config) {
        viewModelScope.launch {
            val copy = withContext(Dispatchers.IO) {
                store.upsert(
                    id = null,
                    name = "${cfg.name} 副本",
                    calls = cfg.calls,
                    mode = cfg.mode,
                    dexSource = cfg.dexFile,
                )
            }
            state = state.copy(configs = store.list(), message = "已复制：${copy.name}")
        }
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }

    fun showMessageSafely(m: String) {
        state = state.copy(message = m)
    }
}

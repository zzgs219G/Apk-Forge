package com.xixin.apkforge.feature.plugin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.PluginPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PluginUiState(
    val form: PluginPack.DialogForm = PluginPack.DialogForm(),
    /** 表单转出来的 JSONC 文本，供预览与复制 */
    val jsonc: String = "",
    val dexSize: Long = 0L,
    val message: String? = null,
    /** 已导出到 Download 的 zip 路径 */
    val exportedZip: String? = null,
    val busy: Boolean = false,
)

/**
 * 插件包 VM。
 *
 * 【为什么单独一个 VM】插件包做的不是注入，而是「造载荷」：
 * 它不需要 APK、不需要注入点、不需要设备权限，只需要一份 dex 和一份配置。
 * 塞进配置页会让「配置」的概念同时指两件事（注入什么 / 弹窗长什么样）。
 *
 * 【导出做了什么】三件事一起：
 *   1. 把表单写成 JSONC；
 *   2. 和内置 dex 一起打成 zip（给别的手机 / 给别人用）；
 *   3. 顺手建一条**配置**——这样回到注入流程就能直接用，
 *      不必再手工粘调用行、手工选 dex。用户说「导出 dex」，真正想要的是「能用」。
 */
class PluginViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(PluginUiState())
        private set

    private val configs = ConfigStore(app.applicationContext)

    init {
        viewModelScope.launch {
            val dex = withContext(Dispatchers.IO) {
                runCatching { PluginPack.bundledDex(getApplication()) }.getOrNull()
            }
            state = state.copy(dexSize = dex?.size?.toLong() ?: 0L)
            refreshJsonc()
        }
    }

    fun setTitle(v: String) = update { it.copy(title = v) }
    fun setMessage(v: String) = update { it.copy(message = v) }
    fun setTitleColor(v: String) = update { it.copy(titleColor = v) }
    fun setMessageColor(v: String) = update { it.copy(messageColor = v) }
    fun setCancelable(v: Boolean) = update { it.copy(cancelable = v) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }

    fun setButton(index: Int, b: PluginPack.DialogButton) = update {
        it.copy(buttons = it.buttons.mapIndexed { i, old -> if (i == index) b else old })
    }

    fun addButton() = update {
        val used = it.buttons.map { b -> b.slot }.toSet()
        val slot = listOf(0, 1, 2).firstOrNull { s -> !used.contains(s) } ?: 2
        it.copy(buttons = it.buttons + PluginPack.DialogButton(slot = slot, text = "按钮${it.buttons.size + 1}"))
    }

    fun removeButton(index: Int) = update {
        // 只许删到剩一个：零按钮的 AlertDialog 在某些 ROM 上关不掉
        if (it.buttons.size <= 1) it else it.copy(buttons = it.buttons.filterIndexed { i, _ -> i != index })
    }

    fun resetToDefault() = update { PluginPack.DialogForm() }

    private fun update(f: (PluginPack.DialogForm) -> PluginPack.DialogForm) {
        state = state.copy(form = f(state.form))
        refreshJsonc()
    }

    private fun refreshJsonc() {
        state = state.copy(jsonc = PluginPack.toJsonc(state.form))
    }

    /**
     * 导出：zip + 建配置。
     *
     * 建配置是这里最容易被忽略但最有价值的一步——导出完立刻能注入，
     * 才算真的「能用」。配置名固定带前缀，方便用户在配置列表里认出来。
     */
    fun export(onDone: () -> Unit = {}) {
        if (state.busy) return
        state = state.copy(busy = true, message = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val jsonc = PluginPack.toJsonc(state.form)
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dex = PluginPack.bundledDex(app)
                    if (!PluginPack.isDex(dex)) error("内置载荷 dex 损坏（魔数不对）")
                    val zip = PluginPack.buildZip(dex, jsonc)
                    val saved = com.xixin.apkforge.data.OutputStore.saveFile(
                        app, zip, PluginPack.ZIP_NAME, "application/zip"
                    )
                    val (dexFile, cfgFile) = PluginPack.exportTo(
                        app, File(app.filesDir, "plugin"), dex, jsonc
                    )
                    val cfg = configs.upsert(
                        id = null,
                        name = "弹窗载荷 · ${state.form.title.take(12).ifBlank { "未命名" }}",
                        calls = PluginPack.CALL,
                        mode = com.xixin.apkforge.core.dex.InjectionMode.ONCE,
                        // 配置写进宿主的 assets/：载荷在运行时从这里读，改文案不用重编 dex
                        assets = mapOf(PluginPack.HOST_ASSET_PATH to cfgFile.absolutePath),
                        dexSource = dexFile,
                        keepExistingDexIfNull = true,
                    )
                    saved.file.absolutePath to cfg
                }
            }
            state = if (out.isSuccess) {
                val (zip, cfg) = out.getOrThrow()
                state.copy(
                    busy = false,
                    exportedZip = zip,
                    message = "已导出 $zip，并新建配置「${cfg.name}」",
                )
            } else {
                state.copy(busy = false, message = "导出失败：${out.exceptionOrNull()?.message}")
            }
            if (out.isSuccess) onDone()
        }
    }

    fun showMessage(m: String) {
        state = state.copy(message = m)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}

package com.xixin.apkforge.feature.plugin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.PayloadBaker
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.data.PluginPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PluginUiState(
    val form: PluginPack.DialogForm = PluginPack.DialogForm(),
    /** 表单转出来的 JSON —— 就是会被烘焙进 dex 的那一份，供预览与复制 */
    val json: String = "",
    /** 模板 dex 体积（占位符版），用来给用户一个「载荷多大」的量级 */
    val templateSize: Long = 0L,
    /** 最近一次烘焙产出的 dex 体积 */
    val bakedSize: Long = 0L,
    val message: String? = null,
    /** 已导出到 Download 的 zip 路径 */
    val exportedZip: String? = null,
    val busy: Boolean = false,
) {
    /** 阻断导出的原因（UI 直接照这个说事，不要各写一套判断）。 */
    val blockers: List<String>
        get() = buildList {
            if (!form.valid) add("标题和正文至少写一个")
            if (!form.anyButtonOn) add("至少留一个按钮：三个都关会变成关不掉的弹窗")
            form.badButtons.forEach { b ->
                add(
                    "${PluginPack.slotLabel(b.slot)}按钮选了「${b.actionLabel}」，但没填" +
                        if (b.action == "网址") "网址" else "QQ 号"
                )
            }
        }

    val canExport: Boolean get() = blockers.isEmpty() && !busy
}

/**
 * 插件包 VM：把「弹窗长什么样」编译成「一段能注入的 dex」。
 *
 * 【为什么单独一个 VM 而不是塞进配置页】插件包做的不是注入，是造载荷：
 * 它不需要 APK、不需要注入点、不需要设备信息。塞进配置页会让「配置」
 * 同时指两件事（注入什么 / 弹窗长什么样），用户没法判断改哪个。
 *
 * 【导出的四步，顺序不能变】
 *   1. 表单 → JSON（含注释，人能读）
 *   2. 读模板 dex，把 JSON **烘焙**进 `PayloadConfig.baked()`
 *   3. dex 落私有目录 + zip 落 Download（zip 只有 dex 与调用行）
 *   4. 建一条配置指向这份 dex —— 回到控制台就能直接注入
 * 第 4 步最容易被漏，但它是「导出完真能用」与「导出完还得自己接一遍」的分界。
 */
class PluginViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(PluginUiState())
        private set

    private val configs = ConfigStore(app.applicationContext)

    init {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching { PluginPack.bundledDex(getApplication()).size.toLong() }.getOrDefault(0L)
            }
            state = state.copy(templateSize = size)
            refreshJson()
        }
    }

    // ────────────────────── 表单编辑 ──────────────────────

    fun setTitle(v: String) = update { it.copy(title = v) }
    fun setTitleColor(v: String) = update { it.copy(titleColor = v) }
    fun setMessage(v: String) = update { it.copy(message = v) }
    fun setMessageColor(v: String) = update { it.copy(messageColor = v) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setSmart(v: Boolean) = update { it.copy(smart = v) }
    fun setSilentDays(v: Int) = update { it.copy(silentDays = v.coerceIn(0, 365)) }
    fun setVersion(v: String) = update { it.copy(version = v) }
    fun setForced(v: Boolean) = update { it.copy(forced = v) }
    fun setOutsideClose(v: Boolean) = update { it.copy(outsideClose = v) }
    fun setStyle(v: String) = update { it.copy(style = v) }
    fun setDimBehind(v: Boolean) = update { it.copy(dimBehind = v) }

    fun setButton(slot: Int, b: PluginPack.Button) = update {
        val exists = it.buttons.any { old -> old.slot == slot }
        it.copy(
            buttons = if (exists) {
                it.buttons.map { old -> if (old.slot == slot) b else old }
            } else {
                it.buttons + b
            }.sortedBy { old -> old.slot }
        )
    }

    fun resetToDefault() = update { PluginPack.DialogForm() }

    /** 直接粘一份 7.0 的配置进来：字段名兼容，动作名会被归一化。 */
    fun importJson(text: String) {
        state = state.copy(form = PluginPack.fromJson(text))
        refreshJson()
        state = state.copy(message = "已按粘贴的配置重建表单")
    }

    private fun update(f: (PluginPack.DialogForm) -> PluginPack.DialogForm) {
        state = state.copy(form = f(state.form))
        refreshJson()
    }

    private fun refreshJson() {
        state = state.copy(json = PluginPack.toJson(state.form))
    }

    // ────────────────────── 导出 ──────────────────────

    fun export(onDone: () -> Unit = {}) {
        if (!state.canExport) {
            state = state.copy(message = state.blockers.firstOrNull() ?: "还不能导出")
            return
        }
        state = state.copy(busy = true, message = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val form = state.form
            val json = PluginPack.toJson(form)

            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val template = PluginPack.bundledDex(app)
                    if (!PluginPack.isDex(template)) error("内置载荷 dex 损坏（魔数不对）")
                    val baked = when (val r = PayloadBaker.bake(template, json)) {
                        is PayloadBaker.Outcome.Ok -> r.dex
                        is PayloadBaker.Outcome.Failed -> error(r.reason)
                    }
                    val zip = PluginPack.buildZip(baked)
                    val saved = OutputStore.saveFile(app, zip, PluginPack.ZIP_NAME, "application/zip")
                    val dexFile = PluginPack.exportDex(File(app.filesDir, "plugin"), baked)
                    val cfg = configs.upsert(
                        id = null,
                        name = "弹窗 · ${form.title.take(12).ifBlank { "未命名" }}",
                        calls = PluginPack.CALL,
                        mode = InjectionMode.ONCE,
                        // 没有 assets 参数可传：配置在 dex 里，宿主包里只多一个 classesN.dex
                        dexSource = dexFile,
                        keepExistingDexIfNull = true,
                    )
                    Triple(saved.file.absolutePath, cfg, baked.size.toLong())
                }
            }
            state = if (out.isSuccess) {
                val (zip, cfg, size) = out.getOrThrow()
                state.copy(
                    busy = false,
                    exportedZip = zip,
                    bakedSize = size,
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

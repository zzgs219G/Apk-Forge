package com.xixin.apkforge.feature.inject

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.ui.anim.InjectAnimationStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.core.dex.ClassInspector
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.core.dex.SamplePayload
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.core.dex.ExistingMethod


/** 注入进行到哪一阶段，用于驱动进度动画。 */
enum class Phase(val label: String) {
    IDLE("待命"),
    READING("读取 APK"),
    PARSING("解析 Manifest"),
    LOCATING("定位 dex"),
    INJECTING("注入调用"),
    REPACKING("重打包"),
    SAVING("写入 Download"),
    DONE("完成"),
    FAILED("失败"),
}

data class InjectUiState(
    val apkFile: File? = null,

    val targetClass: String = "",
    val launcherCandidates: List<String> = emptyList(),
    val targetMethod: String = "onCreate",
    /** 注入点签名。由生命周期下拉框自动带出，不暴露给用户手填。 */
    val targetSignature: String = "(Landroid/os/Bundle;)V",

    val callsText: String = "",
    val payloadDex: File? = null,

    val configs: List<ConfigStore.Config> = emptyList(),
    val activeConfigId: String? = null,

    val parsingApk: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val steps: List<ApkInjectPipeline.Step> = emptyList(),
    val result: ApkInjectPipeline.Result? = null,
    val savedOutput: OutputStore.Saved? = null,
    val message: String? = null,

    val callErrors: List<String> = emptyList(),
    val callCount: Int = 0,

    /** 目标类里已有的方法（选完 APK + 类名后探测出来） */
    val classMethods: List<ExistingMethod> = emptyList(),
    val classSuperType: String? = null,
    val classFound: Boolean = false,
    /** 可选的生命周期注入点（按父类类型给） */
    val lifecyclePoints: List<LifecyclePoints.Point> = LifecyclePoints.ACTIVITY,

    /** 动画风格 */
    val animationStyle: InjectAnimationStyle = InjectAnimationStyle.RING,
) {
    val missing: List<String>
        get() = buildList {
            if (apkFile == null) add("选 APK")
            if (targetClass.isBlank()) add("注入点类名")
            if (callsText.isBlank()) add("调用行")
            if (callErrors.isNotEmpty()) add("修正调用行错误")
        }

    val canRun: Boolean get() = missing.isEmpty() && !running && !parsingApk

    val hasConfigContent: Boolean get() = callsText.isNotBlank()

    /** 当前套用的配置名（用于顶栏副标题） */
    val activeConfigName: String?
        get() = activeConfigId?.let { id -> configs.firstOrNull { it.id == id }?.name }

    /** 当前选中的注入点方法是否已存在于类中（否则会被新建） */
    fun pointExists(p: LifecyclePoints.Point): Boolean =
        classMethods.any { it.name == p.name && it.signature == p.signature }
}

class InjectViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(InjectUiState())
        private set

    private val pipeline = ApkInjectPipeline(app.applicationContext)
    private val store = ConfigStore(app.applicationContext)

    init {
        state = state.copy(configs = store.list())
    }

    fun selectApk(file: File) {
        state = state.copy(apkFile = file, parsingApk = true, result = null,
            savedOutput = null, steps = emptyList(), phase = Phase.PARSING)

        viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) { pipeline.peekManifest(file) }
            val launchers = manifest?.launcherActivities ?: emptyList()
            val cls = state.targetClass.ifBlank { launchers.firstOrNull() ?: "" }

            // 选完 APK 就顺带探测目标类已有方法，让注入点下拉框能标出「已有/将新建」
            val inspect = if (cls.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    ClassInspector.inspect(file, "L${cls.replace('.', '/')};")
                }
            } else null

            state = state.copy(
                parsingApk = false,
                phase = Phase.IDLE,
                launcherCandidates = launchers,
                targetClass = cls,
                classFound = inspect?.found == true,
                classSuperType = inspect?.superType,
                classMethods = inspect?.methods ?: emptyList(),
                lifecyclePoints = LifecyclePoints.forSuperClass(inspect?.superType),
                message = when {
                    manifest == null -> "无法解析 AndroidManifest"
                    launchers.isEmpty() -> "Manifest 里没有 LAUNCHER，请手动填注入点"
                    inspect?.found != true -> "已填入 ${launchers.first()}，但包里找不到该类"
                    else -> "已填入注入点：${launchers.first()}"
                },
            )
        }
    }

    fun clearApk() {
        state = state.copy(
            apkFile = null, launcherCandidates = emptyList(),
            classMethods = emptyList(), classFound = false, classSuperType = null,
        )
    }

    /** 用户改了类名 → 重新探测方法。防抖由 UI 的 LaunchedEffect 负责。 */
    fun setTargetClass(v: String) {
        state = state.copy(targetClass = v)
        val apk = state.apkFile ?: return
        if (v.isBlank()) {
            state = state.copy(classMethods = emptyList(), classFound = false)
            return
        }
        viewModelScope.launch {
            val inspect = withContext(Dispatchers.IO) {
                ClassInspector.inspect(apk, "L${v.trim().replace('.', '/')};")
            }
            // 只在类名没被再次改动时落地结果
            if (state.targetClass.trim() == v.trim()) {
                state = state.copy(
                    classFound = inspect.found,
                    classSuperType = inspect.superType,
                    classMethods = inspect.methods,
                    lifecyclePoints = LifecyclePoints.forSuperClass(inspect.superType),
                )
            }
        }
    }

    fun setAnimationStyle(s: InjectAnimationStyle) {
        state = state.copy(animationStyle = s)
    }

    fun setTargetMethod(v: String) {
        state = state.copy(targetMethod = v)
    }

    /** 选了一个生命周期点：同时设定方法名与签名。 */
    fun selectLifecyclePoint(p: LifecyclePoints.Point) {
        state = state.copy(targetMethod = p.name, targetSignature = p.signature)
    }

    fun setCallsText(v: String) {
        val (calls, errors) = SmaliCallParser.parseAll(v)
        state = state.copy(
            callsText = v,
            callErrors = errors.messages,
            callCount = calls.size,
        )
    }

    fun setPayloadDex(f: File) {
        state = state.copy(payloadDex = f)
    }

    fun clearPayloadDex() {
        state = state.copy(payloadDex = null)
    }

    /** 取消套用配置（不清空已填内容，只解除关联） */
    fun clearActiveConfig() {
        state = state.copy(activeConfigId = null)
    }

    // ────────────── 配置 ──────────────

    fun saveCurrentAsConfig(name: String) {
        viewModelScope.launch {
            val cfg = withContext(Dispatchers.IO) {
                store.upsert(
                    id = state.activeConfigId,
                    name = name,
                    calls = state.callsText,
                    targetMethod = state.targetMethod,
                    targetSignature = state.targetSignature,
                    dexSource = state.payloadDex,
                )
            }
            state = state.copy(
                configs = store.list(),
                activeConfigId = cfg.id,
                message = "配置已保存：${cfg.name}",
            )
        }
    }

    fun applyConfig(cfg: ConfigStore.Config) {
        val dex = cfg.dexFile?.takeIf { it.isFile }
        val (calls, errors) = SmaliCallParser.parseAll(cfg.calls)
        state = state.copy(
            activeConfigId = cfg.id,
            callsText = cfg.calls,
            targetMethod = cfg.targetMethod,
            payloadDex = dex,
            callErrors = errors.messages,
            callCount = calls.size,
            message = "已载入配置：${cfg.name}" +
                if (dex == null && cfg.dexPath != null) "（dex 文件已丢失，请重新选择）" else "",
        )
    }

    fun deleteConfig(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            state = state.copy(
                configs = store.list(),
                activeConfigId = state.activeConfigId.takeIf { it != id },
            )
        }
    }

    fun loadSampleDex() {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "sample").apply { mkdirs() }
                val out = File(dir, "DemoDialog.dex")
                getApplication<Application>().assets.open("sample/DemoDialog.dex").use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                out
            }
            setCallsText(if (state.callsText.isBlank()) SamplePayload.CALL else state.callsText)
            state = state.copy(payloadDex = f, message = "已载入示例弹窗")
        }
    }

    // ────────────── 执行 ──────────────

    fun run() {
        val apk = state.apkFile ?: return
        val app = getApplication<Application>()
        state = state.copy(
            running = true, steps = emptyList(), result = null,
            savedOutput = null, message = null, phase = Phase.READING
        )

        viewModelScope.launch {
            // 中间产物写私有目录，最终再搬进 Download
            val work = File(app.cacheDir, "work").apply { mkdirs() }
            val tmp = File(work, "injected.apk").apply { delete() }

            val input = ApkInjectPipeline.Input(
                sourceApk = apk,
                outputApk = tmp,
                targetClass = state.targetClass.takeIf { it.isNotBlank() },
                targetMethod = state.targetMethod.ifBlank { "onCreate" },
                targetSignature = state.targetSignature,
                callsText = state.callsText,
                payloadDex = state.payloadDex,
            )

            val r = pipeline.run(input) { s ->
                state = state.copy(
                    steps = state.steps + s,
                    phase = phaseOf(s.label) ?: state.phase,
                )
            }

            if (r.ok && tmp.isFile) {
                state = state.copy(phase = Phase.SAVING)
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        OutputStore.saveApk(
                            app,
                            tmp,
                            "${apk.nameWithoutExtension}_injected.apk"
                        )
                    }.getOrNull()
                }
                tmp.delete()
                state = state.copy(
                    running = false,
                    phase = if (saved != null) Phase.DONE else Phase.FAILED,
                    result = r,
                    savedOutput = saved,
                    message = saved?.let { "已输出到 ${it.file.absolutePath}" }
                        ?: "产物写入 Download 失败",
                )
            } else {
                state = state.copy(running = false, phase = Phase.FAILED, result = r)
            }
        }
    }

    private fun phaseOf(label: String): Phase? = when {
        label.contains("Manifest") -> Phase.PARSING
        label.contains("dex") && label.contains("定位") -> Phase.LOCATING
        label.contains("注入") -> Phase.INJECTING
        label.contains("重打包") -> Phase.REPACKING
        else -> null
    }

    fun reset() {
        state = state.copy(
            running = false, phase = Phase.IDLE, steps = emptyList(),
            result = null, savedOutput = null,
        )
    }

    fun showMessage(m: String) {
        state = state.copy(message = m)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}

package com.xixin.apkforge.feature.uncheck

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.core.uncheck.PayloadRepo
import com.xixin.apkforge.ui.lifted.components.result.LogState
import com.xixin.apkforge.core.uncheck.UncheckPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 去签名校验的界面状态。
 *
 * 设计原则：**能提前算出来的问题，不要等到点击才报**。
 * 所以 `blockers` 是一个集中式的"为什么现在不能跑"列表，
 * UI 直接照它说事，不要在各处重复判断（那样必然漂移）。
 */
data class UncheckUiState(
    val inputApk: File? = null,
    val inputSize: Long = 0L,
    val outputDir: File? = null,

    /** 载荷就绪信息；null = 还没拿到 */
    val payloadVersion: String? = null,
    val payloadFromCache: Boolean = false,

    /** 运行期进度 */
    val busy: Boolean = false,
    val stage: String = "",
    val stageDetail: String = "",

    /** 最近一次成功 */
    val lastOutput: File? = null,
    val lastSummary: String? = null,

    /** 错误（人话，可直接展示） */
    val error: String? = null,
) {
    val blockers: List<String>
        get() = buildList {
            if (inputApk == null) add("先选一个 APK")
            else if (!inputApk.isFile) add("选中的文件不见了，请重新选")
            if (payloadVersion == null) add("载荷尚未就绪（首次使用需联网下载）")
        }

    val canRun: Boolean get() = blockers.isEmpty() && !busy
}

/**
 * 去签名校验 VM。
 *
 * 职责边界：
 * - 只管"选文件 → 拿载荷 → 跑流水线 → 报结果"
 * - 不碰签名（产物一律未签名，由用户自己签，与仓库既有契约一致）
 * - 不碰 keystore
 */
class UncheckViewModel(app: Application) : AndroidViewModel(app) {

    var ui by mutableStateOf(UncheckUiState(outputDir = defaultOutputDir()))
        private set

    /**
     * 页面日志。与 ConsoleViewModel 的 `steps` 不同，这里用 [LogState]
     * —— 它自带"复制"按钮，用户能把失败详情一键贴出来。
     *
     * 为什么不用 logcat：debug 包能看，但用户手上是 release 包时
     * logcat 拿不到；而且 adb 也不是谁都装。日志必须在 App 内可读可复制。
     */
    val logs = LogState()

    /**
     * 载荷加载。首次需要联网；之后走缓存（按 index.json 的 version 分目录）。
     *
     * 为什么要单独一个入口而不是在 run() 里懒加载：
     * 用户点进来就该知道"载荷能不能用"，而不是选完 APK 跑一半才发现要联网。
     */
    fun loadPayload(forceRefresh: Boolean = false) {
        if (ui.busy) return
        ui = ui.copy(busy = true, stage = "准备载荷", stageDetail = "", error = null)
        logs.addLog("开始获取载荷（forceRefresh=$forceRefresh）")
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                // 先尝试纯缓存（不联网）—— 离线场景下这是唯一路径
                val cached = if (forceRefresh) null else PayloadRepo.cachedPayloadOrNull(ctx)
                val payload = cached ?: withContext(Dispatchers.IO) {
                    PayloadRepo.acquire(ctx, forceRefresh)
                }
                ui = ui.copy(
                    payloadVersion = payload.version,
                    payloadFromCache = cached != null,
                    busy = false,
                    stage = "",
                )
                logs.addLog("载荷就绪 v${payload.version}（${if (cached != null) "本地缓存" else "已下载"}）"
                    + " boot=${payload.bootDex.size}B payload=${payload.payloadDex.size}B")
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "未知错误"
                ui = ui.copy(
                    busy = false,
                    stage = "",
                    error = "载荷获取失败：$msg\n首次使用需要联网下载（约 23 KB）。",
                )
                logs.error("载荷获取失败", "$msg\n\n${e.stackTraceToString()}")
            }
        }
    }

    fun setInput(file: File) {
        ui = ui.copy(
            inputApk = file,
            inputSize = file.length(),
            error = null,
            lastOutput = null,
            lastSummary = null,
        )
    }

    fun setOutputDir(dir: File) {
        ui = ui.copy(outputDir = dir)
    }

    /**
     * 执行。
     *
     * 进度回调直接写 ui —— 流水线在 IO 线程跑，但 Compose 的 state 写入是
     * 主线程安全的（mutableStateOf 内部同步），这里不需要额外切线程。
     */
    fun run() {
        val input = ui.inputApk ?: return
        val outDir = ui.outputDir ?: defaultOutputDir()
        if (ui.busy) return

        ui = ui.copy(
            busy = true, stage = "开始", stageDetail = "",
            error = null, lastOutput = null, lastSummary = null,
        )
        logs.addLog("开始处理 ${input.name}（${humanSize(input.length())}）")
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val payload = PayloadRepo.cachedPayloadOrNull(ctx)
                    ?: withContext(Dispatchers.IO) { PayloadRepo.acquire(ctx) }

                val out = File(outDir, buildOutputName(input))
                val result = withContext(Dispatchers.IO) {
                    UncheckPipeline.run(
                        input = input,
                        output = out,
                        payload = payload,
                        progress = UncheckPipeline.Progress { stage, detail ->
                            ui = ui.copy(stage = stage, stageDetail = detail)
                            logs.addLog(if (detail.isBlank()) stage else "$stage · $detail")
                        },
                    )
                }
                logs.result(result.output.name, result.output.absolutePath)
                ui = ui.copy(
                    busy = false,
                    stage = "",
                    lastOutput = result.output,
                    payloadVersion = result.version,
                    lastSummary = buildString {
                        append("证书指纹 ").append(result.certSha256Short).append("\n")
                        append("新 dex ").append(result.newDex).append("\n")
                        result.originalApp?.let { append("原 Application ").append(it).append("\n") }
                        append("体积 ").append(humanSize(result.output.length()))
                    },
                )
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "未知错误"
                ui = ui.copy(busy = false, stage = "", error = msg)
                logs.error("处理失败", "$msg\n\n${e.stackTraceToString()}")
            }
        }
    }

    fun clearError() {
        ui = ui.copy(error = null)
    }

    // ── 工具 ─────────────────────────────────────────────────

    private fun defaultOutputDir(): File {
        // 与现有产物目录保持一致：Download/ApkForge/
        val dl = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(dl, "ApkForge")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 产物命名：`<原名>_unchecked.apk`
     *
     * 刻意**不带时间戳**：覆盖同名旧文件比堆一堆 `_1 _2 _3` 更好找。
     * （与现有注入产物的命名风格一致，见 OutputStore。）
     */
    private fun buildOutputName(input: File): String {
        val base = input.name.removeSuffix(".apk")
        return "${base}_unchecked.apk"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

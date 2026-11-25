package com.xixin.apkforge.feature.uncheck

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.core.uncheck.UncheckPayload
import com.xixin.apkforge.core.uncheck.UncheckPipeline
import com.xixin.apkforge.data.uncheck.PayloadFetcher
import com.xixin.apkforge.ui.lifted.components.result.LogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 去签名校验的界面状态。
 *
 * 设计原则：**能提前算出来的问题，不要等到点击才报**。
 * `blockers` 是集中式的「为什么现在不能跑」列表，UI 直接照它说事，
 * 不在各处重复判断（两处必然漂移，见 `AGENTS.md` §11.6）。
 */
data class UncheckUiState(
    val inputApk: File? = null,
    val inputSize: Long = 0L,
    val outputDir: File? = null,

    /** 载荷就绪信息；null = 还没拿到 */
    val payloadVersion: String? = null,
    val payloadFromCache: Boolean = false,
    /** 载荷各文件大小，展示给用户确认 native 层也在 */
    val payloadSizes: Map<String, Int> = emptyMap(),

    /** 运行期进度 */
    val busy: Boolean = false,
    val stage: String = "",
    val stageDetail: String = "",

    /** 最近一次成功 */
    val lastResult: UncheckPipeline.Result? = null,
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
 * 职责边界（`AGENTS.md` 分层）：
 * - 只管「选文件 → 拿载荷 → 跑流水线 → 报结果」
 * - 不碰签名：产物一律未签名，由用户自己签（与仓库既有契约一致）
 * - 不碰 keystore
 * - 真正的逻辑全在 `core/uncheck/`（纯 JVM，可单测），这里只做状态管理与调度
 */
class UncheckViewModel(app: Application) : AndroidViewModel(app) {

    private val fetcher = PayloadFetcher(app)

    var ui by mutableStateOf(UncheckUiState(outputDir = defaultOutputDir()))
        private set

    /**
     * 页面日志。[LogState] 自带「复制」按钮，用户能把失败详情一键贴出来。
     *
     * 为什么不用 logcat：debug 包能看，但用户手上是 release 包时拿不到；
     * adb 也不是谁都装。日志必须在 App 内可读可复制（`AGENTS.md` §1.8）。
     */
    val logs = LogState()

    /**
     * 载荷加载。首次需要联网；之后走缓存（按索引 version 分目录）。
     *
     * 为什么单独一个入口而不是在 run() 里懒加载：用户点进来就该知道
     * 「载荷能不能用」，而不是选完 APK 跑一半才发现要联网。
     */
    fun loadPayload(forceRefresh: Boolean = false) {
        if (ui.busy) return
        ui = ui.copy(busy = true, stage = "准备载荷", stageDetail = "", error = null)
        logs.addLog("开始获取载荷（forceRefresh=$forceRefresh）")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 先试纯缓存（不联网）—— 离线场景下这是唯一路径
                    if (!forceRefresh) {
                        fetcher.cachedOrNull()?.let { PayloadFetcher.Fetched(it, true) }
                    } else null
                } ?: withContext(Dispatchers.IO) { fetcher.acquire(forceRefresh) }

                val payload = result.payload
                ui = ui.copy(
                    payloadVersion = payload.version,
                    payloadFromCache = result.fromCache,
                    payloadSizes = payload.sizes(),
                    busy = false,
                    stage = "",
                )
                logs.addLog(
                    "载荷就绪 v${payload.version}（${if (result.fromCache) "本地缓存" else "已下载"}）"
                )
                // 四个文件逐个报出来：少任何一个都是「去校验只能拦住一半」
                for ((name, size) in payload.sizes()) {
                    logs.addLog("  · $name  $size B")
                }
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "未知错误"
                ui = ui.copy(
                    busy = false,
                    stage = "",
                    error = "载荷获取失败：$msg",
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
            lastResult = null,
            lastSummary = null,
        )
    }

    fun setOutputDir(dir: File) {
        ui = ui.copy(outputDir = dir)
    }

    /**
     * 执行。
     *
     * 进度回调直接写 ui —— 流水线在 IO 线程跑，但 Compose 的 state 写入
     * 是同步安全的，不需要额外切线程。
     */
    fun run() {
        val input = ui.inputApk ?: return
        val outDir = ui.outputDir ?: defaultOutputDir()
        if (ui.busy) return

        ui = ui.copy(
            busy = true, stage = "开始", stageDetail = "",
            error = null, lastResult = null, lastSummary = null,
        )
        logs.addLog("开始处理 ${input.name}（${humanSize(input.length())}）")

        viewModelScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    fetcher.cachedOrNull() ?: fetcher.acquire().payload
                }

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
                    lastResult = result,
                    payloadVersion = result.version,
                    lastSummary = summaryOf(result),
                )

                // 原生层能力不完整时必须明说，不能让人以为去校验「全都能过」
                if (!result.nativeCapable) {
                    logs.addLog(
                        "⚠️ 原包没有 v2/v3 签名块，origin.apk 里读不到证书。" +
                            "App 自己读 APK 文件的那类校验不会被绕过。"
                    )
                }
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

    private fun summaryOf(r: UncheckPipeline.Result): String = buildString {
        append("证书指纹 ").append(r.certSha256Short).append('\n')
        append("新 dex ").append(r.newDex).append('\n')
        r.originalApp?.let { append("原 Application ").append(it).append('\n') }
        append("extractNativeLibs ").append(
            when (r.extractNativeLibs) {
                com.xixin.apkforge.core.axml.AxmlBoolean.Outcome.ALREADY_ON -> "原为 true"
                com.xixin.apkforge.core.axml.AxmlBoolean.Outcome.FLIPPED -> "已改为 true"
                com.xixin.apkforge.core.axml.AxmlBoolean.Outcome.ADDED -> "已新增为 true"
            }
        ).append('\n')
        append("体积 ").append(humanSize(r.sourceSize))
            .append(" → ").append(humanSize(r.outputSize))
        if (r.savedByReuse > 0) {
            append("（数据复用省了 ").append(humanSize(r.savedByReuse)).append("）")
        }
    }

    private fun defaultOutputDir(): File {
        // 与注入产物保持一致：Download/ApkForge/
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
     * 刻意**不带时间戳**：覆盖同名旧文件比堆一堆 `_1 _2 _3` 更好找
     * （与注入产物的命名风格一致，见 OutputStore）。
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

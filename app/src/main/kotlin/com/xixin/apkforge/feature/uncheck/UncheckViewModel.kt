package com.xixin.apkforge.feature.uncheck

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.core.uncheck.UncheckPayload
import com.xixin.apkforge.core.uncheck.UncheckPipeline
import com.xixin.apkforge.data.ApkMeta
import com.xixin.apkforge.data.uncheck.PayloadFetcher
import com.xixin.apkforge.ui.lifted.components.result.LogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 去签名校验的界面状态。
 *
 * ## 只有一个"当前任务"，没有载荷状态卡
 *
 * 上一版把「载荷 v1.0.0 · 本地缓存」做成首屏第一张卡。那是实现了错误的优先级：
 * 载荷是**内部依赖**，用户既不能选择它、也不需要知道它的版本。它唯一该暴露的
 * 时刻是「拿不到」——那时才用一行错误说明说话。
 *
 * 所以状态收敛成：输入包 + 运行状态 + 结果。载荷是否就绪由 [canRun] 隐式表达，
 * 不给它单独的展示位。
 */
data class UncheckUiState(
    val inputApk: File? = null,
    val inputSize: Long = 0L,

    /** 包身份：应用名 / 包名 / 版本 / 图标。读不到时为 null（加固包常见） */
    val meta: ApkMeta.Info? = null,
    /** 正在后台读包身份 */
    val readingMeta: Boolean = false,

    /** 运行期进度 */
    val running: Boolean = false,
    val stage: String = "",
    val stageDetail: String = "",
    /** 0..1，驱动进度条。载荷下载占前段，装配占后段 */
    val progress: Float = 0f,

    /** 最近一次结果 */
    val result: UncheckPipeline.Result? = null,

    /** 错误（人话，可直接展示） */
    val error: String? = null,
) {
    /** 卡片上显示的标题：应用名优先，退回文件名 */
    val displayName: String
        get() = meta?.displayName(inputApk?.name ?: "") ?: inputApk?.name.orEmpty()

    /**
     * 主按钮此刻该做什么。
     *
     * 单一入口的语义：没选包就是选包，选了包就是处理，跑完了再点就是重跑。
     * 用户永远不用在几个入口之间挑（`AGENTS.md` §1.10）。
     */
    val primary: Primary
        get() = when {
            inputApk == null -> Primary.PICK
            running -> Primary.RUNNING
            else -> Primary.RUN
        }

    enum class Primary { PICK, RUN, RUNNING }
}

/**
 * 去签名校验 VM。
 *
 * 职责边界（`AGENTS.md` 分层）：
 * - 只管「选文件 → 跑流水线 → 报结果」
 * - 不碰签名：产物一律未签名，由用户自己签（与仓库既有契约一致）
 * - 真正的逻辑全在 `core/uncheck/`（纯 JVM，可单测）
 */
class UncheckViewModel(app: Application) : AndroidViewModel(app) {

    private val fetcher = PayloadFetcher(app)

    var ui by mutableStateOf(UncheckUiState())
        private set

    /**
     * 页面日志。[LogState] 自带复制按钮，失败时一键把堆栈贴出来。
     *
     * 不靠 logcat：debug 包能看，但用户手上是 release 包时拿不到；adb 也不是谁都装。
     */
    val logs = LogState()

    /**
     * 已缓存的载荷（只读，不联网）。
     *
     * 页面不再有「刷新载荷」按钮 —— 载荷在**开始处理时**自动获取，
     * 缓存命中就跳过网络，没命中才下载。用户点「处理」时不会因为
     * 「忘了先点刷新」而失败。
     */
    private fun cachedPayloadOrNull(): UncheckPayload.Payload? = fetcher.cachedOrNull()

    fun setInput(file: File) {
        ui = ui.copy(
            inputApk = file,
            inputSize = file.length(),
            meta = null,
            readingMeta = true,
            error = null,
            result = null,
            progress = 0f,
            stage = "",
            stageDetail = "",
        )
        // 读包身份要解 arsc / 加载图标，不能在组合期做
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { ApkMeta.read(getApplication(), file) }.getOrNull()
            }
            // 用户可能在此期间换了包，只接受仍然匹配的结果
            if (ui.inputApk == file) {
                ui = ui.copy(meta = info, readingMeta = false)
            }
        }
    }

    fun clearInput() {
        if (ui.running) return
        ui = UncheckUiState()
        logs.clearLogs()
    }

    /**
     * 执行。
     *
     * 载荷在这里**自动获取**：先试缓存（离线可用），没有才联网下载。
     * 不给用户「刷新载荷」这种按钮 —— 那是把内部依赖的管理责任推给了用户。
     */
    fun run() {
        val input = ui.inputApk ?: return
        if (ui.running) return

        ui = ui.copy(
            running = true, stage = "准备", stageDetail = "",
            progress = 0f, error = null, result = null,
        )
        logs.addLog("开始处理 ${input.name}")

        viewModelScope.launch {
            try {
                // ① 载荷：缓存优先
                val payload = cachedPayloadOrNull() ?: run {
                    ui = ui.copy(stage = "获取载荷", stageDetail = "首次使用需联网", progress = 0.02f)
                    logs.addLog("本地无载荷缓存，开始下载")
                    withContext(Dispatchers.IO) { fetcher.acquire().payload }.also {
                        logs.addLog("载荷就绪 v${it.version}")
                    }
                }

                // ② 跑流水线
                val out = File(outputDir(), buildOutputName(input))

                /**
                 * 阶段进度。
                 *
                 * ⚠️ 不能用 `ui.progress + 0.12f` 累加。两个原因：
                 *
                 * ① **并发读改写**：本回调在 IO 线程，而 `ui` 是 Compose 状态。
                 *    `ui.copy(progress = ui.progress + x)` 里的 `ui.progress` 可能在
                 *    读与写之间被主线程的另一次 copy 覆盖，累加会丢步甚至回退。
                 * ② **靠次数推前进量不稳定**：某个阶段重复上报时进度会跳。
                 *
                 * 改成「本次已上报过的不同阶段数 / 预估总阶段数」：
                 * 单调、与回调频次无关、也不需要在 VM 里硬编码一份阶段名清单
                 * （那种清单必然与 `UncheckPipeline` 漂移）。
                 */
                val seenStages = LinkedHashSet<String>()
                // 装配阶段的粗估总数。只影响进度条的"看起来走了多少"，
                // 不参与任何判定；估少了只是提前到 96%，估多了停得早一点。
                val expectedStages = 16
                val result = withContext(Dispatchers.IO) {
                    UncheckPipeline.run(
                        input = input,
                        output = out,
                        payload = payload,
                        progress = UncheckPipeline.Progress { stage, detail ->
                            val isNew = seenStages.add(stage)
                            val frac = (seenStages.size.toFloat() / expectedStages)
                                .coerceIn(0.02f, 0.96f)
                            if (isNew) {
                                logs.addLog(if (detail.isBlank()) stage else "$stage · $detail")
                                ui = ui.copy(stage = stage, stageDetail = detail, progress = frac)
                            } else {
                                // 同一阶段内的细节更新（如"写入 x/y 条目"），
                                // 不重复记日志、也不推进度
                                ui = ui.copy(stageDetail = detail)
                            }
                        },
                    )
                }

                ui = ui.copy(
                    running = false,
                    stage = "",
                    stageDetail = "",
                    progress = 1f,
                    result = result,
                )
                logs.result(result.output.name, logSummary(result))
                logs.addLog("产物：${result.output.absolutePath}")
                logs.addLog("未签名 —— 用你自己的 keystore 签，且必须用 MT 管理器这类手写 ZIP 的工具")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ⚠️ 必须单独接住。CancellationException 是 CoroutineScope 的
                // **正常取消信号**（用户退出页面 / 切换模块都会触发），不是错误。
                // 若被下面的 `catch (Exception)` 吞掉并当成失败上报：
                //   · 用户会看到一条假的「处理失败」
                //   · cancel 被吃掉后无法正确向上传播，协程终止语义被破坏
                //
                // 但**状态必须复位**：否则用户回到页面会看到 running 永远 true、
                // 按钮永久禁用、进度条卡在中间。
                ui = ui.copy(running = false, stage = "", stageDetail = "", progress = 0f)
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "未知错误"
                ui = ui.copy(running = false, stage = "", progress = 0f, error = msg)
                logs.error("处理失败", "$msg\n\n${e.stackTraceToString()}")
            }
        }
    }

    fun clearError() {
        ui = ui.copy(error = null)
    }

    // ── 工具 ─────────────────────────────────────────────────

    private fun logSummary(r: UncheckPipeline.Result): String = buildString {
        append("证书指纹 ").append(r.certSha256Short).append('\n')
        append("新 dex ").append(r.newDex).append('\n')
        r.originalApp?.let { append("原 Application ").append(it).append('\n') }
        append("体积 ").append(humanSize(r.sourceSize))
            .append(" → ").append(humanSize(r.outputSize))
        if (r.savedByReuse > 0) {
            append("（复用省 ").append(humanSize(r.savedByReuse)).append("）")
        }
        if (!r.nativeCapable) {
            append("\n⚠️ 原包无 v2/v3 签名块：只覆盖 PackageManager 类校验")
        }
    }

    /**
     * 产物目录：`Download/ApkForge/`
     *
     * 与注入产物同一个地方 —— 用户不该为了找文件去记两个路径
     * （`AGENTS.md` §1.9：产物路径必须用户可见）。
     */
    private fun outputDir(): File {
        val dl = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        return File(dl, "ApkForge").apply { mkdirs() }
    }

    /** `<原名>_unchecked.apk`，覆盖同名旧文件而不是堆 `_1 _2`。 */
    private fun buildOutputName(input: File): String =
        "${input.name.removeSuffix(".apk")}_unchecked.apk"

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

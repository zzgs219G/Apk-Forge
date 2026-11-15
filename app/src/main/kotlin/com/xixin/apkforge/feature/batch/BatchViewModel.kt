package com.xixin.apkforge.feature.batch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.data.ApkFolderScanner
import com.xixin.apkforge.data.ApkMeta
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.data.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 队列里的一项。 */
data class BatchItem(
    val id: String,
    val name: String,
    val label: String,
    val packageName: String?,
    val size: Long,
    val uri: Uri?,
    val local: File? = null,
    val state: State = State.QUEUED,
    val message: String? = null,
    val outputPath: String? = null,
    val atMs: Long = 0L,
) {
    enum class State { QUEUED, RUNNING, OK, FAILED }

    val title: String get() = label.ifBlank { name }
}

data class BatchUiState(
    val items: List<BatchItem> = emptyList(),
    val scanning: Boolean = false,
    val running: Boolean = false,
    val configId: String? = null,
    val configs: List<ConfigStore.Config> = emptyList(),
    /** 当前正在处理的下标，-1 表示没在跑 */
    val current: Int = -1,
    val message: String? = null,
) {
    val done: Int get() = items.count { it.state == BatchItem.State.OK || it.state == BatchItem.State.FAILED }
    val ok: Int get() = items.count { it.state == BatchItem.State.OK }
    val failed: Int get() = items.count { it.state == BatchItem.State.FAILED }
    val progress: Float get() = if (items.isEmpty()) 0f else done.toFloat() / items.size
    val activeConfig: ConfigStore.Config? get() = configId?.let { id -> configs.firstOrNull { it.id == id } }
}

/**
 * 批量注入。
 *
 * 【为什么是一个独立 VM，而不是往 ConsoleViewModel 里加个列表】
 * 两件事的生命周期完全不同：控制台是「一个包，反复调参再跑」，批量是
 * 「一次挑一批，跑完就走」。塞进同一个 VM 会让「当前任务」这个概念变得含混——
 * 控制台卡片显示哪个包？日志是谁的？拆开之后，控制台仍然是单任务的权威，
 * 批量自己管自己的队列。
 *
 * 【为什么严格串行】注入是 IO + 大内存（几十 MB dex 反复重写）的活。
 * 并行只会更快地把内存打满然后 OOM，实际速度并不会提升。串行还有一个好处：
 * 日志的因果顺序永远清楚，出错时能指着某一项说「它挂了」。
 */
class BatchViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(BatchUiState())
        private set

    private val pipeline = ApkInjectPipeline()
    private val configs = ConfigStore(app.applicationContext)
    private val tasks = TaskStore(app.applicationContext)

    private var job: Job? = null

    init {
        refreshConfigs()
    }

    fun refreshConfigs() {
        val list = configs.list()
        state = state.copy(
            configs = list,
            configId = state.configId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id,
        )
    }

    fun setConfig(id: String?) {
        state = state.copy(configId = id)
    }

    /** 用户选了一个目录：扫出所有 apk 排进队列。 */
    fun pickFolder(treeUri: Uri) {
        state = state.copy(scanning = true, message = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val found = withContext(Dispatchers.IO) {
                runCatching { ApkFolderScanner.listApks(app, treeUri) }.getOrDefault(emptyList())
            }
            // 读应用名与包名：队列里认包靠它们，文件名毫无区分度。
            // 顺手把内容复制进缓存——后面逐项注入时不必再碰 SAF（权限可能已失效）。
            val items = withContext(Dispatchers.IO) {
                found.map { f ->
                    val local = runCatching { FilePicker.copyToCache(app, f.uri, "batch") }.getOrNull()
                    val meta = local?.let { ApkMeta.read(app, it) }
                    BatchItem(
                        id = "b_${f.uri.hashCode()}_${f.name}",
                        name = f.name,
                        label = meta?.displayName(f.name) ?: f.name,
                        packageName = meta?.packageName,
                        size = f.size.takeIf { it > 0 } ?: (local?.length() ?: 0L),
                        uri = f.uri,
                        local = local,
                    )
                }
            }
            state = state.copy(
                scanning = false,
                items = items,
                message = when {
                    items.isEmpty() -> "这个目录里没有 .apk（含子目录，最多 ${ApkFolderScanner.MAX_FILES} 个）"
                    items.size >= ApkFolderScanner.MAX_FILES ->
                        "已收录 ${items.size} 个（上限 ${ApkFolderScanner.MAX_FILES}），其余未加入"
                    else -> "已加入 ${items.size} 个 APK"
                },
            )
        }
    }

    fun clearQueue() {
        if (state.running) return
        state = state.copy(items = emptyList(), current = -1)
    }

    fun remove(id: String) {
        if (state.running) return
        state = state.copy(items = state.items.filterNot { it.id == id })
    }

    fun start() {
        if (state.running) return
        val cfg = state.activeConfig ?: run {
            state = state.copy(message = "先选一套配置：批量注入要有一份「注入什么」")
            return
        }
        if (state.items.none { it.state == BatchItem.State.QUEUED }) {
            state = state.copy(message = "队列里没有待注入的包")
            return
        }
        val (_, errors) = SmaliCallParser.parseAll(cfg.calls)
        if (!errors.isEmpty) {
            state = state.copy(message = "配置「${cfg.name}」的调用行有语法错误，先去配置页修掉")
            return
        }

        state = state.copy(running = true, message = null)
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            val payload = cfg.dexFile?.takeIf { it.isFile }

            var index = 0
            while (index < state.items.size) {
                val item = state.items[index]
                if (item.state != BatchItem.State.QUEUED) {
                    index++
                    continue
                }
                state = state.copy(
                    current = index,
                    items = state.items.mapIndexed { i, it ->
                        if (i == index) it.copy(state = BatchItem.State.RUNNING) else it
                    },
                )

                val local = item.local?.takeIf { it.isFile } ?: withContext(Dispatchers.IO) {
                    item.uri?.let { runCatching { FilePicker.copyToCache(app, it, "batch") }.getOrNull() }
                }
                if (local == null) {
                    update(index) { it.copy(state = BatchItem.State.FAILED, message = "无法读取文件") }
                    index++
                    continue
                }

                val work = File(app.cacheDir, "batch").apply { mkdirs() }
                val tmp = File(work, "b_$index.apk").apply { delete() }
                val result = pipeline.run(
                    ApkInjectPipeline.Input(
                        sourceApk = local,
                        outputApk = tmp,
                        // 类名留空 → 取 Manifest 的 LAUNCHER；方法留空 → 自动挑注入点
                        targetClass = null,
                        targetMethod = "",
                        mode = cfg.mode,
                        callsText = cfg.calls,
                        payloadDex = payload,
                    )
                )

                var saved: String? = null
                if (result.ok && tmp.isFile) {
                    saved = withContext(Dispatchers.IO) {
                        runCatching {
                            OutputStore.saveApk(app, tmp, "${local.nameWithoutExtension}_injected.apk").file.absolutePath
                        }.getOrNull()
                    }
                    tmp.delete()
                }

                val ok = result.ok && saved != null
                update(index) {
                    it.copy(
                        state = if (ok) BatchItem.State.OK else BatchItem.State.FAILED,
                        message = if (ok) null else (result.error ?: "产物写入 Download 失败"),
                        outputPath = saved,
                        atMs = result.durationMs,
                    )
                }
                withContext(Dispatchers.IO) {
                    tasks.add(
                        TaskStore.Record(
                            id = "task_${System.currentTimeMillis()}_$index",
                            apkName = local.name,
                            apkLabel = item.title,
                            apkPath = local.absolutePath,
                            apkSize = local.length(),
                            packageName = result.packageName ?: item.packageName,
                            targetClass = result.resolvedClass ?: "",
                            configId = cfg.id,
                            configName = cfg.name,
                            mode = cfg.mode.name,
                            targetNames = result.targets.map { t -> t.methodName },
                            targetMethod = result.resolvedMethod ?: "",
                            targetSignature = result.resolvedSignature ?: "",
                            outputPath = saved,
                            ok = ok,
                            injectedCount = result.injectedCount,
                            durationMs = result.durationMs,
                            at = System.currentTimeMillis(),
                        )
                    )
                }
                index++
            }
            state = state.copy(running = false, current = -1)
        }
    }

    private fun update(index: Int, f: (BatchItem) -> BatchItem) {
        state = state.copy(
            items = state.items.mapIndexed { i, it -> if (i == index) f(it) else it }
        )
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = state.copy(
            running = false,
            current = -1,
            items = state.items.map {
                if (it.state == BatchItem.State.RUNNING) it.copy(state = BatchItem.State.QUEUED) else it
            },
        )
    }

    fun showMessage(m: String) {
        state = state.copy(message = m)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}

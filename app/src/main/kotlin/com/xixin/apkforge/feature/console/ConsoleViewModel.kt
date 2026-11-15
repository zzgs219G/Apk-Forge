package com.xixin.apkforge.feature.console

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.core.dex.ClassInspector
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.ExistingMethod
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.core.dex.SamplePayload
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.data.ApkMeta
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.data.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 控制台的一句话状态，只用于卡片/顶栏的小徽标。 */
enum class ConsoleStatus(val label: String) {
    EMPTY("待选包"),
    READY("就绪"),
    RUNNING("运行中"),
    DONE("已完成"),
    FAILED("失败"),
}

data class ConsoleUiState(
    /** 当前任务的 APK（复制进私有目录后的稳定副本，历史记录也指向它） */
    val apkFile: File? = null,
    /** 文件名（`app-debug.apk`），只用于说明「物理上动的是哪个文件」 */
    val apkName: String = "",
    /** APK 里声明的应用名（`ApkForge`），列表里认包靠它 */
    val apkLabel: String = "",
    val apkIcon: ImageBitmap? = null,
    val apkVersion: String? = null,
    val apkSize: Long = 0L,
    val packageName: String? = null,

    val targetClass: String = "",
    val launcherCandidates: List<String> = emptyList(),
    /**
     * 真正落笔的方法名。[autoTarget] 为 true 时，这是系统**替你挑的**结果，
     * 不是用户的选择——UI 必须显示成「自动（onCreate）」而不是「onCreate」。
     */
    val targetMethod: String = "",
    val targetSignature: String = "(Landroid/os/Bundle;)V",
    /** 注入点是否交给系统自动决定（默认 true，不再要求用户手选生命周期方法） */
    val autoTarget: Boolean = true,
    /** 注入模式：单次 / 每次进入 / 多点 / 替换方法体 */
    val mode: InjectionMode = InjectionMode.ONCE,

    val classFound: Boolean = false,
    val classSuperType: String? = null,
    val classMethods: List<ExistingMethod> = emptyList(),
    val points: List<LifecyclePoints.Point> = LifecyclePoints.ACTIVITY,
    /** 注入点判定结果（含 native 降级原因与推荐标记） */
    val resolution: LifecyclePoints.Resolution? = null,

    val configs: List<ConfigStore.Config> = emptyList(),
    val activeConfigId: String? = null,
    val callsText: String = "",
    val callCount: Int = 0,
    val callErrors: List<String> = emptyList(),
    val payloadDex: File? = null,

    val parsing: Boolean = false,
    val running: Boolean = false,
    val steps: List<ApkInjectPipeline.Step> = emptyList(),
    val progress: Float = 0f,
    val stage: ApkInjectPipeline.Stage? = null,
    val result: ApkInjectPipeline.Result? = null,
    /** 落盘后的产物路径（Download/ApkForge/xxx.apk） */
    val savedPath: String? = null,
    val elapsedMs: Long = 0L,

    val records: List<TaskStore.Record> = emptyList(),
    val message: String? = null,
) {
    val status: ConsoleStatus
        get() = when {
            running -> ConsoleStatus.RUNNING
            result != null && result.ok -> ConsoleStatus.DONE
            result != null -> ConsoleStatus.FAILED
            apkFile != null -> ConsoleStatus.READY
            else -> ConsoleStatus.EMPTY
        }

    val activeConfig: ConfigStore.Config?
        get() = activeConfigId?.let { id -> configs.firstOrNull { it.id == id } }

    val targetPoint: LifecyclePoints.Point?
        get() = points.firstOrNull { it.name == targetMethod }

    /** 「每次进入」这类模式会铺多个点，这里给出预计落点数供 UI 提前说明。 */
    val plannedTargets: Int
        get() = resolution?.let { r ->
            com.xixin.apkforge.core.dex.InjectionPlanner.plan(
                mode = mode, points = points, methods = classMethods, wanted = r.target,
            ).targets.size
        } ?: 1

    val targetStatus: LifecyclePoints.Status?
        get() = targetPoint?.let { LifecyclePoints.statusOf(it, classMethods) }

    /** 阻断执行的原因；空表示可以开跑。 */
    val blockers: List<String>
        get() = buildList {
            if (apkFile == null) add("选一个 APK")
            if (targetClass.isBlank()) add("填注入点类名")
            if (callsText.isBlank()) add("套一套配置（或粘调用行）")
            if (callErrors.isNotEmpty()) add("先修掉调用行语法错误")
        }

    val canRun: Boolean get() = blockers.isEmpty() && !running && !parsing

    val savedFile: File? get() = savedPath?.let { File(it) }
}

/**
 * 控制台唯一的 ViewModel。
 *
 * 【为什么把「选包 → 配一次 → 跑 → 留记录」全放一处】这条链路的每一步都依赖上一步的结果：
 * 选包才知道 LAUNCHER 类名与类里有哪些方法，类里有什么方法才知道哪些注入点可插
 * （native 的 `onCreate` 就得让位给 `onPostCreate`），跑完才知道产物路径。
 * 拆成多个 ViewModel 只会在它们之间来回倒状态。
 */
class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(ConsoleUiState())
        private set

    private val pipeline = ApkInjectPipeline()
    private val configs = ConfigStore(app.applicationContext)
    private val tasks = TaskStore(app.applicationContext)

    /** 类名输入的探测任务。每敲一个字都开 zip 会卡顿，必须能取消上一次。 */
    private var classProbe: Job? = null

    private companion object {
        /** 标准生命周期方法名，用来区分「标准点」与「用户自定方法」。 */
        val ALL_STANDARD_NAMES: Set<String> = LifecyclePoints.ALL_STANDARD.map { it.name }.toSet()
    }

    init {
        state = state.copy(configs = configs.list(), records = tasks.list())
    }

    // ────────────────────── 选包 ──────────────────────

    /**
     * 用户从 MT / 系统选择器选完 APK。
     *
     * 三件事按顺序做：收进私有目录（历史记录要长期可用）→ 读 Manifest 拿 LAUNCHER →
     * 探测目标类方法（决定哪些注入点可插）。全部完成后自动弹「配置」面板。
     */
    fun selectApk(picked: File) {
        state = state.copy(
            parsing = true, result = null, savedPath = null,
            steps = emptyList(), progress = 0f, stage = null,
            // 先把临时副本立起来：配置页立刻显示「正在读包」，
            // 而不是一边转圈一边说「还差：选一个 APK」。
            apkFile = picked,
            apkName = picked.name,
            apkLabel = picked.nameWithoutExtension,
            apkIcon = null,
            apkVersion = null,
            apkSize = picked.length(),
            // 换包 = 换类 = 之前的注入点结论全部作废，回到自动
            autoTarget = true,
            targetMethod = "",
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            val stable = withContext(Dispatchers.IO) { runCatching { tasks.importApk(picked) } }
                .getOrDefault(picked)
            // 图标/应用名走 PackageManager，与 AXML 解析并行不了（都在 IO 线程串行更快：
            // 两者都要读同一个几十 MB 的 zip，并行反而争 IO）。
            val meta = withContext(Dispatchers.IO) { ApkMeta.read(app, stable) }
            val manifest = withContext(Dispatchers.IO) { pipeline.peekManifest(stable) }
            val launchers = manifest?.launcherActivities ?: emptyList()
            val cls = launchers.firstOrNull() ?: state.targetClass
            val inspect = withContext(Dispatchers.IO) {
                cls.takeIf { it.isNotBlank() }?.let {
                    ClassInspector.inspect(stable, "L${it.replace('.', '/')};")
                }
            }
            state = state.copy(
                parsing = false,
                apkFile = stable,
                apkName = stable.name,
                apkLabel = meta?.displayName(stable.nameWithoutExtension) ?: stable.nameWithoutExtension,
                apkIcon = meta?.icon,
                apkVersion = meta?.versionName,
                apkSize = stable.length(),
                packageName = manifest?.packageName,
                launcherCandidates = launchers,
                targetClass = cls,
                classFound = inspect?.found == true,
                classSuperType = inspect?.superType,
                classMethods = inspect?.methods ?: emptyList(),
                points = LifecyclePoints.forSuperClass(inspect?.superType),
                message = when {
                    manifest == null -> "读不出 AndroidManifest，这个包可能不是标准 APK"
                    launchers.isEmpty() -> "清单里没有 LAUNCHER，请手动填写注入点类名"
                    inspect?.found != true -> "已定位到 ${launchers.first()}，但包里找不到这个类"
                    else -> null
                },
            )
            recomputeResolution()
        }
    }

    /**
     * 换一个注入点类（常用来从 LAUNCHER 切到别的 Activity）。
     *
     * 输入是逐字触发的，而探测要打开整个 APK 扫 dex。所以：先取消上一次探测，
     * 静默 320ms 再干——否则快速输入会排一串 IO 任务，输入框自己的光标都在跳。
     */
    fun setTargetClass(v: String) {
        classProbe?.cancel()
        state = state.copy(targetClass = v, classFound = false)
        val apk = state.apkFile
        if (v.isBlank() || apk == null) return
        classProbe = viewModelScope.launch {
            delay(320)
            val inspect = withContext(Dispatchers.IO) {
                ClassInspector.inspect(apk, "L${v.trim().replace('.', '/')};")
            }
            if (state.targetClass.trim() == v.trim()) {
                state = state.copy(
                    classFound = inspect.found,
                    classSuperType = inspect.superType,
                    classMethods = inspect.methods,
                    points = LifecyclePoints.forSuperClass(inspect.superType),
                )
                normalizeTargetAfterClassChange()
                recomputeResolution()
            }
        }
    }

    fun setTargetMethod(name: String, signature: String) {
        state = state.copy(targetMethod = name, targetSignature = signature, autoTarget = false)
        recomputeResolution()
    }

    /** 选一个注入点：同时带出签名，避免用户看到裸方法名。 */
    fun selectPoint(p: LifecyclePoints.Point) = setTargetMethod(p.name, p.signature)

    /**
     * 把注入点交回系统自动决定。
     *
     * 「自动」不是一个 UI 上的摆设：它决定 [recomputeResolution] 是**自己挑**一个点，
     * 还是**尊重**用户选的点。混在一起的话，用户选了 onResume 之后只要换个类名，
     * 系统就会把他选的悄悄改掉。
     */
    fun useAutoTarget() {
        state = state.copy(autoTarget = true)
        recomputeResolution()
    }

    fun setMode(mode: InjectionMode) {
        state = state.copy(mode = mode)
    }

    /**
     * 换类之后，把「上一套清单里的标准注入点」纠正回来。
     *
     * 踩的坑：在 Activity 上选了 `onPostCreate`，然后把类名改成
     * 一个 `Application` 子类——`Application` 根本没有 `onPostCreate`，
     * 保留它就会新建出一个**永远不会被框架调用**的方法，注入「成功」但毫无效果。
     *
     * 判定范围刻意收窄到「标准生命周期方法名」：
     *  · 是标准名但当前类不支持 → 重置成 `onCreate`（存在就它，否则清单第一个）；
     *  · 是用户自定的名字 → 一律保留，他明确知道自己在干什么。
     */
    private fun normalizeTargetAfterClassChange() {
        // 自动模式下没什么可纠正的：下一次 recomputeResolution 会重新挑。
        if (state.autoTarget) return
        val isStandard = ALL_STANDARD_NAMES.contains(state.targetMethod)
        if (!isStandard || state.points.any { it.name == state.targetMethod }) return
        val preferred = state.points.firstOrNull { it.name == "onCreate" }
            ?: state.points.firstOrNull()
            ?: return
        val old = state.targetMethod
        state = state.copy(
            targetMethod = preferred.name,
            targetSignature = preferred.signature,
            message = "这个类没有 $old，注入点已改为 ${preferred.name}",
        )
    }

    /**
     * 重新算「这个注入点到底能不能插」。
     *
     * 判定权在 `LifecyclePoints.resolve`（纯 JVM，可被单测钉住），这里只落地结果。
     * native 的 onCreate 会自动让位给 onPostCreate，并把原因放进 [ConsoleUiState.resolution]。
     */
    private fun recomputeResolution() {
        // 自动模式：把「想要的点」定成 onCreate，让 resolve 去处理 native 降级；
        // 类没有 onCreate 时取清单第一个，保证永远有一个合法起点。
        val wanted = if (state.autoTarget) {
            state.points.firstOrNull { it.name == "onCreate" } ?: state.points.first()
        } else {
            state.targetPoint ?: LifecyclePoints.Point(
                state.targetMethod, state.targetSignature, "自定义方法"
            )
        }
        val r = LifecyclePoints.resolve(state.points, state.classMethods, wanted)
        state = state.copy(
            resolution = r,
            targetMethod = r.target.name,
            targetSignature = r.target.signature,
        )
    }

    // ────────────────────── 配置 ──────────────────────

    fun refreshConfigs() {
        state = state.copy(configs = configs.list())
    }

    fun applyConfig(cfg: ConfigStore.Config) {
        val dex = cfg.dexFile?.takeIf { it.isFile }
        val (calls, errors) = SmaliCallParser.parseAll(cfg.calls)
        state = state.copy(
            activeConfigId = cfg.id,
            callsText = cfg.calls,
            callCount = calls.size,
            callErrors = errors.messages,
            payloadDex = dex,
            // 配置只决定「注入什么」（调用行 + 载荷），不决定「注到哪」——
            // 注入点是类自己的属性，跟着包走，不该被一套配置带着跨包漂移。
            // 上一版把 config 的 targetMethod 套进当前任务，换到别的包时会戳到
            // 那个包里根本不存在的方法，表现为「注入成功、效果为零」。
            mode = cfg.mode,
            autoTarget = true,
            message = if (dex == null && cfg.dexPath != null) {
                "配置「${cfg.name}」的 dex 文件已丢失，请到配置页重新选择"
            } else {
                null
            },
        )
        recomputeResolution()
    }

    fun clearConfig() {
        state = state.copy(
            activeConfigId = null, callsText = "", payloadDex = null,
            callCount = 0, callErrors = emptyList(),
        )
    }

    fun setPayloadDex(f: File?) {
        state = state.copy(payloadDex = f)
    }

    /** 没有配置、也不想建配置时的一键兜底：示例弹窗 + 一行调用。 */
    fun loadSample() {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "sample").apply { mkdirs() }
                val out = File(dir, SamplePayload.FILE_NAME)
                getApplication<Application>().assets.open(SamplePayload.ASSET_PATH).use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                out
            }
            val (calls, errors) = SmaliCallParser.parseAll(SamplePayload.CALL)
            state = state.copy(
                callsText = SamplePayload.CALL,
                callCount = calls.size,
                callErrors = errors.messages,
                payloadDex = f,
                activeConfigId = null,
                message = "已填入示例弹窗（未套配置）",
            )
        }
    }

    // ────────────────────── 面板开关 ──────────────────────

    /** 进配置页前刷新下拉内容（可能在别处新建/改名/删除了配置）。 */
    fun openSetup() = refreshConfigs()

    // ────────────────────── 执行 ──────────────────────

    fun run() {
        val apk = state.apkFile ?: return
        if (!state.canRun) return
        val app = getApplication<Application>()
        val startedAt = System.currentTimeMillis()

        // 快照：跑起来之后用户可能继续改面板，不能让它影响进行中的任务
        val snapshot = state
        state = state.copy(
            running = true,
            steps = emptyList(), progress = 0f, stage = null,
            result = null, savedPath = null, elapsedMs = 0L, message = null,
        )

        viewModelScope.launch {
            val work = File(app.cacheDir, "work").apply { mkdirs() }
            val tmp = File(work, "injected.apk").apply { delete() }

            val input = ApkInjectPipeline.Input(
                sourceApk = apk,
                outputApk = tmp,
                targetClass = snapshot.targetClass.takeIf { it.isNotBlank() },
                // 自动模式下传空串，交给流水线按类里的真实方法 + native 规则挑
                targetMethod = if (snapshot.autoTarget) "" else snapshot.targetMethod,
                targetSignature = snapshot.targetSignature,
                mode = snapshot.mode,
                callsText = snapshot.callsText,
                payloadDex = snapshot.payloadDex,
            )

            val r = pipeline.run(input) { s ->
                state = state.copy(
                    steps = state.steps + s,
                    stage = s.stage,
                    progress = maxOf(state.progress, s.progress),
                )
            }

            // 落盘：正式产物进 Download/ApkForge/，临时文件立刻清掉
            var savedPath: String? = null
            if (r.ok && tmp.isFile) {
                state = state.copy(
                    stage = ApkInjectPipeline.Stage.SAVE,
                    progress = ApkInjectPipeline.progressOf(ApkInjectPipeline.Stage.SAVE, 0.2f),
                )
                state = state.copy(steps = state.steps + saveStartStep(app, tmp))
                savedPath = withContext(Dispatchers.IO) {
                    runCatching {
                        OutputStore.saveApk(
                            app, tmp, "${apk.nameWithoutExtension}_injected.apk"
                        ).file.absolutePath
                    }.getOrNull()
                }
                tmp.delete()
                state = state.copy(
                    steps = state.steps + saveEndStep(savedPath),
                    progress = 1f,
                )
            }

            val elapsed = System.currentTimeMillis() - startedAt
            val ok = r.ok && savedPath != null
            state = state.copy(
                running = false,
                stage = null,
                progress = if (ok) 1f else state.progress,
                result = if (ok) r else (r.copy(error = r.error ?: "产物写入 Download 失败")),
                savedPath = savedPath,
                elapsedMs = elapsed,
            )

            // 留一条记录：跑的包、用的配置、注入点、产物，全部钉住
            withContext(Dispatchers.IO) {
                tasks.add(
                    TaskStore.Record(
                        id = "task_${System.currentTimeMillis()}",
                        apkName = apk.name,
                        apkLabel = snapshot.apkLabel,
                        apkPath = apk.absolutePath,
                        apkSize = apk.length(),
                        packageName = snapshot.packageName,
                        targetClass = r.resolvedClass ?: snapshot.targetClass,
                        configId = snapshot.activeConfigId,
                        configName = snapshot.activeConfig?.name ?: "自定义调用",
                        mode = snapshot.mode.name,
                        targetNames = r.targets.map { it.methodName },
                        targetMethod = r.resolvedMethod ?: snapshot.targetMethod,
                        targetSignature = r.resolvedSignature ?: snapshot.targetSignature,
                        outputPath = savedPath,
                        ok = ok,
                        injectedCount = r.injectedCount,
                        durationMs = elapsed,
                        at = System.currentTimeMillis(),
                    )
                )
            }
            state = state.copy(records = tasks.list())
        }
    }

    private fun saveStartStep(app: Application, tmp: File) = ApkInjectPipeline.Step(
        stage = ApkInjectPipeline.Stage.SAVE,
        label = "写入 Download",
        detail = OutputStore.displayDir(),
        progress = ApkInjectPipeline.progressOf(ApkInjectPipeline.Stage.SAVE, 0.2f),
    )

    private fun saveEndStep(path: String?) = ApkInjectPipeline.Step(
        stage = ApkInjectPipeline.Stage.SAVE,
        label = if (path != null) "已保存" else "写入失败",
        detail = path ?: "MediaStore 拒绝写入，请检查存储空间",
        warn = path == null,
        progress = 1f,
    )

    // ────────────────────── 记录 ──────────────────────

    /** 从历史记录恢复任务：APK 副本 + 配置，直接回到可执行状态。 */
    fun reuseRecord(record: TaskStore.Record) {
        val apk = record.apkFile
        if (apk == null || !apk.isFile) {
            state = state.copy(message = "这条记录的 APK 副本已不存在，请重新选包")
            return
        }
        state = state.copy(
            apkFile = apk,
            apkName = record.apkName,
            apkSize = apk.length(),
            packageName = record.packageName,
            targetClass = record.targetClass,
            targetMethod = record.targetMethod,
            targetSignature = record.targetSignature,
            result = null,
            savedPath = record.outputPath?.takeIf { File(it).isFile },
            steps = emptyList(),
            progress = 0f,
        )
        viewModelScope.launch {
            val inspect = withContext(Dispatchers.IO) {
                ClassInspector.inspect(apk, "L${record.targetClass.replace('.', '/')};")
            }
            val cfg = record.configId?.let { id -> configs.list().firstOrNull { it.id == id } }
            state = state.copy(
                classFound = inspect.found,
                classSuperType = inspect.superType,
                classMethods = inspect.methods,
                points = LifecyclePoints.forSuperClass(inspect.superType),
                configs = configs.list(),
            )
            if (cfg != null) {
                applyConfig(cfg)
            } else {
                recomputeResolution()
                state = state.copy(
                    message = "原配置「${record.configName}」已被删除，请重新选一套",
                )
            }
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tasks.delete(id) }
            state = state.copy(records = tasks.list(), message = "记录已删除")
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tasks.clear() }
            state = state.copy(records = tasks.list(), message = "记录已清空（产物文件保留）")
        }
    }

    /** 「再跑一次」：保留包与配置，只清上一轮结果。 */
    fun rerun() {
        state = state.copy(
            result = null, savedPath = null,
            steps = emptyList(), progress = 0f, elapsedMs = 0L,
        )
        run()
    }

    fun showMessage(m: String) {
        state = state.copy(message = m)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}

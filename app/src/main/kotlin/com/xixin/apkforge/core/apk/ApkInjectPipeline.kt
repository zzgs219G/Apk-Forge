package com.xixin.apkforge.core.apk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.axml.AxmlManifest
import com.xixin.apkforge.core.dex.ClassInspector
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.InjectionPlanner
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.core.dex.SmaliCallParser

/**
 * 注入流水线：APK → 解析 LAUNCHER → 定位 dex → 插调用（缺失则建方法）→ 追加 payload → 重打包。
 *
 * 【为什么要「阶段 + 进度」】旧版只回调一句日志，UI 只能转圈。进度条要动得**真实**，
 * 就必须让流水线自己说出「现在在整条链路的哪一段」。所以每条 [Step] 自带
 * [Step.stage]（阶段）、[Step.sub]（阶段内进度）与算好的 [Step.progress]（全局 0~1）。
 * 顺手把重打包的逐条目写入也接上回调——那几百个条目正是「日志飞速滚动」的真实来源，
 * 而不是拿假日志糊弄人。
 */
class ApkInjectPipeline(private val context: Context) {

    /**
     * 流水线阶段。`weight` 是这一段的相对耗时权重，用来把「阶段」换算成连续进度：
     * 重打包占 4 份（真的最慢），定位类占 2.5 份（多 dex 要逐个扫），注入占 3 份（要重写 dex 池）。
     */
    enum class Stage(val label: String, val weight: Float) {
        READ("读取安装包", 1f),
        MANIFEST("解析清单", 1f),
        LOCATE("定位目标类", 2.5f),
        INJECT("注入调用", 3f),
        PAYLOAD("准备载荷", 1f),
        REPACK("重写压缩包", 4f),
        SAVE("落盘 Download", 1f),
    }

    data class Input(
        val sourceApk: File,
        val outputApk: File,
        /** 注入点完整类名；留空则自动取 Manifest 的 LAUNCHER */
        val targetClass: String? = null,
        /**
         * 注入点方法名。**留空表示自动**——由 [LifecyclePoints.resolve] 结合类里
         * 真实存在的方法与 native 降级规则挑一个，不再要求用户手选。
         */
        val targetMethod: String = "",
        /** 注入模式，决定铺几个方法（见 [InjectionMode]） */
        val mode: InjectionMode = InjectionMode.ONCE,
        /** 注入点方法签名，默认 (Landroid/os/Bundle;)V */
        val targetSignature: String = "(Landroid/os/Bundle;)V",
        /** 用户粘贴的 smali 调用行（可多行、可含 # 注释） */
        val callsText: String,
        /** payload dex；null 表示不追加 */
        val payloadDex: File? = null,
        /**
         * 随 payload 一起写进宿主的额外条目（path → 内容）。
         *
         * 插件包功能用它把 `assets/apkforge_dialog.json` 塞进宿主：
         * 载荷 dex 只负责代码，配置必须能被用户在 MT 里直接改，
         * 所以它是资产文件而不是编译进 dex 的常量。
         */
        val extraAssets: Map<String, ByteArray> = emptyMap(),
    )

    /**
     * 一条执行记录。
     *
     * @param stage 阶段，决定进度条落在哪一段
     * @param sub 阶段内完成度 0~1；一条日志报完整个阶段就传 1
     * @param progress 全局进度 0~1，由 [stage] + [sub] 折算，UI 直接渲染
     * @param atMs 相对开始的时间偏移，日志左侧的 `+1.24s`
     */
    data class Step(
        val stage: Stage,
        val label: String,
        val detail: String = "",
        val warn: Boolean = false,
        val sub: Float = 1f,
        val progress: Float = 0f,
        val atMs: Long = 0L,
    )

    data class Result(
        val ok: Boolean,
        val steps: List<Step>,
        val error: String? = null,
        val outputApk: File? = null,
        val packageName: String? = null,
        val launcherFound: List<String> = emptyList(),
        val resolvedClass: String? = null,
        /** 真正落笔的方法名。请求的是 native 方法时，这里会是自动挑的替代点 */
        val resolvedMethod: String? = null,
        val resolvedSignature: String? = null,
        val createdMethod: Boolean = false,
        /** 每个真正落笔的方法一条；多点/替换模式下会有多条 */
        val targets: List<DexCallInjector.TargetResult> = emptyList(),
        val injectedCount: Int = 0,
        val stats: ApkRepackager.Stats? = null,
        val durationMs: Long = 0L,
    )

    /** 只解析 Manifest，用于「选完 APK 就自动填类名」。 */
    fun peekManifest(apk: File): AxmlManifest.Result? = try {
        ApkRepackager.readEntry(apk, MANIFEST)?.let { AxmlManifest.parseBytes(it) }
    } catch (_: Exception) {
        null
    }

    suspend fun run(input: Input, onStep: (Step) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val steps = mutableListOf<Step>()
            val t0 = System.nanoTime()

            fun elapsed() = (System.nanoTime() - t0) / 1_000_000

            fun step(
                stage: Stage,
                label: String,
                detail: String = "",
                warn: Boolean = false,
                sub: Float = 1f,
            ) {
                val s = Step(
                    stage = stage,
                    label = label,
                    detail = detail,
                    warn = warn,
                    sub = sub,
                    progress = progressOf(stage, sub),
                    atMs = elapsed(),
                )
                steps += s
                onStep(s)
            }

            try {
                require(input.sourceApk.isFile) { "APK 不存在：${input.sourceApk.absolutePath}" }

                // ── 1. 读包 ──
                step(
                    Stage.READ, "打开安装包",
                    "${input.sourceApk.name} · ${mb(input.sourceApk.length())}",
                )

                // ── 2. Manifest ──
                val manifestBytes = ApkRepackager.readEntry(input.sourceApk, MANIFEST)
                    ?: return@withContext Result(false, steps, "APK 里没有 AndroidManifest.xml，不是可安装的包")
                val manifest = AxmlManifest.parseBytes(manifestBytes)
                step(
                    Stage.MANIFEST, "解析 AndroidManifest",
                    "二进制 AXML · ${bytes(manifestBytes.size)}",
                )
                step(
                    Stage.MANIFEST, "包信息",
                    "package=${manifest.packageName ?: "?"} · LAUNCHER ${manifest.launcherActivities.size} 个",
                )

                // ── 3. 注入点 ──
                val resolved = input.targetClass?.trim()?.takeIf { it.isNotEmpty() }
                    ?: manifest.primaryLauncher
                    ?: return@withContext Result(
                        false, steps,
                        "没填类名，且 Manifest 里找不到 LAUNCHER activity。请手动填写完整类名"
                    )
                val src = if (!input.targetClass.isNullOrBlank()) "用户指定" else "自动取自 LAUNCHER"
                step(Stage.MANIFEST, "注入点", "$resolved（$src）")

                // ── 4. 解析调用 ──
                val (calls, errors) = SmaliCallParser.parseAll(input.callsText)
                if (!errors.isEmpty) {
                    return@withContext Result(
                        false, steps,
                        "调用解析失败：\n" + errors.messages.joinToString("\n")
                    )
                }
                if (calls.isEmpty()) {
                    return@withContext Result(false, steps, "没有解析出任何调用，请粘贴 invoke 行")
                }
                step(Stage.INJECT, "解析调用行", "${calls.size} 条 invoke")
                // 把每条调用的落点单独列一行：出问题时用户第一件想确认的事
                // 就是「我插的到底是哪个类的方法」，而原始 smali 行太长，日志里读不出来。
                // 逐条列出，不省略：日志是给人（也给 AI）看的，省略号等于把需求砍掉。
                // 条数确实可能很多，但这里全量列出——界面上是单行滚动区，不占版面。
                calls.forEach { c ->
                    step(
                        Stage.INJECT, "→ ${c.owner.simpleType()}.${c.name}",
                        c.kind.opcodeBase +
                            c.paramTypes.joinToString("", "(", ")") + c.returnType,
                    )
                }

                // ── 5. 定位 dex（逐包上报，日志会一行行刷过去）──
                val dexNames = ApkRepackager.listDexNames(input.sourceApk)
                if (dexNames.isEmpty()) {
                    return@withContext Result(false, steps, "APK 内没有 dex")
                }
                step(Stage.LOCATE, "扫描 dex 清单", "${dexNames.size} 个：${dexNames.joinToString()}")

                val ownerType = "L${resolved.replace('.', '/')};"
                var hitDex: String? = null
                var hitBytes: ByteArray? = null
                dexNames.forEachIndexed { i, name ->
                    val bytes = ApkRepackager.readEntry(input.sourceApk, name)
                    val hit = bytes != null && DexCallInjector.containsClass(bytes, ownerType)
                    step(
                        Stage.LOCATE, if (hit) "命中 $name" else "扫描 $name",
                        if (hit) "找到 ${simple(resolved)}" else "${bytes?.let { bytes(it.size) } ?: "读取失败"}",
                        sub = (i + 1).toFloat() / dexNames.size,
                    )
                    if (hit && hitDex == null) {
                        hitDex = name
                        hitBytes = bytes
                    }
                }
                val dexName = hitDex
                val dexBytes = hitBytes
                if (dexName == null || dexBytes == null) {
                    return@withContext Result(
                        false, steps,
                        "所有 dex 里都没有类 $resolved。请确认类名完整（含包名）"
                    )
                }

                // ── 6. 决定真正落笔的方法 ──
                // 加固壳 / NativeActivity 的 onCreate 常是 native：没有字节码，插不了。
                // 这时按 LifecyclePoints.CREATE_FALLBACKS 退到语义最接近的点，而不是直接报错。
                val inspect = ClassInspector.inspect(input.sourceApk, ownerType)
                val points = LifecyclePoints.forSuperClass(inspect.superType)
                // 注入点默认「自动」：input.targetMethod 为空就交给 onCreate 走 resolve，
                // 它会处理 native 降级。用户显式填了名字则尊重他（含自定义方法）。
                val wanted = input.targetMethod.trim().takeIf { it.isNotEmpty() }
                    ?.let { name ->
                        points.firstOrNull { it.name == name }
                            ?: LifecyclePoints.Point(name, input.targetSignature, "自定义方法")
                    }
                    ?: points.firstOrNull { it.name == "onCreate" }
                    ?: points.first()
                val resolution = LifecyclePoints.resolve(points, inspect.methods, wanted)
                val target = resolution.target
                if (resolution.fallback || resolution.reason != null) {
                    step(
                        Stage.LOCATE,
                        if (resolution.fallback) "注入点已降级" else "注入点不可用",
                        resolution.reason.orEmpty(),
                        warn = true,
                    )
                }

                // 模式决定铺几个点。规划在纯 JVM 的 InjectionPlanner 里做，
                // 这里只负责把结论回报成日志——用户看到的每个方法名都对应一次真实改写。
                val plan = InjectionPlanner.plan(
                    mode = input.mode,
                    points = points,
                    methods = inspect.methods,
                    wanted = target,
                )
                step(Stage.LOCATE, "注入模式", "${input.mode.label} · ${plan.targets.size} 个落点")
                plan.note?.let { step(Stage.LOCATE, "模式说明", it) }

                // ── 7. 注入（逐落点改写 dex，后一个落点接着前一个的产物继续改）──
                // 显式钉成非空：dexBytes 是从可空变量里取出来的，进了 lambda 后
                // 智能转换失效，编译器会把它当 ByteArray? 用。
                var newDexBytes: ByteArray = dexBytes
                val targetResults = mutableListOf<DexCallInjector.TargetResult>()
                plan.targets.forEachIndexed { i, t ->
                    val spec = DexCallInjector.Spec(
                        ownerType = ownerType,
                        methodName = t.point.name,
                        methodSignature = t.point.signature,
                        calls = calls,
                        replaceBody = t.replaceBody,
                    )
                    val outcome = DexCallInjector.inject(newDexBytes, spec)
                    newDexBytes = when (outcome) {
                        is DexCallInjector.Outcome.Injected -> {
                            step(
                                Stage.INJECT,
                                if (outcome.createdMethod) "新建方法并注入" else "注入调用",
                                if (outcome.createdMethod) {
                                    "${t.point.name}${t.point.signature} 不存在，已新建（含父类调用），插入 ${outcome.injectedCount} 条"
                                } else {
                                    "在 ${t.point.name} 开头插入 ${outcome.injectedCount} 条调用"
                                },
                                sub = (i + 1).toFloat() / plan.targets.size,
                            )
                            outcome.warnings.forEach { step(Stage.INJECT, "警告", it, warn = true) }
                            targetResults += DexCallInjector.TargetResult(
                                methodName = t.point.name,
                                signature = t.point.signature,
                                createdMethod = outcome.createdMethod,
                                injectedCount = outcome.injectedCount,
                            )
                            outcome.dex
                        }
                        is DexCallInjector.Outcome.Skipped ->
                            return@withContext Result(false, steps, "注入跳过: ${outcome.reason}")
                        is DexCallInjector.Outcome.Failed ->
                            return@withContext Result(false, steps, "注入失败: ${outcome.reason}")
                    }
                }
                step(
                    Stage.INJECT, "回写 dex 池",
                    "$dexName ${bytes(dexBytes.size)} → ${bytes(newDexBytes.size)}",
                )

                // ── 8. payload ──
                val payloadBytes = input.payloadDex?.let { f ->
                    require(f.isFile) { "payload dex 不存在：${f.absolutePath}" }
                    val raw = f.readBytes()
                    require(
                        raw.size > 8 &&
                            raw[0] == 'd'.code.toByte() && raw[1] == 'e'.code.toByte() &&
                            raw[2] == 'x'.code.toByte() && raw[3] == '\n'.code.toByte()
                    ) { "payload 不是合法 dex（缺少 dex\\n 魔数）：${f.name}" }
                    step(Stage.PAYLOAD, "读取载荷 dex", "${f.name} · ${bytes(raw.size)}")
                    raw
                } ?: run {
                    step(Stage.PAYLOAD, "跳过载荷", "本次不追加 payload dex")
                    null
                }

                if (input.extraAssets.isNotEmpty()) {
                    step(
                        Stage.PAYLOAD, "附带资产",
                        input.extraAssets.keys.joinToString() + " · " +
                            input.extraAssets.values.sumOf { it.size }.let { bytes(it) },
                    )
                }

                // ── 9. 重打包（逐条目上报，限流后约 60~80 行日志）──
                step(
                    Stage.REPACK, "重写压缩包",
                    "替换 $dexName" + if (payloadBytes != null) " + 追加 payload" else "",
                )
                // 条目可能上千，全部上报会把日志刷成噪音；按总量切 ~60 段采样
                var lastReported = -1
                val stats = ApkRepackager.repack(
                    sourceApk = input.sourceApk,
                    outputApk = input.outputApk,
                    targetDexName = dexName,
                    newDexBytes = newDexBytes,
                    payloadDexBytes = payloadBytes,
                    extraEntries = input.extraAssets,
                    onEntry = { index, total, name ->
                        val bucket = if (total <= 60) index else index * 60 / total
                        if (bucket != lastReported) {
                            lastReported = bucket
                            step(
                                Stage.REPACK, "写入 $name", "$index/$total 条目",
                                sub = index.toFloat() / total,
                            )
                        }
                    },
                )
                step(
                    Stage.REPACK, "完成",
                    "${stats.totalEntries} 条目 · ${mb(stats.originalSize)} → ${mb(stats.outputSize)}",
                )
                if (stats.addedAssets.isNotEmpty()) {
                    step(Stage.REPACK, "写入资产", stats.addedAssets.joinToString())
                }

                val dur = elapsed()
                Result(
                    ok = true,
                    steps = steps,
                    outputApk = input.outputApk,
                    packageName = manifest.packageName,
                    launcherFound = manifest.launcherActivities,
                    resolvedClass = resolved,
                    resolvedMethod = target.name,
                    resolvedSignature = target.signature,
                    createdMethod = resolution.candidates
                        .firstOrNull { it.point.name == target.name }
                        ?.status == LifecyclePoints.Status.MISSING,
                    targets = targetResults,
                    stats = stats,
                    // 语义：注入的调用总条数 = 落点数 × 每个方法插入的条数。
                    // 单次模式下两者相等；多点模式会翻倍，UI 直接照这个数字说事。
                    injectedCount = calls.size * targetResults.size,
                    durationMs = dur,
                )
            } catch (e: Exception) {
                Result(false, steps, e.message ?: e.javaClass.simpleName, durationMs = elapsed())
            }
        }

    companion object {
        private const val MANIFEST = "AndroidManifest.xml"

        val STAGES: List<Stage> = Stage.entries
        private val TOTAL_WEIGHT: Float = STAGES.sumOf { it.weight.toDouble() }.toFloat()

        /** 阶段 + 阶段内进度 → 全局 0~1。让进度条连续，不在阶段交界处跳。 */
        fun progressOf(stage: Stage, sub: Float = 1f): Float {
            val before = STAGES.takeWhile { it != stage }.sumOf { it.weight.toDouble() }.toFloat()
            val s = sub.coerceIn(0f, 1f)
            return ((before + s * stage.weight) / TOTAL_WEIGHT).coerceIn(0f, 1f)
        }

        private fun mb(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        private fun bytes(n: Int): String = mb(n.toLong())

        private fun simple(cls: String): String = cls.substringAfterLast('.')

        /** `Lcom/foo/Bar;` → `com.foo.Bar` */
        private fun String.simpleType(): String =
            trimStart('L').trimEnd(';').replace('/', '.')
    }
}

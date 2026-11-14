package com.xixin.apkforge.core.apk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.axml.AxmlManifest
import com.xixin.apkforge.core.dex.ClassInspector
import com.xixin.apkforge.core.dex.DexCallInjector
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
        /** 注入点方法名，默认 onCreate；不存在时会新建 */
        val targetMethod: String = "onCreate",
        /** 注入点方法签名，默认 (Landroid/os/Bundle;)V */
        val targetSignature: String = "(Landroid/os/Bundle;)V",
        /** 用户粘贴的 smali 调用行（可多行、可含 # 注释） */
        val callsText: String,
        /** payload dex；null 表示不追加 */
        val payloadDex: File? = null,
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
                calls.take(MAX_CALL_LOGS).forEach { c ->
                    step(
                        Stage.INJECT, "→ ${c.owner.simpleType()}.${c.name}",
                        c.kind.opcodeBase +
                            c.paramTypes.joinToString("", "(", ")") + c.returnType,
                    )
                }
                if (calls.size > MAX_CALL_LOGS) {
                    step(Stage.INJECT, "…", "另有 ${calls.size - MAX_CALL_LOGS} 条调用未逐条列出")
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
                val wanted = points.firstOrNull { it.name == input.targetMethod }
                    ?: LifecyclePoints.Point(
                        input.targetMethod, input.targetSignature, "自定义方法"
                    )
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
                step(
                    Stage.LOCATE, "注入点确认",
                    when (LifecyclePoints.statusOf(target, inspect.methods)) {
                        LifecyclePoints.Status.EXISTS -> "${target.name}${target.signature} 已存在，插在方法开头"
                        LifecyclePoints.Status.MISSING -> "${target.name}${target.signature} 不存在，将新建并补父类调用"
                        LifecyclePoints.Status.NATIVE -> "${target.name} 是 native，无法注入"
                    },
                )

                // ── 7. 注入 ──
                val spec = DexCallInjector.Spec(
                    ownerType = ownerType,
                    methodName = target.name,
                    methodSignature = target.signature,
                    calls = calls,
                )
                val newDexBytes = when (val o = DexCallInjector.inject(dexBytes, spec)) {
                    is DexCallInjector.Outcome.Injected -> {
                        step(
                            Stage.INJECT, if (o.createdMethod) "新建方法并注入" else "注入调用",
                            if (o.createdMethod) {
                                "${target.name}${target.signature} 不存在，已新建（含父类调用），插入 ${o.injectedCount} 条"
                            } else {
                                "在 ${target.name} 开头插入 ${o.injectedCount} 条调用"
                            },
                        )
                        o.warnings.forEach { step(Stage.INJECT, "警告", it, warn = true) }
                        o.dex
                    }
                    is DexCallInjector.Outcome.Skipped ->
                        return@withContext Result(false, steps, "注入跳过: ${o.reason}")
                    is DexCallInjector.Outcome.Failed ->
                        return@withContext Result(false, steps, "注入失败: ${o.reason}")
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
                    stats = stats,
                    injectedCount = calls.size,
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

        /** 日志里最多逐条列出的调用数，避免一次粘 50 行把终端刷满。 */
        private const val MAX_CALL_LOGS = 5

        private fun simple(cls: String): String = cls.substringAfterLast('.')

        /** `Lcom/foo/Bar;` → `com.foo.Bar` */
        private fun String.simpleType(): String =
            trimStart('L').trimEnd(';').replace('/', '.')
    }
}

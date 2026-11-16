package com.xixin.apkforge.core.apk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.axml.AxmlManifest
import com.xixin.apkforge.core.dex.ClassInspector
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.core.dex.InjectIdempotency
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.InjectionPlanner
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.core.dex.PackIntegrity
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.core.dex.patch.FastInjector

/**
 * 注入流水线：APK → 解析 LAUNCHER → 定位 dex → 插调用（缺失则建方法）→ 追加 payload → 重打包。
 *
 * 【为什么不持有 Context】这条流水线原来有一个 `private val context: Context`，
 * 但全文件没用到它一次——留着它等于把「核心逻辑可在纯 JVM 上跑」这条铁律
 * 降级成一句注释：`core/` 里出现 `android.*` 就会失去端到端单测能力
 * （AGENTS.md §1.1）。去掉之后 `ApkInjectPipeline` 可以直接在 JVM 上被测试，
 * 而不只是它调用的 `DexCallInjector` / `ApkRepackager`。
 *
 * 需要 Context 的事（落盘到 Download、读配置）都在 `data/` 层做，由 ViewModel 编排。
 *
 * 【为什么要「阶段 + 进度」】旧版只回调一句日志，UI 只能转圈。进度条要动得**真实**，
 * 就必须让流水线自己说出「现在在整条链路的哪一段」。所以每条 [Step] 自带
 * [Step.stage]（阶段）、[Step.sub]（阶段内进度）与算好的 [Step.progress]（全局 0~1）。
 * 顺手把重打包的逐条目写入也接上回调——那几百个条目正是「日志飞速滚动」的真实来源，
 * 而不是拿假日志糊弄人。
 */
class ApkInjectPipeline {

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
        /**
         * payload dex；null 表示不追加。
         *
         * 注意这里**刻意没有**「额外资产」这种入参。载荷需要的配置必须烘焙进
         * dex（见 [com.xixin.apkforge.core.dex.PayloadBaker]），理由：
         * 注入一个包只应该让它多一个 `classesN.dex`，不该改写宿主自己的 assets。
         * 想加资产时请先想清楚——这是产品边界的改变，不是便利性的加法。
         */
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

    /**
     * @param onStep 每个里程碑回调一次，会**追加**到日志列表
     * @param onTick 只更新进度与「当前正在做什么」的单行摘要，**不追加日志**。
     *               用于大条目压缩这类「一件事要干两秒」的场景：
     *               进度条要动、单行日志要变，但日志列表不该被同一件事刷屏。
     */
    suspend fun run(
        input: Input,
        /** 高频状态回调，放在 onStep 之前以免破坏既有的尾随 lambda 调用点 */
        onTick: ((progress: Float, line: String) -> Unit)? = null,
        onStep: (Step) -> Unit = {},
    ): Result =
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
                // 注意这一步归 Stage.MANIFEST 而不是 Stage.INJECT：进度是按
                // `Stage` 声明顺序折算的（READ→MANIFEST→LOCATE→INJECT→…），
                // 而「解析调用行」发生在定位 dex 之前。挂到 INJECT 上会让进度
                // 先跳到 0.33（INJECT 段）再掉回 0.15（LOCATE 段）——
                // UI 用 `maxOf` 掩盖了倒退，但「进度不许倒退」是坑 22 的契约。
                // 这条由 `PipelineEndToEndTest.整条流水线跑通并只多一个 dex` 钉住。
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
                step(Stage.MANIFEST, "解析调用行", "${calls.size} 条 invoke")
                // 把每条调用的落点单独列一行：出问题时用户第一件想确认的事
                // 就是「我插的到底是哪个类的方法」，而原始 smali 行太长，日志里读不出来。
                // 逐条列出，不省略：日志是给人（也给 AI）看的，省略号等于把需求砍掉。
                // 条数确实可能很多，但这里全量列出——界面上是单行滚动区，不占版面。
                calls.forEach { c ->
                    step(
                        Stage.MANIFEST, "→ ${c.owner.simpleType()}.${c.name}",
                        c.kind.opcodeBase +
                            c.paramTypes.joinToString("", "(", ")") + c.returnType,
                    )
                }

                // ── 5. 定位 dex（逐包上报，日志会一行行刷过去）──
                val dexNames = ApkRepackager.listDexNames(input.sourceApk)
                if (dexNames.isEmpty()) {
                    return@withContext Result(false, steps, "APK 内没有 dex")
                }

                // 加固检测：只认确定性证据（魔数/头部字段/结构自相矛盾），
                // 不数阈值。全部 dex 都不可读时才拦，因为那时注入必然失败，
                // 而失败信息会误导成「类名写错」——真正的原因是 dex 被加密了。
                //
                // ⚠️ 这一段的每条 step 都必须是 sub = 0f：LOCATE 段后面还有
                // 逐 dex 扫描（从 1/N 报起），这里报 1f 会让进度先冲顶再退回。
                val integrity = PackIntegrity.inspect(input.sourceApk)
                integrity.issues.forEach { issue ->
                    step(Stage.LOCATE, "dex 结构异常：${issue.entry}", issue.detail, warn = true, sub = 0f)
                }
                integrity.hint?.let {
                    step(Stage.LOCATE, "疑似加固包", it, warn = true, sub = 0f)
                }
                if (integrity.blocked) {
                    return@withContext Result(
                        false, steps,
                        integrity.hint ?: "所有 dex 都无法解析，注入不可能成功。请换一个未加固的包"
                    )
                }
                // sub 从 0 起：紧接着的逐 dex 扫描会从 1/N 往上报，
                // 若这里默认 sub=1f，进度会先冲到本段末尾再退回 1/N（倒退）。
                step(
                    Stage.LOCATE, "扫描 dex 清单",
                    "${dexNames.size} 个：" + dexNames.joinToString() +
                        if (integrity.issues.isEmpty()) " · 头部全部合法"
                        else " · ${integrity.issues.size} 处头部异常",
                    sub = 0f,
                )

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
                // 被幂等保护跳过的落点。空表示这次没有任何方法被跳过。
                val skippedTargets = mutableListOf<String>()

                plan.targets.forEachIndexed { i, t ->
                    val spec = DexCallInjector.Spec(
                        ownerType = ownerType,
                        methodName = t.point.name,
                        methodSignature = t.point.signature,
                        calls = calls,
                        replaceBody = t.replaceBody,
                    )

                    // 幂等保护：方法开头已经是这条调用就跳过。
                    // 判据在 InjectIdempotency 里（精确比对 owner/name/参数/返回值），
                    // 不看「载荷类有没有被调用过」——那会误伤「同一个类要插两个方法」。
                    when (val verdict = InjectIdempotency.check(input.sourceApk, spec)) {
                        is InjectIdempotency.Verdict.AlreadyInjected -> {
                            step(
                                Stage.INJECT,
                                "跳过重复注入：${t.point.name}",
                                InjectIdempotency.message(verdict, t.point.name),
                                warn = true,
                                sub = (i + 1).toFloat() / plan.targets.size,
                            )
                            skippedTargets += t.point.name
                            return@forEachIndexed
                        }
                        InjectIdempotency.Verdict.Clean,
                        InjectIdempotency.Verdict.Unknown -> Unit
                    }

                    // 先试增量写入：只追加新 code_item + 改 4 字节偏移，
                    // 实测 40MB dex 从 ~9000ms 降到 ~80ms。
                    // 不适用时（新建方法 / 替换方法体 / 带 try-catch / 引用不在池里）
                    // 会返回 NotApplicable，那时退回全量 DexPool 路径并说明原因。
                    val tFast0 = System.nanoTime()
                    // 注入要 1 秒以上，期间必须让「现在在干什么」动起来。
                    // 阶段名来自 PoolInjector 内部的 lap()，这里是给用户看的中文说明。
                    val stageText = mapOf(
                        "1-read-sections" to "读取各段索引",
                        "2-read-strings" to "解析字符串池",
                        "5-types" to "合并类型池",
                        "7-fields" to "重排字段池",
                        "9-classdefs" to "重排类定义",
                        "10b-3a-locate" to "定位注入点",
                        "10b-3b-prepare" to "准备重写方法体",
                        "10b-codeitem-done" to "重建方法体",
                        "10b-annot-done" to "重写注解",
                        "10c-typelist-done" to "重写参数表",
                        "11-ids-done" to "重建 id 表",
                        "11a-static-values" to "重写静态初值",
                        "11c-classdata" to "重写类数据",
                        "11b-classdefs-buf" to "重建类定义表",
                        "12-layout" to "排版 dex 各段",
                        "12a-before-fillback" to "回填交叉引用",
                        "12a-fillback-done" to "回填完成",
                        "12b-insn-patch" to "回填指令索引",
                    )
                    val fast = FastInjector.tryInject(newDexBytes, spec) { stage ->
                        // 「10b-annot-done 15334」这种带统计量的，切掉后半段再查表
                        val key = stage.substringBefore(' ')
                        val label = stageText[key] ?: "注入中"
                        onTick?.invoke(
                            progressOf(Stage.INJECT, 0.5f),
                            "$label  ·  $stage",
                        )
                    }
                    val fastMs = (System.nanoTime() - tFast0) / 1_000_000
                    if (fast is FastInjector.Result.Injected) {
                        step(
                            Stage.INJECT, "注入调用（增量）",
                            "在 ${t.point.name} 开头插入 ${fast.injectedCount} 条调用 · " +
                                "${fastMs}ms · ${fast.note}",
                            sub = (i + 1).toFloat() / plan.targets.size,
                        )
                        fast.warnings.forEach { step(Stage.INJECT, "警告", it, warn = true) }
                        targetResults += DexCallInjector.TargetResult(
                            methodName = t.point.name,
                            signature = t.point.signature,
                            createdMethod = false,
                            injectedCount = fast.injectedCount,
                        )
                        newDexBytes = fast.dex
                        return@forEachIndexed
                    }

                    // 回退不是错误，但必须说清「为什么这次走慢路径」——
                    // 否则用户看到耗时差异会以为是随机波动。
                    step(
                        Stage.INJECT, "增量不适用，改用全量写入",
                        (fast as FastInjector.Result.NotApplicable).reason,
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
                // 全部落点都被幂等保护跳过：这时产物会和源包一模一样（只多一个 payload dex），
                // 产出它没有意义，而且会让用户以为「注入成功了」。如实说清楚并停下。
                if (targetResults.isEmpty() && skippedTargets.isNotEmpty()) {
                    return@withContext Result(
                        false, steps,
                        "没有可注入的落点：${skippedTargets.joinToString()} 开头已经是我们这条调用。" +
                            "重复注入只会让载荷执行多次，已停止。请从注入前的原始包重新开始。",
                    )
                }

                step(
                    Stage.INJECT, "回写 dex 池",
                    "$dexName ${bytes(dexBytes.size)} → ${bytes(newDexBytes.size)}" +
                        " · dex ${DexOpcodes.label(dexBytes)}",
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
                // 同上：这一段之后还有逐落点的真实进度，这里先报 0 而不是 1
                step(
                    Stage.REPACK, "重写压缩包",
                    "替换 $dexName" + if (payloadBytes != null) " + 追加 payload" else "",
                    sub = 0f,
                )
                // 条目可能上千，全部上报会把日志刷成噪音；按总量切 ~60 段采样
                var lastReported = -1
                var repackIndex = 0
                var repackTotal = 1
                val stats = ApkRepackager.repack(
                    sourceApk = input.sourceApk,
                    outputApk = input.outputApk,
                    targetDexName = dexName,
                    newDexBytes = newDexBytes,
                    payloadDexBytes = payloadBytes,
                    onEntry = { index, total, name ->
                        repackIndex = index
                        repackTotal = total
                        val bucket = if (total <= 60) index else index * 60 / total
                        if (bucket != lastReported) {
                            lastReported = bucket
                            step(
                                Stage.REPACK, "写入 $name", "$index/$total 条目",
                                sub = index.toFloat() / total,
                            )
                        }
                    },
                    // 大条目（classes2.dex 12.5MB）压缩要 2 秒，期间必须让进度动起来，
                    // 否则用户看到的就是「76/147 卡死两秒」。
                    // 这里只更新进度条与单行摘要，**不新增 step 记录** ——
                    // 50 次回调若每次都存一行，日志会被同一个条目的百分比刷屏。
                    onBytes = { written, totalBytes, name ->
                        val frac = if (totalBytes <= 0) 0f else written.toFloat() / totalBytes
                        val entryBase = repackIndex.toFloat() / repackTotal
                        val entrySpan = 1f / repackTotal
                        onTick?.invoke(
                            progressOf(Stage.REPACK, entryBase + entrySpan * frac),
                            "写入 $name  ${(frac * 100).toInt()}%  " +
                                "${mb(written.toLong())}/${mb(totalBytes.toLong())}",
                        )
                    },
                )
                step(
                    Stage.REPACK, "完成",
                    "${stats.totalEntries} 条目 · ${mb(stats.originalSize)} → ${mb(stats.outputSize)}",
                )

                // 重打包后两条「产物能不能装」的事实，必须报出来而不是留给用户试装：
                //  1. 对齐：STORED 条目丢了 extra field 的对齐填充（ZipEntry 不搬 extra），
                //     .arsc 未 4 字节对齐、.so 未页对齐都是装机/加载失败级别的问题；
                //  2. 签名：java.util.zip 重写后 APK Signing Block 必然丢失，
                //     产物是**完全未签名**的，不是「签名不匹配」——任何设备都装不上。
                if (stats.misalignedEntries > 0) {
                    step(
                        Stage.REPACK, "对齐异常",
                        "${stats.misalignedEntries} 个 STORED 条目未落在原有对齐上，" +
                            "产物可能装不上或资源加载失败。请把这条日志连同 APK 一起反馈",
                        warn = true,
                    )
                }
                if (stats.sourceHadSignature) {
                    step(
                        Stage.REPACK, "签名已失效",
                        "原包带 v2/v3 签名，重写 zip 后签名块丢失。产物是未签名包，" +
                            "需要自行用 MT / apksigner 重签后才能安装",
                        warn = true,
                    )
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

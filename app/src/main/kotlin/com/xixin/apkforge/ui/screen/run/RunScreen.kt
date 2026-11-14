package com.xixin.apkforge.ui.screen.run

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.anim.InjectArtAnimation
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.components.rememberCopyText

/**
 * 运行页（控制台的执行视图）。
 *
 * 【这一版和上一版的区别，都是被指出来才看清的】
 *
 * 1. **进度条只在跑的时候存在。** 上一版跑完仍把「100% + 进度条」摆在最上面，
 *    紧接着下面才是「注入成功」卡——同一件事说两遍，而且进度条此刻已经不表达任何信息。
 *    现在完成后整个进度区**撤掉**，由结果卡接管这一屏。
 *
 * 2. **日志默认是单行。** 用户要的是「一行实时刷新的状态」，不是一列要读的条目。
 *    运行中显示一行滚动的当前动作（左边小进度，右边耗时），
 *    点开才看完整历史——历史仍然逐条、可滚动、可勾选复制。
 *
 * 3. **日志可复制且不吞内容。** 上一版 `maxLines = 1 + Ellipsis` 把落点和方法签名
 *    直接吃掉，复制还无从下手——那既不给人看也不给 AI 看。现在：
 *    · 每一行可长按选择（`SelectionContainer`）；
 *    · 一键复制全文（带时间戳与阶段，贴给 AI 能直接定位）；
 *    · 换行不截断。
 *
 * 4. **结果卡内聚。** 结果卡自己包含：结论 + 改了什么 + 产物路径 + 操作按钮。
 *    上一版把「完成动画」画在卡外还叠着内容，纯属多余——动画应该是卡内的一个视觉元素。
 */
@Composable
fun RunScreen(
    vm: ConsoleViewModel,
    /** 返回上一页（注入配置）。跑完想换配置再跑一次，这是最常见的下一步。 */
    onBack: () -> Unit = {},
    /** 直接回控制台首页（结果卡里那颗按钮）。 */
    onExit: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current
    val copyText = rememberCopyText()

    Column(Modifier.fillMaxSize()) {
        RunTopBar(
            running = state.running,
            ok = state.result?.ok,
            onBack = onBack,
        )

        AnimatedContent(
            targetState = if (state.running) 1 else if (state.result != null) 2 else 0,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
            label = "runBody",
        ) { phase ->
            when (phase) {
                0 -> IdleHint(onBack = onBack)

                1 -> RunningBody(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> ResultBody(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onInstall = {
                        state.savedFile?.let { f ->
                            if (!OutputStore.installPath(context, f)) {
                                vm.showMessage("没有可用的安装器")
                            }
                        }
                    },
                    onLocate = {
                        state.savedFile?.let { f ->
                            if (!FilePicker.locateInMt(context, f)) {
                                vm.showMessage("未安装 MT 管理器")
                            }
                        }
                    },
                    onShare = {
                        state.savedFile?.let { f ->
                            if (!OutputStore.sharePath(context, f)) {
                                vm.showMessage("分享失败")
                            }
                        }
                    },
                    onRerun = vm::rerun,
                    onClose = onExit,
                    onCopy = copyText,
                )
            }
        }
    }
}

// ══════════════════════ 顶栏 ══════════════════════

@Composable
private fun RunTopBar(running: Boolean, ok: Boolean?, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        running -> "正在注入"
                        ok == true -> "注入完成"
                        ok == false -> "注入失败"
                        else -> "运行"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (running) "别退出，dex 正在重写" else "结果会一直留在记录里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) PulsingDot()
        }
    }
}

@Composable
private fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(680, easing = LinearEasing), RepeatMode.Reverse),
        label = "liveDot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = a))
        )
        HGap(6.dp)
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

// ══════════════════════ 运行中 ══════════════════════

/**
 * 运行中的整页。
 *
 * 结构（自上而下）：进度环与百分比 → 粗进度条 → **单行实时日志** → 展开区。
 * 单行区是主角：它回答「现在在干什么」，而且只占一行，剩下的空间给历史。
 */
@Composable
private fun RunningBody(state: ConsoleUiState, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "progress",
    )
    val current = state.steps.lastOrNull()
    var historyOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING)
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InjectArtAnimation(phaseLabel = "", size = 40.dp, showLabel = false)
            HGap(12.dp)
            Text(
                "${(animated * 100).toInt()}",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                "%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    state.stage?.label ?: "准备中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    String.format("%.1fs", (current?.atMs ?: 0L) / 1000.0),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Gap(12.dp)
        ForgeProgressBar(progress = animated, failed = false, running = true)
        Gap(16.dp)

        // ── 单行实时日志 ──
        // 这一行是「现在正在做什么」，不换行、自动吸底式更新（内容整体替换）。
        // 它替代了上一版「一列条目滚动」的主视觉——用户当时要的就是这一行。
        LiveLine(
            step = current,
            count = state.steps.size,
            open = historyOpen,
            onToggle = { historyOpen = !historyOpen },
        )

        if (historyOpen) {
            Gap(10.dp)
            StepHistory(
                steps = state.steps,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                "日志共 ${state.steps.size} 行，点上面「展开」可查看全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * 实时日志条。
 *
 * 【为什么是「一条」而不是「一列」】用户当时要的就是这个：运行中屏幕上只有
 * **当前正在做什么**，不是一列要读的条目。历史仍然在，点右侧「展开」就是全量。
 *
 * 【为什么它允许换行，而不是 `maxLines = 1 + Ellipsis`】
 * 一条日志的信息量全在**尾部**（落点方法名、签名、产物路径）。
 * 截断之后剩下的东西既读不出来，也贴不给 AI —— 那不是「整齐」，是把答案删了。
 * 所以这里刻意让 label / detail 各自换行：常态下还是一行，超长时往下长一点，
 * 但一个字都不丢。真要看全部，右侧「展开」给逐条历史 + 一键复制。
 */
@Composable
private fun LiveLine(
    step: ApkInjectPipeline.Step?,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 小光标：告诉用户这行还在动
            val t = rememberInfiniteTransition(label = "cursor")
            val a by t.animateFloat(
                1f, 0.15f,
                infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
                label = "cursorAlpha",
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        (if (step?.warn == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary).copy(alpha = a)
                    )
            )
            HGap(10.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    step?.label ?: "准备中",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (step?.warn == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (!step?.detail.isNullOrBlank()) {
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (open) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                    contentDescription = if (open) "收起日志" else "展开全部 $count 行",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ForgeProgressBar(progress: Float, failed: Boolean, running: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "bar")
    val shimmer by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val h = size.height
        val r = h / 2f
        drawLine(
            color = track,
            start = Offset(r, r),
            end = Offset(size.width - r, r),
            strokeWidth = h,
            cap = StrokeCap.Round,
        )
        val w = (size.width - h) * progress.coerceIn(0f, 1f)
        if (w > 0.5f) {
            drawLine(
                color = fill,
                start = Offset(r, r),
                end = Offset(r + w, r),
                strokeWidth = h,
                cap = StrokeCap.Round,
            )
            if (running) {
                val bandW = 44.dp.toPx().coerceAtMost(w)
                val headX = r + w
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f)),
                        startX = headX - bandW,
                        endX = headX,
                    ),
                    topLeft = Offset(headX - bandW, 0f),
                    size = androidx.compose.ui.geometry.Size(bandW, h),
                )
                drawCircle(
                    color = fill.copy(alpha = 0.22f * (0.5f + shimmer)),
                    radius = h * 1.5f,
                    center = Offset(headX, r),
                )
            }
        }
    }
}

/** 运行中的历史日志：逐条、可滚动、自动吸底。 */
@Composable
private fun StepHistory(steps: List<ApkInjectPipeline.Step>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= steps.lastIndex - 1
        }
    }
    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty() && atBottom) listState.scrollToItem(steps.size)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(count = steps.size) { i ->
                LogLine(step = steps[i], latest = i == steps.lastIndex)
            }
        }
    }
}

// ══════════════════════ 结果 ══════════════════════

@Composable
private fun ResultBody(
    state: ConsoleUiState,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onRerun: () -> Unit,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val r = state.result ?: return
    val ok = r.ok && state.savedPath != null
    // 日志展开状态是本页唯一的「视图偏好」，不放进 VM：它跟数据无关，只跟当下看的心情有关
    var logOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = AppConstants.SCREEN_EDGE_PADDING,
            end = AppConstants.SCREEN_EDGE_PADDING,
            top = 4.dp,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 一张卡说完：结论 / 改了什么 / 产物 / 动作。不再有独立的横幅 + 独立的产物卡。
        item {
            OutcomeCard(
                state = state,
                ok = ok,
                onInstall = onInstall,
                onLocate = onLocate,
                onShare = onShare,
                onCopy = onCopy,
            )
        }

        item {
            RoundSummary(state = state)
        }

        item {
            LogSection(
                steps = state.steps,
                open = logOpen,
                onToggle = { logOpen = !logOpen },
                onCopy = onCopy,
            )
        }

        item {
            // 上一版的「底部固定按钮条」被收进结果卡，这里只留重跑——
            // 回控制台是结果卡里的主按钮，重跑是次要动作，分开就不会误点。
            OutlinedButton(
                onClick = onRerun,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                HGap(6.dp)
                Text(if (ok) "再跑一次" else "重试")
            }
        }

        item { TextButton(onClick = onClose) { Text("回到控制台") } }
    }
}

/**
 * 结果卡：这一屏唯一的视觉重心。
 *
 * 完成动画放在卡头当图标用（六边形合拢 + 对勾/叉号），**不再画在内容之上**——
 * 上一版那个「遮挡」的观感就是这么来的：动画被当成覆盖层，而它本该是卡片的一部分。
 */
@Composable
private fun OutcomeCard(
    state: ConsoleUiState,
    ok: Boolean,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val r = state.result ?: return
    val accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val container = if (ok) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutcomeGlyph(ok = ok)
                HGap(14.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ok) "注入成功" else "注入失败",
                        style = MaterialTheme.typography.headlineSmall,
                        color = accent,
                    )
                    Gap(3.dp)
                    Text(
                        if (ok) {
                            val desc = r.targets.joinToString("、") { t ->
                                if (t.createdMethod) "新建 ${t.methodName}" else "写入 ${t.methodName}"
                            }
                            desc.ifBlank { "调用已插入" }
                        } else {
                            r.error ?: "未知错误"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                    )
                }
            }

            if (ok) {
                Gap(14.dp)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Gap(12.dp)

                FmtRow(
                    label = "落点",
                    value = r.targets.joinToString("\n") { t ->
                        "${t.methodName}${t.signature} · ${t.injectedCount} 条" +
                            if (t.createdMethod) "（新建）" else ""
                    },
                )
                Gap(10.dp)
                FmtRow(
                    label = "产物",
                    value = state.savedPath.orEmpty(),
                    mono = true,
                    onCopy = { onCopy(state.savedPath.orEmpty()) },
                )
                if (r.stats?.addedDex != null) {
                    Gap(10.dp)
                    FmtRow(label = "追加", value = r.stats.addedDex.orEmpty(), mono = true)
                }

                Gap(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp))
                        HGap(6.dp)
                        Text("安装")
                    }
                    OutlinedButton(onClick = onLocate, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        HGap(6.dp)
                        Text("MT 定位")
                    }
                    OutlinedButton(onClick = onShare, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        HGap(6.dp)
                        Text("分享")
                    }
                }
                Gap(10.dp)
                Text(
                    "未重签名：只能在已安装同签名包的设备上覆盖安装。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 结果卡里「标签 / 值」的两列行。值太长时换行，不截断。 */
@Composable
private fun FmtRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        HGap(8.dp)
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                ),
                color = if (mono) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 一行摘要：耗时 / 调用数 / 体积。数字用等宽，扫一眼就能比。 */
@Composable
private fun RoundSummary(state: ConsoleUiState) {
    val r = state.result ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Metric(String.format("%.1fs", state.elapsedMs / 1000.0), "总耗时")
            Metric("${r.injectedCount}", "注入调用")
            Metric("${r.targets.size}", "落点数")
            Metric("${(r.stats?.outputSize ?: 0L) / 1048576} MB", "产物")
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace
            ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 日志区。
 *
 * 三个必须同时成立：
 *  · **可复制**：一键拿到全部日志（含时间戳与阶段），贴给 AI 用；
 *  · **可折叠**：默认收起，只露第一行摘要与行数，不占满整屏；
 *  · **不吞内容**：展开后每行自动换行、可长按选择，没有省略号。
 */
@Composable
private fun LogSection(
    steps: List<ApkInjectPipeline.Step>,
    open: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "执行日志",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                HGap(8.dp)
                Text(
                    "${steps.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onCopy(renderLog(steps)) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制全部日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                TextButton(onClick = onToggle) {
                    Icon(
                        if (open) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    HGap(4.dp)
                    Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                }
            }

            Gap(6.dp)
            if (open) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            steps.forEach { LogLine(step = it, latest = false) }
                        }
                    }
                }
            } else {
                // 收起时给最后一条：用户此刻最想知道的「结束在哪一步」
                val last = steps.lastOrNull()
                Text(
                    last?.let { "${it.stage.label} · ${it.label}" } ?: "无日志",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(4.dp)
                Text(
                    "点「展开」查看全部；右上角可一键复制正文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 一条日志。
 *
 * 【关键约束】`maxLines = 1 + Ellipsis` 在这里是错的：日志的落点信息
 * （`→ com.x.demo.DemoDialog.inject(Landroid/app/Activity;)V`）全在行尾，
 * 截断等于只留下「→ com.x.demo...」，既读不出方法也贴不给 AI。
 * 所以这里允许换行，只靠等宽字体保持对齐感。
 */
@Composable
private fun LogLine(step: ApkInjectPipeline.Step, latest: Boolean) {
    val tint = when {
        step.warn -> MaterialTheme.colorScheme.error
        latest -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            String.format("+%.2fs", step.atMs / 1000.0),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            ),
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(46.dp),
        )
        HGap(6.dp)
        Text(
            step.label + if (step.detail.isNotBlank()) "  ${step.detail}" else "",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (latest) FontWeight.Medium else FontWeight.Normal,
            ),
            color = tint,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 把日志渲染成可复制的纯文本。
 *
 * 带阶段与时间戳：贴给 AI 时，对方能据此判断「卡在重打包」还是「解析就失败了」，
 * 而不需要用户再补充一句「它跑到一半不动了」。
 */
private fun renderLog(steps: List<ApkInjectPipeline.Step>): String = buildString {
    appendLine("# ApkForge 执行日志（${steps.size} 行）")
    steps.forEach { s ->
        append("+")
        append(String.format("%.2fs", s.atMs / 1000.0))
        append("  [")
        append(s.stage.label)
        append("] ")
        if (s.warn) append("! ")
        append(s.label)
        if (s.detail.isNotBlank()) {
            append("  ")
            append(s.detail)
        }
        appendLine()
    }
}

/** 完成动画：六边形描出 + 对勾/叉号。作为卡头图标使用，不覆盖内容。 */
@Composable
private fun OutcomeGlyph(ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(ok) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(if (ok) 460 else 320, easing = LinearEasing))
    }
    val p = grow.value

    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer {
                if (!ok) {
                    val shake = if (p < 1f) (1f - p) * 5f else 0f
                    translationX = kotlin.math.sin(p * 22f) * shake
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(52.dp)) {
            val w = size.minDimension
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = w * 0.44f
            val stroke = w * 0.055f

            drawHexRing(cx, cy, r, color.copy(alpha = 0.18f), stroke)
            drawHexRingProgress(cx, cy, r, color, stroke, p)

            if (ok) {
                val k = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
                if (k > 0f) {
                    val a = Offset(cx - r * 0.34f, cy)
                    val b = Offset(cx - r * 0.08f, cy + r * 0.26f)
                    val c = Offset(cx + r * 0.38f, cy - r * 0.26f)
                    drawLine(color, a, b, strokeWidth = stroke * 1.15f, cap = StrokeCap.Round)
                    if (k > 0.5f) {
                        val mid = b
                        drawLine(
                            color, mid,
                            Offset(mid.x + (c.x - mid.x) * ((k - 0.5f) / 0.5f),
                                mid.y + (c.y - mid.y) * ((k - 0.5f) / 0.5f)),
                            strokeWidth = stroke * 1.15f, cap = StrokeCap.Round,
                        )
                    }
                }
            } else {
                if (p > 0.4f) {
                    val k = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    val d = r * 0.3f
                    drawLine(
                        color, Offset(cx - d, cy - d),
                        Offset(cx - d + 2 * d * k, cy - d + 2 * d * k),
                        strokeWidth = stroke * 1.15f, cap = StrokeCap.Round,
                    )
                    drawLine(
                        color, Offset(cx + d, cy - d),
                        Offset(cx + d - 2 * d * k, cy - d + 2 * d * k),
                        strokeWidth = stroke * 1.15f, cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexRing(
    cx: Float, cy: Float, r: Float, color: Color, stroke: Float,
) {
    val path = androidx.compose.ui.graphics.Path()
    repeat(6) { i ->
        val a = Math.toRadians((-90 + i * 60).toDouble())
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexRingProgress(
    cx: Float, cy: Float, r: Float, color: Color, stroke: Float, p: Float,
) {
    val path = androidx.compose.ui.graphics.Path()
    val total = 6
    val steps = (total * p).toInt()
    val frac = (total * p) - steps
    val pts = (0..total).map { i ->
        val a = Math.toRadians((-90 + i * 60).toDouble())
        Offset(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat())
    }
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until steps.coerceAtMost(total)) path.lineTo(pts[i + 1].x, pts[i + 1].y)
    if (steps < total && frac > 0f) {
        val from = pts[steps]
        val to = pts[steps + 1]
        path.lineTo(from.x + (to.x - from.x) * frac, from.y + (to.y - from.y) * frac)
    }
    drawPath(
        path, color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round)
    )
}

@Composable
private fun IdleHint(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Gap(10.dp)
        Text("还没有在跑的任务", style = MaterialTheme.typography.titleMedium)
        Gap(4.dp)
        Text(
            "回控制台选包 → 选配置 → 开始注入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(14.dp)
        TextButton(onClick = onBack) { Text("回控制台") }
    }
}

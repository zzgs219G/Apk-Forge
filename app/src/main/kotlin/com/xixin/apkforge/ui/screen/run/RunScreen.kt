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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 运行页（控制台的执行视图）。
 *
 * 【为什么是整页而不是卡片内动画】注入是「几十秒的黑盒」：用户需要的是**确信它在动**，
 * 而不是几个跳动的点。所以这里用满屏终端日志 + 顶部真实进度条：
 *  · 进度来自流水线自己的阶段权重，不是假的匀速填充；
 *  · 日志每行带 `+1.24s` 时间戳，滚动到底部，快速刷过时像编译输出；
 *  · 结束时不弹 toast，而是原地把面板变成「结果」——眼睛不用重新找位置。
 *
 * 【完成动画的取向】成功是「六边形合拢 + 对勾描出」，失败是「边框抖动 + 叉号」。
 * 都控制在 400ms 内，因为用户此刻更想读路径与按钮，而不是看动画表演。
 */
@Composable
fun RunScreen(
    vm: ConsoleViewModel,
    /** 返回上一页（注入配置）。跑完想换配置再跑一次，这是最常见的下一步。 */
    onBack: () -> Unit = {},
    /** 直接回控制台首页（结果区那颗按钮）。 */
    onExit: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        RunTopBar(
            running = state.running,
            ok = state.result?.ok,
            onBack = onBack,
        )

        // 结果出现后整体切成结果视图；运行中则是进度 + 日志
        AnimatedContent(
            targetState = state.running || state.result != null,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "runBody",
        ) { active ->
            if (!active) {
                IdleHint(onBack = onBack)
            } else {
                Column(Modifier.fillMaxSize()) {
                    ProgressHeader(state)

                    if (state.result == null) {
                        LogTerminal(
                            steps = state.steps,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        ResultBody(
                            state = state,
                            modifier = Modifier.weight(1f),
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
                        )
                    }
                }
            }
        }

        if (state.result != null) {
            ResultActions(
                ok = state.result.ok,
                onRerun = vm::rerun,
                onClose = onExit,
            )
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回控制台")
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
            if (running) {
                PulsingDot()
            }
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

// ══════════════════════ 进度 ══════════════════════

/**
 * 顶部进度：数字 + 阶段名 + 进度条。
 *
 * 进度值来自流水线（`ApkInjectPipeline.progressOf`），弹簧平滑只为消除高频回调的抖动，
 * 不做额外加速——否则用户会觉得「进度条自己在跑，跟干活没关系」。
 */
@Composable
private fun ProgressHeader(state: ConsoleUiState) {
    val animated by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "progress",
    )
    val result = state.result
    val done = result != null

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // 运行中的图形锚点：六边形轨道描边生长 + 箭头呼吸。
            // 复用 ui/anim 里那套 SVG path 动画——它本来就是为「注入」画的，
            // 放在百分比左边，让这行数字有个可视的「正在写入」的对象。
            if (state.running) {
                InjectArtAnimation(
                    phaseLabel = "",
                    size = 34.dp,
                    showLabel = false,
                )
                HGap(10.dp)
            }
            Text(
                "${(animated * 100).toInt()}",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (result != null && !result.ok) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                "%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.running -> state.stage?.label ?: "准备中"
                    result?.ok == true -> "全部完成"
                    result != null -> "中断于 ${state.stage?.label ?: "执行阶段"}"
                    else -> "待命"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Gap(10.dp)
        ForgeProgressBar(
            progress = animated,
            failed = result != null && !result.ok,
            running = state.running,
        )
        if (result != null) {
            Gap(8.dp)
            Text(
                buildString {
                    append("总耗时 ")
                    append(String.format("%.1fs", state.elapsedMs / 1000.0))
                    append(" · 注入 ${result.injectedCount} 条调用")
                    result.stats?.addedDex?.let { append(" · 追加 $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 进度条。
 *
 * 造型上刻意不是 Material 的细横线：粗 10dp、圆头、进度头带一段渐隐光带，
 * 运行时有流光在头部游动。理由是这页的主角就是「进度」，
 * 它得比日志更抢眼，但也不能像进度环那样把日志挤掉。
 */
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
                // 头部流光：一段 40dp 的透明度渐变，跟着进度头跑
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
                // 呼吸光晕，让「在动」这件事更明确
                drawCircle(
                    color = fill.copy(alpha = 0.22f * (0.5f + shimmer)),
                    radius = h * 1.5f,
                    center = Offset(headX, r),
                )
            }
        }
    }
}

// ══════════════════════ 日志终端 ══════════════════════

/**
 * 终端日志。
 *
 * 关键观感：**单行滚动、快速刷过**。所以
 *  · 固定行高、等宽字体，不换行（超长截断），滚动节奏才稳；
 *  · 每行前面跟 `+0.42s`，新行从底部推入时像真终端；
 *  · 自动吸附到底部，但用户手动上滑查看时就不再抢滚动位置。
 */
@Composable
private fun LogTerminal(
    steps: List<ApkInjectPipeline.Step>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 只有本来就贴着底部才自动跟随；用户回看时不被拽走
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= steps.lastIndex - 1
        }
    }
    LaunchedEffect(steps.size) {
        // 用瞬时滚动而不是动画滚动：
        //   · 重打包一次吐几十条，动画会被下一条日志不断打断，越跟越落后；
        //   · 终端本来就该是「内容往上顶」的硬滚动，动画反而显得软。
        if (steps.isNotEmpty() && atBottom) {
            listState.scrollToItem(steps.size)
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING),
    ) {
        Box(Modifier.padding(vertical = 10.dp)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                itemsIndexed(steps) { i, s ->
                    LogLine(step = s, latest = i == steps.lastIndex && steps.size > 1)
                }
                item {
                    // 光标行：日志在跑时，最后一行永远有个闪动的块
                    BlinkingCursor()
                }
            }
        }
    }
}

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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            String.format("+%.2fs", step.atMs / 1000.0),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            ),
            color = MaterialTheme.colorScheme.outline,
        )
        HGap(8.dp)
        Text(
            if (step.warn) "!" else "›",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (step.warn) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
        HGap(6.dp)
        Text(
            step.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (latest) FontWeight.Medium else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (step.detail.isNotBlank()) {
            HGap(6.dp)
            Text(
                step.detail,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                color = if (step.warn) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(
        1f, 0f,
        infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 7.dp, height = 13.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = a))
        )
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
) {
    val r = state.result ?: return
    val ok = r.ok && state.savedPath != null

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = AppConstants.SCREEN_EDGE_PADDING,
            end = AppConstants.SCREEN_EDGE_PADDING,
            top = 4.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ResultBanner(ok = ok, created = r.createdMethod, method = r.resolvedMethod)
        }

        if (!ok) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "原因",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                        )
                        Gap(4.dp)
                        Text(
                            r.error ?: "未知错误",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Gap(10.dp)
                        Text(
                            "已跑过的步骤在下面，最后一条通常是断点。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        if (ok) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "产物",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Gap(6.dp)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                state.savedPath.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Gap(12.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onInstall,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp))
                                HGap(6.dp)
                                Text("安装")
                            }
                            OutlinedButton(
                                onClick = onLocate,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                                HGap(6.dp)
                                Text("MT 定位")
                            }
                            OutlinedButton(
                                onClick = onShare,
                                shape = RoundedCornerShape(12.dp),
                            ) {
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

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("${r.injectedCount}", "注入调用")
                    Metric(
                        "${(r.stats?.outputSize ?: 0L) / 1048576}",
                        "MB 产物",
                    )
                    Metric(
                        r.stats?.addedDex?.removeSuffix(".dex")?.removePrefix("classes") ?: "—",
                        "追加 dex",
                    )
                }
            }
        }

        item {
            Text(
                "执行日志",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            StepsCard(state.steps)
        }
    }
}

/**
 * 结果横幅 + 完成动画。
 *
 * 成功：六边形从外向内合拢、对勾描出；失败：叉号弹出并抖一下。
 * 用 `AnimatedContent` 是为了让「运行中 → 结果」这一帧有过渡，而不是硬切。
 */
@Composable
private fun ResultBanner(ok: Boolean, created: Boolean, method: String?) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val container = if (ok) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            OutcomeGlyph(ok = ok)
            HGap(14.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (ok) "注入成功" else "注入失败",
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                )
                Gap(3.dp)
                Text(
                    when {
                        !ok -> "看下面的日志定位断点，改完可以直接重跑"
                        created -> "已新建 ${method ?: "方法"} 并补上父类调用"
                        else -> "调用已插到 ${method ?: "目标方法"} 的最前面"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** 完成动画：六边形描出 + 对勾/叉号。400ms 内收尾，不耽误读结果。 */
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
            .size(56.dp)
            .graphicsLayer {
                // 失败时轻抖一下，提示「这里断了」
                if (!ok) {
                    val shake = if (p < 1f) (1f - p) * 6f else 0f
                    translationX = kotlin.math.sin(p * 22f) * shake
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(56.dp)) {
            val w = size.minDimension
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = w * 0.44f
            val stroke = w * 0.055f

            // 六边形按进度描出
            drawHexRing(cx, cy, r, color.copy(alpha = 0.18f), stroke)
            drawHexRingProgress(cx, cy, r, color, stroke, p)

            if (ok) {
                // 对勾：从 p=0.55 开始描，避免和六边形同时出现显得挤
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
private fun Metric(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(
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

@Composable
private fun StepsCard(steps: List<ApkInjectPipeline.Step>) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            steps.forEach { s ->
                LogLine(step = s, latest = false)
            }
        }
    }
}

@Composable
private fun ResultActions(ok: Boolean, onRerun: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRerun,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                HGap(6.dp)
                Text(if (ok) "再跑一次" else "重试")
            }
            Button(
                onClick = onClose,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                HGap(6.dp)
                Text("回到控制台")
            }
        }
    }
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
        Text(
            "还没有在跑的任务",
            style = MaterialTheme.typography.titleMedium,
        )
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

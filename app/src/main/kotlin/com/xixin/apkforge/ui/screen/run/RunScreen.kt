package com.xixin.apkforge.ui.screen.run

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.components.rememberCopyText
import com.xixin.apkforge.ui.lifted.components.result.LogPanel
import com.xixin.apkforge.ui.lifted.components.result.LogState
import com.xixin.apkforge.ui.lifted.m3e.AnimatedSwap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // 两条数据源在这里汇合成同一个值：
        //  · 历史记录 → 读归档日志 → RunOutcome（live = false）
        //  · 本次会话 → state.result → RunOutcome（live = true）
        // 页面只认 RunOutcome。于是「跑完看到什么，以后点记录就看到什么」
        // 是结构保证的，而不是靠两张页各自记得同步。
        val viewing = state.viewing
        val outcome = if (viewing != null) {
            rememberArchivedOutcome(vm, viewing)
        } else {
            state.toOutcome()
        }

        // 回看历史时，顶栏副标题必须是**数据**（哪个包、哪次），
        // 不能沿用「结果会一直留在记录里」这种说明句——那是在解释功能，不是在报告状态。
        RunTopBar(
            running = state.running,
            ok = outcome?.ok,
            onBack = onBack,
            subtitle = viewing?.let { rec ->
                rec.title + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(rec.at))
            },
        )

        // 运行中不再换页：进度与日志在**模态对话框**里（见 InjectProgressDialog 头注释），
        // 配置页原地保留。一次注入通常 1~5 秒，为它准备一整页是浪费，而且会让
        // 用户丢失「我刚确认过的配置」这一上下文。
        AnimatedSwap(
            // ⚠️ 三态，不是两态。上一版只有「有结果 / 没结果」两种，
            // 于是**运行中**（result 仍为 null）落进了「没结果」分支，
            // 显示「还没有在跑的任务」——而顶栏此刻写着「正在注入」。
            // 一个页面同时说两件相反的事，用户只能理解为 app 坏了。
            targetState = when {
                state.running -> 1
                outcome != null -> 2
                else -> 0
            },
            label = "runBody",
        ) { phase ->
            when (phase) {
                // 运行中：主体让位给进度对话框，这里不摆任何"空"的暗示。
                // 留空不是偷懒——对话框盖在上面，底层画什么用户都看不到，
                // 画一个占位符反而会在对话框消失的瞬间闪一下。
                1 -> Box(Modifier.fillMaxSize())

                0 -> IdleHint(onBack = onBack)

                else -> outcome?.let { o ->
                    ResultBody(
                        outcome = o,
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

    // 注入进行中：模态对话框，关不掉（见 InjectProgressDialog 头注释）。
    if (state.running) {
        InjectProgressDialog(state = state)
    }
}

// ══════════════════════ 顶栏 ══════════════════════

@Composable
private fun RunTopBar(
    running: Boolean,
    ok: Boolean?,
    onBack: () -> Unit,
    /** 覆盖副标题。null = 用默认文案（运行中/已完成各一句）。 */
    subtitle: String? = null,
) {
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
                    subtitle ?: if (running) "别退出，dex 正在重写" else "结果会一直留在记录里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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

// ══════════════════════ 结果 ══════════════════════

/**
 * 结果页主体。
 *
 * 它只认 [RunOutcome]——刚跑完的那次和历史归档的那次，到这里已经是同一种东西。
 * 「刚跑完」与「回看」的差别只有两处，都由 [RunOutcome.live] 决定：
 *  · 动作按钮（安装 / 定位 / 分享 / 重跑）只在活的那一侧出现；
 *  · 历史侧日志默认展开（点进来就是为了读日志），活的那侧默认收起（结论才是主角）。
 */
@Composable
private fun ResultBody(
    outcome: RunOutcome,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onRerun: () -> Unit,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
) {
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
        // 一张卡说完：结论 / 改了什么 / 产物 / 动作。
        item {
            OutcomeCard(
                outcome = outcome,
                onInstall = onInstall,
                onLocate = onLocate,
                onShare = onShare,
                onCopy = onCopy,
            )
        }

        item { RoundSummary(outcome = outcome) }

        // 日志面板直接搬 jian_box 的 LogPanel：卡头 + 复制反馈 + 内部滚动。
        // 不再有「展开/收起」——折叠态只能露出最后一行，而这一页最常见的用法
        // 恰恰是「滑到底看它卡在哪一步」，多一次点击换不来一屏空间。
        item {
            // 结果页只有一份「已跑完」的文本，所以走 addRaw（不盖墙钟时间戳，
            // 行首的 +1.24s 已经是相对耗时）；清空按钮关掉——日志是这次运行的证据。
            // 日志状态跟着文本走：换了记录就重建，避免上一条的内容残留。
            val logState = remember(outcome.logText) {
                LogState().apply {
                    outcome.logText.trimEnd()
                        .takeIf { it.isNotBlank() }
                        ?.let { addRaw(it) }
                }
            }
            LogPanel(
                logs = logState,
                isError = !outcome.ok,
                showClearButton = false,
            )
        }

        // 历史侧不摆任何按钮：页面的职责是「读」，动作用控制台卡片的 ⋮ 菜单。
        if (outcome.live) {
            item {
                // 「底部固定按钮条」已被收进结果卡，这里只留重跑——
                // 回控制台是结果卡里的主按钮，重跑是次要动作，分开就不会误点。
                OutlinedButton(
                    onClick = onRerun,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    HGap(6.dp)
                    Text(if (outcome.ok) "再跑一次" else "重试")
                }
            }

            item { TextButton(onClick = onClose) { Text("回到控制台") } }
        }
    }
}

/**
 * 结果卡：这一屏唯一的视觉重心。
 *
 * 完成动画放在卡头当图标用（六边形合拢 + 对勾/叉号），**不再画在内容之上**——
 * 上一版那个「遮挡」的观感就是这么来的：动画被当成覆盖层，而它本该是卡片的一部分。
 * 回看历史时不播动画：那条记录不是「刚刚完成」，让一个对勾重新长出来是在撒谎。
 */
@Composable
private fun OutcomeCard(
    outcome: RunOutcome,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val ok = outcome.ok
    val accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val container = if (ok) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }

    // 行是数据驱动的：活的那侧不知道配置名/源包（可能已漂移），历史那侧知道，
    // 就多出两行。用列表拼，而不是五段 if 各写一遍间距。
    val rows = listOfNotNull(
        if (ok) "落点" to outcome.targets.joinToString("\n") else null,
        outcome.configName?.let { "配置" to it },
        outcome.apkName?.let { "源 APK" to it },
        if (ok) "产物" to outcome.savedPath.orEmpty() else null,
        outcome.addedDex?.let { "追加" to it },
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutcomeGlyph(ok = ok, animate = outcome.live)
                HGap(14.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ok) "注入成功" else "注入失败",
                        style = MaterialTheme.typography.headlineSmall,
                        color = accent,
                    )
                    Gap(3.dp)
                    Text(
                        outcome.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                    )
                }
            }

            if (rows.isNotEmpty()) {
                Gap(14.dp)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Gap(12.dp)

                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) Gap(10.dp)
                    val mono = label == "产物" || label == "源 APK" || label == "追加"
                    FmtRow(
                        label = label,
                        value = value,
                        mono = mono,
                        onCopy = if (mono) ({ onCopy(value) }) else null,
                    )
                }
            }

            // 动作只在「刚跑完」出现：产物就在手边，下一步就是装它。
            if (outcome.live && ok) {
                Gap(14.dp)
                // ⚠️ 三颗按钮必须 weight(1f) 平分宽度，且 contentPadding 收窄。
                //
                // 上一版没给 weight、用的是 Button 默认水平内边距（24dp×2）：
                // 三颗按钮的「图标 18 + 间隔 6 + 文字」加上 48dp 内边距后总宽超出卡片，
                // 最后一颗的「分享」被压成竖排两个字（分 / 享）。
                // 这不是字号问题——**是没给布局分配规则，让 Compose 自己挤**。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 主操作（安装）用实心，两个次要动作用描边：层级由填充度表达。
                    Button(
                        onClick = onInstall,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp))
                        HGap(5.dp)
                        Text("安装", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onLocate,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        HGap(5.dp)
                        Text("MT 定位", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onShare,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        HGap(5.dp)
                        Text("分享", maxLines = 1, softWrap = false)
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
private fun RoundSummary(outcome: RunOutcome) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Metric(String.format(Locale.US, "%.1fs", outcome.elapsedMs / 1000.0), "总耗时")
            Metric("${outcome.injectedCount}", "注入调用")
            Metric("${outcome.targetCount}", "落点数")
            // 体积未知（产物被移走 / 老记录没存）就整格不显示，而不是印一个 0 MB。
            outcome.outputSize?.let { Metric("${it / 1048576} MB", "产物") }
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

/** 完成动画：六边形描出 + 对勾/叉号。作为卡头图标使用，不覆盖内容。 */
@Composable
private fun OutcomeGlyph(ok: Boolean, animate: Boolean = true) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(ok, animate) {
        if (animate) {
            grow.snapTo(0f)
            grow.animateTo(1f, tween(if (ok) 460 else 320, easing = LinearEasing))
        } else {
            // 回看历史：直接给终态。这条记录不是「刚刚完成」。
            grow.snapTo(1f)
        }
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

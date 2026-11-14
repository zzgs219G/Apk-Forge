package com.xixin.apkforge.ui.screen.console

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.data.TaskStore
import com.xixin.apkforge.feature.console.ConsoleStatus
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.page.AppTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台首页。
 *
 * 【为什么是「一张锚点卡 + 一列记录」】旧版把配置、选包、类名、方法、日志、结果全塞进一屏，
 * 每块都在争夺注意力，用户的第一眼问题是「我该点哪」。
 * 现在的信息层级只有两级：
 *   1. **锚点卡**（唯一主操作）——没选包时是「选 APK 开始」，选完变成任务摘要 + 「开始注入」；
 *   2. **任务记录**（历史，可复用）——列表里每条都记着包、配置、注入点、产物，
 *      点一下就带着上次的包和配置回到配置页，换套配置再跑。
 * 别的东西（配置管理、签名、关于）都在侧边栏，不在这里抢位置。
 */
@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenRun: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "apk") }
                .onSuccess {
                    vm.selectApk(it)
                    // 选完包立刻进配置页：用户的下一步必然是「选配置 / 确认注入点」。
                    // 解析在后台继续，配置页会先立起来并显示「正在读包」。
                    onOpenSetup()
                }
                .onFailure { vm.showMessage("APK 读取失败：${it.message}") }
        }
    }

    fun pickApk() = apkPicker.launch(FilePicker.pickApkIntent(context))

    /** 锚点卡上那颗按钮的行为随状态走，全 App 只有这一处主入口。 */
    fun primary() {
        when {
            state.apkFile == null -> pickApk()
            state.running -> onOpenRun()
            state.canRun -> { vm.run(); onOpenRun() }
            else -> onOpenSetup()
        }
    }

    // 锚点卡滚出屏幕后，右下角浮出同一动作。
    // 长列表（记录多时）里没有这个，用户滑到底部想开跑还得往回找。
    val anchorGone by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "控制台",
                onMenuClick = onOpenDrawer,
                actions = { StatusDot(state.status) },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = AppConstants.SCREEN_EDGE_PADDING,
                    end = AppConstants.SCREEN_EDGE_PADDING,
                    top = 4.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    AnchorCard(
                        state = state,
                        onPickApk = ::pickApk,
                        onPrimary = ::primary,
                        onOpenRun = onOpenRun,
                    )
                }

                if (state.records.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "任务记录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            HGap(8.dp)
                            Text(
                                "${state.records.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = vm::clearRecords) {
                                Text("清空记录", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    items(state.records, key = { it.id }) { record ->
                        RecordCard(
                            record = record,
                            onReuse = {
                                vm.reuseRecord(record)
                                onOpenSetup()
                            },
                            onInstall = {
                                val f = record.outputFile
                                if (f == null || !f.isFile) {
                                    vm.showMessage("产物已不在 Download 里，可能被移动或删除")
                                } else if (!OutputStore.installPath(context, f)) {
                                    vm.showMessage("没有可用的安装器")
                                }
                            },
                            onLocate = {
                                val f = record.outputFile
                                if (f == null || !f.isFile) {
                                    vm.showMessage("产物已不在 Download 里")
                                } else if (!FilePicker.locateInMt(context, f)) {
                                    vm.showMessage("未安装 MT 管理器，或用分享代替")
                                }
                            },
                            onShare = {
                                val f = record.outputFile
                                if (f == null || !f.isFile) {
                                    vm.showMessage("产物已不在 Download 里")
                                } else if (!OutputStore.sharePath(context, f)) {
                                    vm.showMessage("分享失败")
                                }
                            },
                            onDelete = { vm.deleteRecord(record.id) },
                        )
                    }

                    item {
                        Text(
                            "点卡片 = 带着这个包和配置再来一次；换配置不用重新找文件。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                        )
                    }
                } else {
                    item { HintBlock() }
                }
            }
        }

        // 悬浮锚点：卡片滚走后的替身
        androidx.compose.animation.AnimatedVisibility(
            visible = anchorGone && state.apkFile != null,
            enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = AppConstants.SCREEN_EDGE_PADDING, bottom = 20.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = ::primary,
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        if (state.running) Icons.AutoMirrored.Rounded.ArrowForward
                        else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        when {
                            state.running -> "看进度"
                            state.canRun -> "开始注入"
                            else -> "继续配置"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }

        // 消息条浮在底部：不占布局、不挡主操作，点「知道了」才走
        androidx.compose.animation.AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = vm::consumeMessage) {
                        Text("知道了", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

// ══════════════════════ 锚点卡 ══════════════════════

/**
 * 全屏唯一的主操作。它的形态随状态变化，但**永远只有一颗按钮**：
 *   · 没选包 → 「选 APK 开始」
 *   · 选了包 → 「开始注入」（缺配置时点它进配置页）
 */
@Composable
private fun AnchorCard(
    state: ConsoleUiState,
    onPickApk: () -> Unit,
    onPrimary: () -> Unit,
    onOpenRun: () -> Unit,
) {
    val hasApk = state.apkFile != null
    val cta = when {
        !hasApk -> "选 APK 开始"
        state.running -> "看执行进度"
        state.canRun -> "开始注入"
        else -> "继续配置"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hasApk) "当前任务" else "本地 dex 注入",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Gap(6.dp)
                    Text(
                        text = if (hasApk) state.apkName else "选一个 APK，\n把调用塞进它的生命周期",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = if (hasApk) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Gap(6.dp)
                    Text(
                        text = if (hasApk) {
                            listOfNotNull(
                                state.packageName,
                                "${state.apkSize / 1048576} MB",
                            ).joinToString(" · ")
                        } else {
                            "选包 → 选配置 → 注入。产物进 Download/ApkForge。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                HGap(12.dp)
                ForgeGlyph(
                    modifier = Modifier.size(if (hasApk) 56.dp else 72.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    active = state.status == ConsoleStatus.RUNNING,
                )
            }

            if (hasApk) {
                Gap(16.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TonalPill(
                        state.activeConfig?.name ?: "未套配置",
                        warn = state.activeConfig == null,
                    )
                    TonalPill(state.targetMethod, mono = true)
                    if (state.targetStatus == LifecyclePoints.Status.MISSING) {
                        TonalPill("将创建")
                    }
                }
            }

            Gap(18.dp)
            Button(
                onClick = onPrimary,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(
                    if (hasApk) Icons.Rounded.PlayArrow else Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                HGap(8.dp)
                Text(cta, style = MaterialTheme.typography.titleMedium)
            }

            if (hasApk) {
                // 「上次的结果」是次要动作：不抢主按钮，但必须在卡片里够得着。
                // 否则用户跑完回去，只能靠翻记录列表找回来。
                val result = state.result
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (result != null) {
                        TextButton(onClick = onOpenRun) {
                            Text(
                                (if (result.ok) "上次成功" else "上次失败") + " · 看结果",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    TextButton(onClick = onPickApk) {
                        Text("换一个 APK", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * 品牌图形：一枚六边形 + 穿透它的注入箭头。
 *
 * 与运行页的动画同源（`ui/anim` 的六边形），尺寸小、只描边，不做循环动画——
 * 首页需要一个**静止的标记**当视觉锚点，动起来反而抢走按钮的注意力。
 */
@Composable
fun ForgeGlyph(
    modifier: Modifier = Modifier,
    color: Color,
    active: Boolean = false,
) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 0.55f else 1f,
        label = "glyphAlpha",
    )
    Canvas(modifier) {
        val w = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = w * 0.42f
        val path = Path()
        repeat(6) { i ->
            val a = Math.toRadians((-90 + i * 60).toDouble())
            val x = cx + (r * Math.cos(a)).toFloat()
            val y = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = color.copy(alpha = 0.18f * alpha),
            style = Stroke(width = w * 0.05f),
        )
        // 箭头：从左上插向中心偏右下
        val arrow = Path().apply {
            moveTo(cx - r * 0.62f, cy - r * 0.62f)
            lineTo(cx + r * 0.18f, cy + r * 0.18f)
            moveTo(cx + r * 0.18f, cy - r * 0.12f)
            lineTo(cx + r * 0.18f, cy + r * 0.18f)
            lineTo(cx - r * 0.12f, cy + r * 0.18f)
        }
        drawPath(
            path = arrow,
            color = color.copy(alpha = alpha),
            style = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = color.copy(alpha = 0.9f * alpha),
            radius = w * 0.035f,
            center = Offset(cx + r * 0.55f, cy + r * 0.55f),
        )
        drawCircle(
            color = color.copy(alpha = 0.35f * alpha),
            radius = w * 0.07f,
            center = Offset(cx + r * 0.55f, cy + r * 0.55f),
        )
    }
}

@Composable
private fun StatusDot(status: ConsoleStatus) {
    val color = when (status) {
        ConsoleStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        ConsoleStatus.DONE -> MaterialTheme.colorScheme.primary
        ConsoleStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        HGap(6.dp)
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun TonalPill(text: String, warn: Boolean = false, mono: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (warn) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
        },
    ) {
        Text(
            text,
            style = if (mono) {
                MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = if (warn) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ══════════════════════ 记录卡 ══════════════════════

/**
 * 一条任务记录。
 *
 * 职责：「防止重复选 APK」。所以卡面第一行必须是**包名**，第二行是**用了哪套配置**，
 * 第三行才是时间/大小这类元信息。产物缺失时明说，而不是等用户点了安装才报错。
 */
@Composable
private fun RecordCard(
    record: TaskStore.Record,
    onReuse: () -> Unit,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = onReuse,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 失败也要有痕迹：左侧竖条位置固定，扫列表时能一眼分辨成功与失败
            Box(
                Modifier
                    .size(width = 4.dp, height = 38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (record.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
            )
            HGap(12.dp)

            Column(Modifier.weight(1f)) {
                Text(
                    record.apkName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(3.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.configName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    HGap(6.dp)
                    Text(
                        "· ${record.targetMethod}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Gap(3.dp)
                Text(
                    listOfNotNull(
                        timeAgo(record.at),
                        "${record.apkSizeMb} MB",
                        if (record.outputExists) "产物在" else "产物已删",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.outputExists) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    },
                    maxLines = 1,
                )
            }

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("换配置再注入", Icons.Rounded.Refresh) {
                        menu = false; onReuse()
                    }
                    MenuRow("安装产物", Icons.Rounded.InstallMobile) {
                        menu = false; onInstall()
                    }
                    MenuRow("MT 定位", Icons.Rounded.FolderOpen) {
                        menu = false; onLocate()
                    }
                    MenuRow("分享产物", Icons.Rounded.Share) {
                        menu = false; onShare()
                    }
                    MenuRow("删除记录", Icons.Rounded.Delete, danger = true) {
                        menu = false; onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun HintBlock() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "三步就够",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Gap(10.dp)
            StepLine("1", "选 APK", "自动读出 LAUNCHER Activity 与它已有的方法")
            StepLine("2", "选配置", "配置 = 调用行 + payload dex；没有就现场建一套")
            StepLine("3", "注入", "跑完自动存进 Download/ApkForge，并留一条记录")
        }
    }
}

@Composable
private fun StepLine(no: String, title: String, detail: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    no,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HGap(10.dp)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 相对时间。列表只是给人扫一眼，超过一周就给日期。 */
private fun timeAgo(at: Long): String {
    val diff = System.currentTimeMillis() - at
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        min < 60 * 24 * 7 -> "${min / 60 / 24} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(at))
    }
}

package com.xixin.apkforge.ui.screen.console

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.xixin.apkforge.ui.lifted.components.ApkIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台首页。
 *
 * 【这一版为什么会变成这样】
 *
 * 上一版在同一屏放了两个「开始注入」：贴底的长条按钮栏 + 一个悬浮按钮。
 * 更要命的是贴底栏没有 `align` —— `Box` 里的子节点默认落在**左上角**，
 * 于是它在运行时直接叠到了顶栏上。用户看到的是「按钮飞到顶上去了」，
 * 而他此前说的恰恰是「把上面那个删掉，用右下角代替」。
 *
 * 所以改成一句话能说清的结构：
 *   ① **全屏只有一个主操作** —— 右下角的悬浮按钮。位置固定、拇指够得着、
 *      任何滚动位置都在。它自己会随状态换文案（选包 → 开始注入 → 看进度），
 *      用户永远不用在几个入口之间挑。
 *   ② **卡片只描述、不抢操作**。当前任务卡上没有任何「按钮」形态的主入口，
 *      空状态也一样，只说「右下角选包」。
 *   ③ 右上角是**次要动作的收纳处**：批量、换包、清记录。它们与当前任务无关，
 *      塞进底部会和主操作打架。
 */
@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenRun: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    onOpenBatch: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }

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

    /** 底部栏那颗按钮的行为随状态走，全 App 只有这一处主入口。 */
    fun primary() {
        when {
            state.apkFile == null -> pickApk()
            state.running -> onOpenRun()
            state.canRun -> { vm.run(); onOpenRun() }
            else -> onOpenSetup()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "控制台",
                onMenuClick = onOpenDrawer,
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuRow("新建配置", Icons.Rounded.Add) {
                                menu = false; onOpenConfig()
                            }
                            MenuRow("批量注入", Icons.Rounded.FolderOpen) {
                                menu = false; onOpenBatch()
                            }
                            MenuRow("换一个 APK", Icons.Rounded.Refresh) {
                                menu = false; pickApk()
                            }
                            HorizontalDivider()
                            MenuRow("清空记录", Icons.Rounded.Delete, danger = true) {
                                menu = false; vm.clearRecords()
                            }
                        }
                    }
                },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = AppConstants.SCREEN_EDGE_PADDING,
                    end = AppConstants.SCREEN_EDGE_PADDING,
                    top = 4.dp,
                    // 底部留出悬浮按钮的空间，否则最后一条记录会被它压住
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    AnchorCard(state = state, onOpenRun = onOpenRun)
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
                                Text("清空", style = MaterialTheme.typography.labelMedium)
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
                } else {
                    item { HintBlock() }
                }
            }
        }

        // ── 右下角唯一主操作 ──
        // 常驻，不随滚动/是否选包而消失。上一版的「选了包才出现」等于在
        // 用户最需要入口的时候把入口藏了。这里「选 APK」本身就是它的第一个动作。
        // ⚠️ 必须显式 `align(BottomEnd)`：`Box` 里不给 align 的子节点默认在左上角，
        //    上一版贴底栏漏了这句，运行时叠到顶栏上——就是「按钮飞到顶上」的来源。
        ExtendedFloatingActionButton(
            onClick = ::primary,
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(
                    when {
                        state.running -> Icons.AutoMirrored.Rounded.ArrowForward
                        state.apkFile == null -> Icons.Rounded.FolderOpen
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = null,
                )
            },
            text = { Text(primaryLabel(state), style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = AppConstants.SCREEN_EDGE_PADDING, bottom = 20.dp),
        )

        // 消息条：浮在悬浮按钮之上，不挡主操作
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
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

private fun primaryLabel(state: ConsoleUiState): String = when {
    state.running -> "看进度"
    state.apkFile == null -> "选 APK 开始"
    state.canRun -> "开始注入"
    else -> "继续配置"
}

// ══════════════════════ 锚点卡 ══════════════════════

/**
 * 当前任务卡。
 *
 * 【标签的含义】卡上两枚标签是**这条任务的身份**：
 *   ① 配置（注入什么：调用行 + payload dex）
 *   ② 签名（产物怎么签）——目前是「未签名」占位，
 *      因为它迟早要占这个位置，提前留好位置比将来挤进来更省事。
 * 注入点不是标签：它由系统自动算出来，把它摆在同等位置会让用户以为需要关心。
 */
@Composable
private fun AnchorCard(
    state: ConsoleUiState,
    onOpenRun: () -> Unit,
) {
    val hasApk = state.apkFile != null

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (!hasApk) {
                EmptyAnchor()
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ApkIcon(
                    icon = state.apkIcon,
                    fallback = state.apkLabel.ifBlank { state.apkName },
                    size = 56.dp,
                )
                HGap(14.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "当前任务",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(2.dp)
                    Text(
                        state.apkLabel.ifBlank { state.apkName },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Gap(2.dp)
                    Text(
                        state.packageName ?: "包名未知",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusDot(state.status)
            }

            Gap(10.dp)
            Text(
                // 文件名单独一行：它是「物理上动的是哪个文件」，与手机里那个 APK 对得上号
                listOfNotNull(
                    state.apkName,
                    state.apkVersion?.let { "v$it" },
                    "${state.apkSize / 1048576} MB",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(
                    text = state.activeConfig?.name ?: "未套配置",
                    warn = state.activeConfig == null,
                )
                Tag(text = "未签名", muted = true)
            }

            Gap(10.dp)
            Text(
                buildString {
                    append(state.mode.label)
                    append(" → ")
                    append(if (state.autoTarget) "自动 · " else "")
                    append(state.targetMethod.ifBlank { "待解析" })
                    if (state.plannedTargets > 1) append(" 等 ${state.plannedTargets} 个方法")
                    if (state.targetStatus == LifecyclePoints.Status.MISSING) append("（将创建）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.result != null) {
                Gap(8.dp)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (state.result.ok) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.result.ok) "上次注入成功" else "上次注入失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.result.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenRun) {
                            Text("看结果", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAnchor() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ForgeGlyph(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        HGap(16.dp)
        Column(Modifier.weight(1f)) {
            Text(
                "本地 dex 注入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(4.dp)
            Text(
                "选一个 APK，\n把调用塞进它的生命周期",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Gap(6.dp)
            Text(
                "选包 → 选配置 → 注入。产物进 Download/ApkForge。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Gap(12.dp)
    Text(
        "点右下角按钮选 APK —— 全屏只有这一个主操作，不用找",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
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
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Tag(text: String, warn: Boolean = false, muted: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = when {
            warn -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            muted -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                warn -> MaterialTheme.colorScheme.error
                muted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
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
 * 认包靠三样东西，缺一样就会「认不出是哪个包」：
 *   ① 图标（最快，一眼分辨）
 *   ② 应用名（人话）
 *   ③ 包名（唯一，且和系统设置里对得上）
 * 文件名退到最后一行，只在需要「去文件管理器找它」时才用得上。
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
    val context = LocalContext.current
    // 图标要开整个 zip 读资源，**不能**在组合期同步做：
    // 列表滚动时每帧调一次会把主线程钉住，表现是「滑到记录区就掉帧」。
    // 用 produceState 在 IO 线程解，先给 null（首字母兜底）再替换。
    val icon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = record.apkPath,
    ) {
        value = withContext(Dispatchers.IO) {
            record.apkFile?.takeIf { it.isFile }?.let {
                com.xixin.apkforge.data.ApkMeta.read(context, it)?.icon
            }
        }
    }

    Card(
        onClick = onReuse,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApkIcon(icon = icon, fallback = record.title, size = 40.dp)
            HGap(12.dp)

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    HGap(6.dp)
                    val mark = if (record.ok) "成功" else "失败"
                    Text(
                        mark,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (record.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                Gap(2.dp)
                Text(
                    record.packageName ?: record.apkName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(2.dp)
                Text(
                    listOfNotNull(
                        record.configName,
                        record.targetsSummary.ifBlank { null },
                        timeAgo(record.at),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!record.outputExists) {
                    Gap(2.dp)
                    Text(
                        "产物已删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
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

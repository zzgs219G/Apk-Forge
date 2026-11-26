package com.xixin.apkforge.ui.screen.console

import com.xixin.apkforge.ui.lifted.components.sheet.SheetActionRow
import com.xixin.apkforge.ui.lifted.components.sheet.OptionSheet
import androidx.compose.material.icons.rounded.Security
import java.io.File
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.xixin.apkforge.ui.lifted.components.state.AppEmptyState
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
    /**
     * 选完包、用户挑「去签名校验」时走这里。
     *
     * 带 `File` 参数是必须的：去校验继承**控制台刚选的这个包**，
     * 不再自己弹一次选择器（那样就成了两个上传入口）。
     */
    onOpenUncheck: (File) -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }

    /**
     * 选完包后要做什么。null = 面板没开。
     *
     * 之所以要这个中间态：一个 APK 可以走两条完全不同的路（注入 / 去校验），
     * 而**选包这件事只有一处**。让用户选完包再问「做哪件事」，
     * 比在入口处分两个页面各自上传同一份文件要诚实。
     */
    var pendingApk by remember { mutableStateOf<File?>(null) }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "apk") }
                .onSuccess { pendingApk = it }
                .onFailure { vm.showMessage("APK 读取失败：${it.message}") }
        }
    }

    fun pickApk() = apkPicker.launch(FilePicker.pickApkIntent(context))

    // 记录动作：卡片 ⋮ 菜单里的五项。
    //
    // 【为什么判断放在这里而不是菜单项里】产物可能已经被用户移走/删掉。
    // 把「文件还在不在」的判断留在同一处，菜单只是触发器——
    // 否则五个入口各写一遍存在性检查，漏一个就会出现「点了没反应」。
    fun installRecord(rec: TaskStore.Record) {
        val f = rec.outputFile
        if (f == null || !f.isFile) {
            vm.showMessage("产物已不在 Download 里，可能被移动或删除")
        } else if (!OutputStore.installPath(context, f)) {
            vm.showMessage("没有可用的安装器")
        }
    }

    fun locateRecord(rec: TaskStore.Record) {
        val f = rec.outputFile
        if (f == null || !f.isFile) {
            vm.showMessage("产物已不在 Download 里")
        } else if (!FilePicker.locateInMt(context, f)) {
            vm.showMessage("未安装 MT 管理器，或用分享代替")
        }
    }

    fun shareRecord(rec: TaskStore.Record) {
        val f = rec.outputFile
        if (f == null || !f.isFile) {
            vm.showMessage("产物已不在 Download 里")
        } else if (!OutputStore.sharePath(context, f)) {
            vm.showMessage("分享失败")
        }
    }

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

            // ⚠️ TaskHeader 必须在 LazyColumn **外面**。
            //
            // 上一版把它作为 `item {}` 放进列表：记录一多，往下滑「当前任务」
            // 就被滚出屏幕了。而它回答的是「我现在在操作哪个包、跑到哪一步」——
            // 这个信息在浏览记录时恰恰最需要对照，滚走等于把它变成一次性提示。
            // 记录列表是「历史」，当前任务不是历史的一部分，它不该跟着历史一起滚。
            if (state.apkFile != null) {
                TaskHeader(
                    state = state,
                    onOpenRun = onOpenRun,
                    modifier = Modifier.padding(
                        horizontal = AppConstants.SCREEN_EDGE_PADDING,
                        vertical = 4.dp,
                    ),
                )
            }

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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.records.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
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
                            // 整卡点击 = 看这一跑的日志。动作收在右侧 ⋮ 里。
                            onOpen = {
                                vm.viewRecord(record)
                                onOpenRun()
                            },
                            onReuse = {
                                vm.reuseRecord(record)
                                onOpenSetup()
                            },
                            onInstall = { installRecord(record) },
                            onLocate = { locateRecord(record) },
                            onShare = { shareRecord(record) },
                            onDelete = { vm.deleteRecord(record.id) },
                        )
                    }
                } else if (state.apkFile == null) {
                    // 首次启动。只留一行事实说明，不铺三步指引、不铺卡片：
                    // 空态要说三件事：这是什么、为什么空、现在能做什么。
                    // 只写一行灰字会把「没做完」当成人设——见 AppEmptyState 头注释。
                    item {
                        AppEmptyState(
                            title = "还没有注入任务",
                            description = "选一个 APK，把调用写进它的生命周期。" +
                                "产物会存到 Download/ApkForge。",
                            action = {
                                // 空态里也放一个入口：FAB 在右下角，第一次打开的人不一定会去看那里。
                                // 这不是「第二个主操作」——它与 FAB 触发同一个 primary()，
                                // 是同一动作的显式化，不是并列的第二个功能。
                                FilledTonalButton(
                                    onClick = ::pickApk,
                                    shape = MaterialTheme.shapes.large,
                                    contentPadding = PaddingValues(
                                        horizontal = 22.dp, vertical = 14.dp
                                    ),
                                ) {
                                    Icon(
                                        Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    HGap(8.dp)
                                    Text(
                                        "选择 APK",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            },
                        )
                    }
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
            shape = MaterialTheme.shapes.large,
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
                shape = MaterialTheme.shapes.medium,
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
        // ── 模块选择：选完包之后问「做哪件事」 ──
        //
        // 为什么在这里问而不是在入口处分两个页面：上传动作只有一处，
        // 用户不必记住「我上次是在哪个页面选的包」。两条路本质上是同一个输入的
        // 两种处理，先选文件再选处理方式更符合直觉。
        val picked = pendingApk
        if (picked != null) {
            OptionSheet(
                title = "对 ${picked.name} 做什么",
                onDismiss = { pendingApk = null },
            ) { close ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    SheetActionRow(
                        text = "dex 注入",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = {
                            close()
                            pendingApk = null
                            vm.selectApk(picked)
                            onOpenSetup()
                        },
                    )
                    SheetActionRow(
                        text = "去签名校验",
                        icon = Icons.Rounded.Security,
                        onClick = {
                            close()
                            pendingApk = null
                            onOpenUncheck(picked)
                        },
                    )
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

// ══════════════════════ 当前任务 ══════════════════════

/**
 * 当前任务头。
 *
 * 【为什么不再是卡片】上一版这里是一张 28dp 圆角的大卡：56dp 图标 + 「当前任务」
 * 小标题 + 应用名 + 包名 + 文件名 + 两枚标签 + 一行落在哪个方法 + 结果条。
 * 一屏之内它是最重的一块，但它承载的信息只有「哪个包」和「跑到哪一步了」——
 * 而这些在下一次点击后就会被注入配置页 / 运行页接管。
 *
 * 现在压成**两行文字 + 一个状态点**：图标、应用名、状态在第一行，
 * 包名与配置名合并在第二行。不再有卡、不再有标签、不再单独一行解释注入点
 * （那是注入配置页的事，控制台不必复述）。
 *
 * 唯一保留的附加块是「上次注入结果」——它是对**下一步该点哪**有直接影响的信息
 * （成功 → 去看产物，失败 → 去看日志），所以给一行，仍然不做卡片。
 *
 * ## 为什么在 LazyColumn 外面
 *
 * 见调用处注释：它是「现在」，不是「历史」。记录列表可以滚，当前任务不该跟着滚走。
 */
@Composable
private fun TaskHeader(
    state: ConsoleUiState,
    onOpenRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApkIcon(
                icon = state.apkIcon,
                fallback = state.apkLabel.ifBlank { state.apkName },
                size = 36.dp,
            )
            HGap(11.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    state.apkLabel.ifBlank { state.apkName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(1.dp)
                Text(
                    // 包名 + 配置名：前者认包，后者说明「这次插的是哪一套」。
                    // 文件名与版本号不在这里：需要找文件时记录卡里有。
                    listOfNotNull(
                        state.packageName,
                        state.activeConfig?.name ?: "未套配置",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HGap(8.dp)
            StatusDot(state.status)
        }

        if (state.result != null) {
            val ok = state.result.ok
            val tone = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            Gap(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onOpenRun)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tone)
                )
                HGap(8.dp)
                Text(
                    if (ok) "上次注入成功" else "上次注入失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "看结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    onOpen: () -> Unit,
    onReuse: () -> Unit,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
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
    var menu by remember { mutableStateOf(false) }

    // 整卡点击 = **看这一跑的日志**。
    //
    // 上一版这里是「打开详情弹窗」：弹窗里塞了配置/注入点/时间/两条路径，
    // 加五个图标动作。后来发现整卡的意图根本不需要一个中间层——
    // 用户点记录，要的是「这次跑成什么样」，那就是运行页本身。
    // 弹窗也顺带消失：它的信息并进了日志页头部，动作并进了右侧菜单。
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 卡片自己的动作菜单。与顶栏的 `⋮` 是同一个组件（DropdownMenu），
            // 不是新造的一套：点开的位置贴着触发它的那张卡片，语义正确。
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "记录操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("重跑一次", Icons.Rounded.Refresh) { menu = false; onReuse() }
                    MenuRow("安装产物", Icons.Rounded.InstallMobile) { menu = false; onInstall() }
                    MenuRow("MT 定位", Icons.Rounded.FolderOpen) { menu = false; onLocate() }
                    MenuRow("分享产物", Icons.Rounded.Share) { menu = false; onShare() }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    MenuRow("删除记录", Icons.Rounded.Delete, danger = true) {
                        menu = false
                        onDelete()
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

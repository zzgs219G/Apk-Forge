package com.xixin.apkforge.ui.screen.setup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.core.dex.InjectionMode
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.components.ApkIcon
import com.xixin.apkforge.ui.lifted.components.card.AppSummaryRow
import com.xixin.apkforge.ui.lifted.components.card.CardRowDivider
import com.xixin.apkforge.ui.lifted.components.card.SectionCard
import com.xixin.apkforge.ui.lifted.components.input.ToolSingleLineInput
import com.xixin.apkforge.ui.lifted.components.rememberCopyText
import com.xixin.apkforge.ui.lifted.components.select.ToolSegmentedOptions
import com.xixin.apkforge.ui.lifted.components.sheet.OptionSheet
import com.xixin.apkforge.ui.lifted.components.sheet.SheetActionRow
import com.xixin.apkforge.ui.lifted.components.sheet.SheetOption
import com.xixin.apkforge.ui.lifted.components.sheet.SheetSectionLabel

/**
 * 注入前的确认页。
 *
 * ## 信息架构：按「实体」分卡，不按「组件」分卡
 *
 * 这个页面只有**两件事**，每件事对应一张卡：
 *
 * ```
 * ┌ 目标 ──────────────────────────────────────┐
 * │  [icon] 简盒_2.1.2.apk              [更换]  │   ← 对什么动手
 * │  注入点类名 [com.xixin.box.MainActivity ]  │   ← 直接可改
 * └────────────────────────────────────────────┘
 * ┌ 注入位置 ──────────────────────────────────┐
 * │  注入点方法   onCreate（自动）        ⌄    │   ← 写到哪个方法
 * │  触发方式     单次注入                ⌄    │   ← 触发几次
 * └────────────────────────────────────────────┘
 * ┌ 注入内容 ──────────────────────────────────┐
 * │  配置         我的弹窗                ⌄    │   ← 写什么
 * │  ┌ invoke-static {p0}, Lcom/…  ─────────┐ │
 * │  │ 3 行 · PayloadDialog.dex · 12 KB     │ │
 * │  └───────────────────────────────────────┘ │
 * └────────────────────────────────────────────┘
 * ```
 *
 * 三条划分依据，都是「同一件事必须在一张卡里」：
 *
 * **① 类名必须在「目标」卡，且是输入框，不是只读文本。**
 *    类名虽然由清单自动识别填好，但它**经常需要改**（要注入到别的 Activity、
 *    或者清单里有多个 LAUNCHER）。做成只读文本等于把"改它"这件事藏进别处——
 *    上一版就藏在「注入点方法」弹层的**最底部**，得滚到底才找得到，
 *    那是全页最不合理的交互。
 *
 * **② 方法 + 触发必须在同一张卡。**
 *    它们回答的是同一个问题："调用落到宿主里哪个方法的第几行、执行几次"。
 *    `EVERY_ENTER` 会改变落点数量（onStart + onResume），
 *    `MULTI_POINT` 会铺 6 个点——触发方式**直接影响**方法是哪几个。
 *    把它们分到两张卡，等于把一个决策的两个参数拆开。
 *
 * **③ 配置 + 调用行 + dex 必须在同一张卡。**
 *    这三者在 `ConfigStore.Config` 里就是**同一条记录**：调用行和 dex 是配置的字段。
 *    上一版把「调用与载荷」独立成卡，结果是那张卡在没有配置时只有一行
 *    「还没有调用行」，空荡得像个占位符。合回配置卡后，
 *    选中配置 → 调用行与 dex 自动填充并作为**只读确认**显示在下方，一张卡说完整件事。
 *
 * ## 不写介绍性文案
 *
 * 这个界面的读者是开发者，他需要的是数据不是说明书。所以：
 *  · 不写「注什么、注到哪、注几次」这种卡头自述
 *  · 不写「不存在，将新建」——既然是自动挑的，用户不需要知道它怎么挑的
 *  · 不写「调用行在配置里编辑，这里只做确认」——只读区就在配置下方，位置本身就说明了
 *  · 状态只在**异常**时出现（类未找到 / 语法错误），正常态一个字都不多写
 */
@Composable
fun SetupScreen(
    vm: ConsoleViewModel,
    onBack: () -> Unit = {},
    onRun: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "apk") }
                .onSuccess { vm.selectApk(it) }
                .onFailure { vm.showMessage("APK 读取失败：${it.message}") }
        }
    }
    fun pickApk() = apkPicker.launch(FilePicker.pickApkIntent(context))

    val dexPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "dex") }
                .onSuccess { vm.setPayloadDex(it) }
                .onFailure { vm.showMessage("dex 读取失败：${it.message}") }
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshConfigs()
        onPauseOrDispose { }
    }

    var sheet by remember { mutableStateOf<SetupSheet?>(null) }

    Column(Modifier.fillMaxSize()) {
        SetupTopBar(onBack = onBack, onMenu = onOpenConfig)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 4.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TargetCard(
                    state = state,
                    onReplace = ::pickApk,
                    onClassChange = vm::setTargetClass,
                )
            }

            item {
                PositionCard(
                    state = state,
                    onOpenMethod = { sheet = SetupSheet.Method },
                    onSelectMode = vm::setMode,
                )
            }

            item {
                ContentCard(
                    state = state,
                    onOpenConfig = { sheet = SetupSheet.Config },
                    onOpenCalls = { sheet = SetupSheet.Calls },
                )
            }

            if (state.blockers.isNotEmpty()) {
                item { BlockerNotice(state.blockers) }
            }
        }

        BottomRunBar(enabled = state.canRun, onRun = { vm.run(); onRun() })
    }

    // ── 弹层 ──────────────────────────────────────────────────────────────
    when (sheet) {
        SetupSheet.Method -> OptionSheet(
            title = "注入点方法",
            onDismiss = { sheet = null },
        ) { close ->
            SheetOption(
                title = "自动",
                selected = state.autoTarget,
                onClick = { close(); vm.useAutoTarget() },
            )
            state.resolution?.candidates?.forEach { c ->
                SheetOption(
                    title = c.point.name,
                    subtitle = c.point.signature,
                    selected = !state.autoTarget && c.point.name == state.targetMethod,
                    enabled = c.status != LifecyclePoints.Status.NATIVE,
                    onClick = { close(); vm.selectPoint(c.point) },
                )
            }
        }

        SetupSheet.Config -> OptionSheet(
            title = "注入内容",
            onDismiss = { sheet = null },
        ) { close ->
            if (state.configs.isEmpty()) {
                SheetActionRow("新建一套配置", icon = Icons.Rounded.Add, onClick = {
                    close(); onOpenConfig()
                })
                SheetActionRow("用内置示例", icon = Icons.Rounded.PlayArrow, onClick = {
                    close(); vm.loadSample()
                })
            } else {
                state.configs.forEach { cfg ->
                    SheetOption(
                        title = cfg.name,
                        subtitle = "${cfg.callCount} 条" +
                            if (cfg.hasDex) " · dex ${cfg.dexSizeKb}KB" else " · 无 dex",
                        selected = cfg.id == state.activeConfig?.id,
                        onClick = { close(); vm.applyConfig(cfg) },
                    )
                }
                SheetSectionLabel("其它")
                SheetActionRow("新建配置…", icon = Icons.Rounded.Add, onClick = {
                    close(); onOpenConfig()
                })
                if (state.activeConfig != null) {
                    SheetActionRow("取消套用", icon = Icons.Rounded.Delete, danger = true, onClick = {
                        close(); vm.clearConfig()
                    })
                }
            }
        }

        SetupSheet.Calls -> OptionSheet(
            title = "调用行",
            onDismiss = { sheet = null },
        ) { close ->
            CallListFull(calls = state.callsText, errors = state.callErrors)
            Gap(8.dp)
            PayloadSource(
                state = state,
                onPick = {
                    close()
                    dexPicker.launch(FilePicker.pickDexIntent(context))
                },
                onClear = { close(); vm.setPayloadDex(null) },
                onOpenConfig = { close(); onOpenConfig() },
                onSample = { close(); vm.loadSample() },
            )
        }

        null -> Unit
    }
}

private enum class SetupSheet { Method, Config, Calls }

// ══════════════════════ 顶栏 / 底栏 ══════════════════════

@Composable
private fun SetupTopBar(onBack: () -> Unit, onMenu: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回控制台")
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text("注入配置", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = onMenu) {
                Text("配置库", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BottomRunBar(enabled: Boolean, onRun: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 12.dp)
        ) {
            Button(
                onClick = onRun,
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppConstants.STANDARD_BUTTON_HEIGHT),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                HGap(8.dp)
                Text("开始注入", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ══════════════════════ 卡 1：目标 ══════════════════════

/**
 * 目标：对哪个 APK、哪个类动手。
 *
 * 类名是**输入框**而不是只读文本——它虽然自动填好，但经常要改
 * （注入到别的 Activity、清单里有多个 LAUNCHER）。
 * 做成只读会把「改它」藏进别处，那是全页最容易迷路的操作。
 */
@Composable
private fun TargetCard(
    state: ConsoleUiState,
    onReplace: () -> Unit,
    onClassChange: (String) -> Unit,
) {
    SectionCard(title = "目标") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApkIcon(
                icon = state.apkIcon,
                fallback = state.apkLabel.ifBlank { state.apkName },
                size = 40.dp,
            )
            HGap(11.dp)
            Column(Modifier.weight(1f)) {
                // 版本号跟着应用名放：它属于「这是哪个版本」的身份信息，
                // 塞进第二行只会把包名和大小挤到出现省略号。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.apkLabel.ifBlank { state.apkName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    state.apkVersion?.let {
                        HGap(6.dp)
                        Text(
                            "v$it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Gap(1.dp)
                // 第二行只放「包名 · 大小」。包名可以截断（尾部是唯一标识，
                // 但它是整串里最长的部分），大小必须完整可见——所以大小在前，包名在后。
                Text(
                    "${state.apkSize / 1048576} MB" +
                        (state.packageName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onReplace) {
                Text("更换", style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
        ) {
            // 复用现成的 ToolSingleLineInput（自带粘贴 / 清空 / 错误态），不另写一套。
            // 上一版手写 InlineTextField 是重复实现——公共组件已经覆盖这个需求，
            // 而且它还漏掉了粘贴与清空这两个实际会用到的动作。
            ToolSingleLineInput(
                value = state.targetClass,
                onValueChange = onClassChange,
                label = "注入点类名",
                placeholder = "com.example.app.MainActivity",
                isError = state.targetClass.isNotBlank() && !state.classFound,
                autoGrow = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            // 只在异常时补一行说明；正常态（类已找到）一个字都不多写。
            val hint = when {
                state.parsing -> "读取中…"
                state.targetClass.isNotBlank() && !state.classFound -> "包里没有这个类"
                else -> null
            }
            if (hint != null) {
                Gap(4.dp)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.classFound) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ══════════════════════ 卡 2：注入位置 ══════════════════════

/**
 * 注入位置 = 方法 + 触发方式。
 *
 * 二者必须在同一张卡：它们共同决定「调用落到哪个方法的第几行、执行几次」。
 * `EVERY_ENTER` 会改变落点数量（onStart + onResume）、`MULTI_POINT` 会铺 6 个点——
 * 触发方式**直接影响**方法是哪几个，拆开就是把一个决策的两个参数分开摆。
 *
 * 正常态不显示任何状态文字：方法就是方法名，触发就是模式名。
 * 「已存在 / 将新建」这类信息对用户没有行动价值——注入器自己会处理。
 */
@Composable
private fun PositionCard(
    state: ConsoleUiState,
    onOpenMethod: () -> Unit,
    onSelectMode: (InjectionMode) -> Unit,
) {
    val method = state.targetMethod.ifBlank { "待解析" }
    SectionCard(title = "注入位置") {
        AppSummaryRow(
            title = "注入点方法",
            value = if (state.autoTarget) "$method（自动）" else method,
            mono = true,
            onClick = onOpenMethod,
        )

        // ── 触发方式：就地分段，不弹层 ──
        //
        // 上一版这一行点了会弹 bottom sheet，里面是 4 个带长说明的单选项。
        // 那是**用容器代替判断**：4 个短标签（单次注入 / 每次进入 / 多点全覆盖 /
        // 替换方法体）一屏就装得下，弹出去反而多两步、还遮住上下文。
        //
        // M3 里「2~5 个互斥短选项」的标准形态是 SegmentedButton（分段按钮）：
        // 全部选项可见、当前值高亮、一次点击即切换。这也正是 lifted 里
        // `ToolSegmentedOptions` 的用途——它此前业务层零引用。
        //
        // 代价是拿不到 `hint` 那句话（"onStart 与 onResume 各插一份…"）。
        // 这是有意的取舍：**说明不该常驻**。真正需要解释的是"这次会写几个方法"，
        // 那件事已经由下面这行数字回答了——它比一句话更准确，且是数据不是文案。
        CardRowDivider()
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
        ) {
            Text(
                "触发方式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(8.dp)
            ToolSegmentedOptions(
                options = InjectionMode.entries.map { it.short },
                selectedIndex = InjectionMode.entries.indexOf(state.mode),
                onSelect = { onSelectMode(InjectionMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.plannedTargets > 0) {
                Gap(6.dp)
                Text(
                    // 数字，不是文案：它直接回答"这次会改几个方法"。
                    if (state.plannedTargets > 1) {
                        "写入 ${state.plannedTargets} 个方法"
                    } else {
                        "写入 1 个方法"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ══════════════════════ 卡 3：注入内容 ══════════════════════

/**
 * 注入内容 = 配置（调用行 + dex）。
 *
 * 这三者在 `ConfigStore.Config` 里就是**同一条记录**，所以同卡。
 * 选中配置后，调用行与 dex 自动作为只读确认显示在下方——
 * 不需要再点开任何东西才知道「这次到底会插什么」。
 */
@Composable
private fun ContentCard(
    state: ConsoleUiState,
    onOpenConfig: () -> Unit,
    onOpenCalls: () -> Unit,
) {
    val active = state.activeConfig
    val hasCalls = state.callCount > 0 || state.callsText.isNotBlank()
    val dex = state.payloadDex

    SectionCard(title = "注入内容") {
        AppSummaryRow(
            title = "配置",
            value = active?.name ?: if (state.configs.isEmpty()) "还没有配置" else "未套用",
            subtitle = when {
                state.callErrors.isNotEmpty() -> state.callErrors.first()
                active == null && state.configs.isNotEmpty() -> "${state.configs.size} 套可选"
                null != active && !active.hasDex -> "这套配置没有 dex"
                else -> null
            },
            warn = state.callErrors.isNotEmpty(),
            onClick = onOpenConfig,
        )

        // 调用行 + dex：选中配置后自动填充的只读确认区。
        // 它不是独立一行而是一块代码预览，因为调用行天然是多行等宽文本。
        if (hasCalls || dex != null) {
            CardRowDivider()
            CallPreview(
                calls = state.callsText,
                dexName = dex?.name,
                dexSizeKb = dex?.let { it.length() / 1024 },
                fromConfig = active?.name,
                errors = state.callErrors,
                onExpand = onOpenCalls,
            )
        }
    }
}

/**
 * 调用行预览。
 *
 * 默认显示前几行（等宽、不截断、可复制），点右上角展开看全文。
 * 这里有内容时它是「确认区」，没有内容时整块不渲染——
 * 上一版把「还没有调用行」渲染成一整张卡，那是把「空」当成信息。
 */
@Composable
private fun CallPreview(
    calls: String,
    dexName: String?,
    dexSizeKb: Long?,
    fromConfig: String?,
    errors: List<String>,
    onExpand: () -> Unit,
) {
    val copyText = rememberCopyText()
    val lines = remember(calls) {
        calls.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        if (lines.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${lines.size} 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { copyText(calls) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    lines.take(PREVIEW_LINES).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = if (line.startsWith("#")) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (lines.size > PREVIEW_LINES) {
                        TextButton(
                            onClick = onExpand,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "展开全部 ${lines.size} 行",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }

        if (dexName != null || fromConfig != null) {
            Gap(8.dp)
            PayloadLine(
                dexName = dexName,
                dexSizeKb = dexSizeKb,
                fromConfig = fromConfig,
                onClick = onExpand,
            )
        }

        errors.forEach { err ->
            Gap(4.dp)
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 载荷 dex 一行。
 *
 * 有配置 → 「PayloadDialog.dex · 12 KB」+ 来源说明；无配置 → 给选择入口。
 * **单一来源**：有配置时 dex 由配置提供，本页不再给第二个选择入口
 * （上一版配置里有 dex、本页又能再选一个，同一件事两条通道）。
 */
@Composable
private fun PayloadLine(
    dexName: String?,
    dexSizeKb: Long?,
    fromConfig: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (dexName != null) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = if (dexName != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        HGap(8.dp)
        Column(Modifier.weight(1f)) {
            Text(
                dexName ?: "不追加载荷 dex",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (dexName != null) FontFamily.Monospace else FontFamily.Default
                ),
                maxLines = 2,
            )
            Text(
                when {
                    dexName == null && fromConfig != null -> "这套配置没有 dex"
                    dexName == null -> "只插调用，不打包载荷"
                    dexSizeKb != null -> "${dexSizeKb} KB"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) {
            Text("详情", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ══════════════════════ 弹层内容 ══════════════════════

/**
 * 调用全文。只在弹层里出现——它是「想看全部」时才打开的视图，
 * 页面上常驻的是 [CallPreview] 的摘要。
 *
 * 【为什么不用省略号】这是一份要给人看、还要复制给 AI 看的清单。
 * `maxLines = 1 + Ellipsis` 把 `invoke-virtual {p0}, Lx/y;->z(...)` 的后半截
 * 直接吃掉，剩下的内容毫无用处。单行过长时**换行**而不是截断。
 */
@Composable
private fun CallListFull(calls: String, errors: List<String>) {
    val copyText = rememberCopyText()
    val lines = remember(calls) {
        calls.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    if (lines.isEmpty()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                "还没有调用行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${lines.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copyText(calls) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制调用行",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (line.startsWith("#")) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    errors.forEach { err ->
        Gap(6.dp)
        Text(
            err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun PayloadSource(
    state: ConsoleUiState,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onOpenConfig: () -> Unit,
    onSample: () -> Unit,
) {
    val cfg = state.activeConfig
    val dex = state.payloadDex
    val name = dex?.name

    if (name == null && cfg == null) {
        SheetActionRow("选择 payload dex", icon = Icons.Rounded.Add, onClick = onPick)
        SheetActionRow("用内置示例", icon = Icons.Rounded.PlayArrow, onClick = onSample)
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (name != null) Icons.Rounded.Check else Icons.Rounded.Delete,
            contentDescription = null,
            tint = if (name != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        HGap(8.dp)
        Column(Modifier.weight(1f)) {
            Text(
                name ?: "不追加 dex",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (name != null) FontFamily.Monospace else FontFamily.Default
                ),
                maxLines = 2,
            )
            if (name != null) {
                Text(
                    "${dex.length() / 1024} KB" +
                        if (cfg != null) " · 来自「${cfg.name}」" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (cfg == null) {
            if (name != null) {
                TextButton(onClick = onClear) {
                    Text("移除", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                TextButton(onClick = onPick) {
                    Text("选择", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            TextButton(onClick = onOpenConfig) {
                Text("去改", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ══════════════════════ 公共片段 ══════════════════════

/**
 * 阻断原因。全页唯一一处——上一版列表末尾与底栏各显示一次，
 * 同一句话隔着按钮出现两遍。
 */
@Composable
private fun BlockerNotice(blockers: List<String>) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        blockers.forEach { b ->
            Text(
                "· $b",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PREVIEW_LINES = 3

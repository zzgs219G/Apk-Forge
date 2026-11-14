package com.xixin.apkforge.ui.screen.setup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.components.input.ToolSingleLineInput

/**
 * 注入前的确认页（用页面而不是弹窗）。
 *
 * 【为什么不是弹窗】这里要放下「配置下拉 + 类名 + 方法下拉 + 调用预览 + 载荷状态」五段内容，
 * 在手机上做成弹窗要么被键盘顶掉，要么内部再套一层滚动，反而更难受。
 * 页面还有一个隐性好处：**返回键语义天然正确**——配置选错了按返回就是回控制台，
 * 不需要「点空白关闭 / 点 X 关闭」这种二次决策。
 *
 * 【信息层级】从上到下就是用户的决策顺序：
 *   套哪套配置 → 注到哪个类的哪个方法 → 这套配置到底带什么 → 开跑。
 * 每一段只在「需要看一眼」时才展开细节，默认收起。
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

    // 每次回到本页都刷新配置下拉：用户可能刚在配置页新建/改名/删除。
    // 用 LifecycleResumeEffect 而不是 LaunchedEffect(Unit) —— 后者在
    // `restoreState = true` 的导航里可能不重新执行，回来看到的还是旧列表。
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshConfigs()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        SetupTopBar(
            onBack = onBack,
            onMenu = onOpenConfig,
            canRun = state.canRun,
            onRun = { vm.run(); onRun() },
        )

        if (state.parsing) {
            // 选完包就直接进这一页，解析还在后台跑。与其让用户对着空卡片发呆，
            // 不如明说在读什么——他知道还要等，就不会乱点。
            ParsingNotice(state.apkName)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 6.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TargetHeader(state)
            }

            item {
                SetupCard("配置", trailing = if (state.configs.isEmpty()) "无" else "${state.configs.size} 套") {
                    ConfigDropdown(
                        configs = state.configs,
                        active = state.activeConfig,
                        onSelect = vm::applyConfig,
                        onClear = vm::clearConfig,
                        onCreate = onOpenConfig,
                    )
                    if (state.activeConfig == null) {
                        Gap(8.dp)
                        TextButton(onClick = vm::loadSample) {
                            Text("先填一套示例跑通流程", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item {
                SetupCard("注入位置") {
                    ClassField(state = state, onChange = vm::setTargetClass)
                    Gap(12.dp)
                    PointDropdown(
                        state = state,
                        onSelect = vm::selectPoint,
                    )
                    state.resolution?.let { r ->
                        if (r.reason != null) {
                            Gap(10.dp)
                            Notice(
                                text = r.reason,
                                warn = true,
                            )
                        } else {
                            Gap(8.dp)
                            Text(
                                state.targetPoint?.hint ?: "自定义方法：不存在时会自动创建",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SetupCard("这次会注入什么", trailing = "${state.callCount} 条") {
                    if (state.callsText.isBlank()) {
                        Text(
                            "还没有调用行。选一套配置，或去配置页粘一段 invoke。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                state.callsText.lineSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .take(4)
                                    .forEach { line ->
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = if (line.startsWith("#")) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                if (state.callCount > 4) {
                                    Text(
                                        "… 另有 ${state.callCount - 4} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    state.callErrors.forEach { err ->
                        Gap(6.dp)
                        Text(
                            err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Gap(10.dp)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Gap(10.dp)
                    PayloadRow(
                        name = state.payloadDex?.name,
                        sizeKb = state.payloadDex?.length()?.div(1024),
                        onPick = { dexPicker.launch(FilePicker.pickDexIntent(context)) },
                        onClear = { vm.setPayloadDex(null) },
                    )
                }
            }

            if (state.blockers.isNotEmpty()) {
                item {
                    Notice(
                        text = "还差：" + state.blockers.joinToString("、"),
                        warn = false,
                    )
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }

        BottomRunBar(
            enabled = state.canRun,
            blockers = state.blockers,
            onRun = { vm.run(); onRun() },
        )
    }
}

// ══════════════════════ 顶栏 / 底栏 ══════════════════════

@Composable
private fun SetupTopBar(
    onBack: () -> Unit,
    onMenu: () -> Unit,
    canRun: Boolean,
    onRun: () -> Unit,
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回控制台")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "注入配置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "确认包、配置与注入点",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMenu) {
                Text("管理配置", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ParsingNotice(name: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            HGap(10.dp)
            Text(
                "正在读 $name 的清单与 dex…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TargetHeader(state: ConsoleUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.apkName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    state.packageName,
                    "${state.apkSize / 1048576} MB",
                    "${state.launcherCandidates.size} 个 LAUNCHER",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BottomRunBar(enabled: Boolean, blockers: List<String>, onRun: () -> Unit) {
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
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                HGap(8.dp)
                Text("开始注入", style = MaterialTheme.typography.titleMedium)
            }
            if (!enabled) {
                Gap(6.dp)
                Text(
                    "还差：" + blockers.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ══════════════════════ 组件 ══════════════════════

@Composable
private fun SetupCard(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle(title, trailing = trailing)
            Gap(10.dp)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDropdown(
    configs: List<ConfigStore.Config>,
    active: ConfigStore.Config?,
    onSelect: (ConfigStore.Config) -> Unit,
    onClear: () -> Unit,
    onCreate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    if (configs.isEmpty()) {
        // 空状态不留一个点不开的下拉框，直接给动作
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "还没有配置。一套配置 = 调用行 + payload dex。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCreate) { Text("新建") }
        }
        return
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = active?.name ?: "选一套配置",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            supportingText = {
                Text(
                    active?.let {
                        "${it.callCount} 条 · ${it.targetMethod} · " +
                            if (it.hasDex) "dex ${it.dexSizeKb}KB" else "无 payload dex"
                    } ?: "${configs.size} 套可选",
                )
            },
            trailingIcon = {
                val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "cfgArrow")
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rot),
                )
            },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            configs.forEach { cfg ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(cfg.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${cfg.callCount} 条 · ${cfg.targetMethod}" +
                                    if (cfg.hasDex) " · 带 dex" else " · 无 dex",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (cfg.id == active?.id) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        onSelect(cfg)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("取消套用", style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onClear()
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun ClassField(state: ConsoleUiState, onChange: (String) -> Unit) {
    // 刻意不存本地副本：`remember(state.targetClass) { mutableStateOf(...) }` 这种写法
    // 会在每次外部更新时重建状态，光标跳到末尾、中文输入法组词被打断。
    // ViewModel 里的更新是同步的（异步的只有 dex 探测），受控模式足够。
    ToolSingleLineInput(
        value = state.targetClass,
        onValueChange = onChange,
        label = "注入点类名",
        placeholder = "com.example.app.MainActivity",
        isError = state.targetClass.isNotBlank() && !state.classFound,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
    )
    Gap(6.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                state.targetClass.isBlank() -> "留空会自动取清单里的 LAUNCHER"
                state.classFound -> "已找到 · 父类 " + state.classSuperType.simpleName()
                else -> "包里没有这个类，检查拼写（要带包名）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.targetClass.isNotBlank() && !state.classFound) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
    }

    // 多个 LAUNCHER 时给快捷入口，省得手敲包名
    if (state.launcherCandidates.size > 1) {
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.launcherCandidates.take(3).forEach { c ->
                Surface(
                    onClick = { onChange(c) },
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        c.substringAfterLast('.'),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 注入点下拉。
 *
 * 三项状态必须一眼可见，否则用户不知道自己到底在「覆写」还是「凭空造方法」：
 *   · 已有 —— 绿色对勾
 *   · 将创建 —— 中性提示（会自动补 super 调用）
 *   · native 不可插 —— 警告色 + 禁点
 * 推荐项（native 降级后的替代）带高亮底，因为那是系统替用户做的决定，得说清楚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointDropdown(state: ConsoleUiState, onSelect: (LifecyclePoints.Point) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.targetPoint

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current?.name ?: state.targetMethod.ifBlank { "选方法" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("注入到") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            supportingText = {
                Text(state.targetSignature, fontFamily = FontFamily.Monospace)
            },
            trailingIcon = {
                val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "ptArrow")
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rot),
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.resolution?.candidates?.forEach { c ->
                val usable = c.status != LifecyclePoints.Status.NATIVE
                DropdownMenuItem(
                    enabled = usable,
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.point.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = if (usable) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                                HGap(6.dp)
                                StatusTag(c.status)
                                if (c.recommended) {
                                    HGap(4.dp)
                                    StatusTag(null, "推荐")
                                }
                            }
                            Text(
                                c.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(c.point)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusTag(status: LifecyclePoints.Status?, text: String? = null) {
    val label = text ?: status?.label ?: ""
    val (bg, fg) = when (status) {
        LifecyclePoints.Status.EXISTS ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.primary
        LifecyclePoints.Status.MISSING ->
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.secondary
        LifecyclePoints.Status.NATIVE ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.14f) to MaterialTheme.colorScheme.error
        null ->
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.tertiary
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun PayloadRow(
    name: String?,
    sizeKb: Long?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (name != null) Icons.Rounded.Check else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (name != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        HGap(8.dp)
        Column(Modifier.weight(1f)) {
            Text(
                name ?: "不追加 payload dex",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (name != null) FontFamily.Monospace else FontFamily.Default
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (name != null) "${sizeKb ?: 0} KB · 作为 classesN.dex 追加"
                else "只插调用、不打包载荷（调用会指向不存在的类）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (name != null) {
            TextButton(onClick = onClear) { Text("换", style = MaterialTheme.typography.labelMedium) }
        } else {
            TextButton(onClick = onPick) { Text("选择", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Notice(text: String, warn: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (warn) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (warn) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            HGap(8.dp)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (warn) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String?.simpleName(): String =
    this?.trimStart('L')?.trimEnd(';')?.substringAfterLast('/') ?: "?"

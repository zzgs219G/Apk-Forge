package com.xixin.apkforge.ui.screen.inject

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.feature.inject.InjectUiState
import com.xixin.apkforge.feature.inject.InjectViewModel
import com.xixin.apkforge.feature.inject.Phase
import com.xixin.apkforge.ui.anim.InjectAnimation
import com.xixin.apkforge.ui.lifted.page.AppTopBar
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.AppConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import com.xixin.apkforge.ui.lifted.components.input.ToolSingleLineInput

/**
 * 注入页。
 *
 * 组件一律用 **M3 标准件**（Card / OutlinedTextField / FilterChip / DropdownMenu /
 * Button），颜色与字号走 `MaterialTheme` 角色，不自造视觉。
 * 参考 jian_box `page/toolbox/ToolboxPage.kt` 的卡片栈式布局：
 * 圆角 20~24dp、`CardDefaults.cardColors(containerColor = surfaceContainerLow)`、
 * 零阴影。
 */
@Composable
fun InjectScreen(
    vm: InjectViewModel = viewModel(),
    onOpenDrawer: () -> Unit = {},
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

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "注入",
            onMenuClick = onOpenDrawer,
            actions = { PhaseBadge(state.phase) },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 配置
            item {
                ConfigCard(
                    configs = state.configs,
                    activeId = state.activeConfigId,
                    onApply = vm::applyConfig,
                    onClear = vm::clearActiveConfig,
                    onOpenConfig = onOpenConfig,
                )
            }

            // APK
            item {
                SectionCard(title = "目标 APK") {
                    FilePickRow(
                        title = state.apkFile?.name ?: "选择 APK",
                        detail = state.apkFile?.let {
                            if (state.classFound) {
                                "${it.length() / 1024 / 1024} MB · LAUNCHER ${state.launcherCandidates.size} 个"
                            } else {
                                "${it.length() / 1024 / 1024} MB"
                            }
                        },
                        selected = state.apkFile != null,
                        busy = state.parsingApk,
                        onClick = { apkPicker.launch(FilePicker.pickApkIntent(context)) },
                        onClear = state.apkFile?.let { { vm.clearApk() } },
                    )
                }
            }

            // 注入点
            item {
                SectionCard(title = "注入点") {
                    ToolSingleLineInput(
                        value = state.targetClass,
                        onValueChange = vm::setTargetClass,
                        label = "类名",
                        placeholder = "com.example.app.MainActivity",
                        isError = state.targetClass.isNotBlank() && !state.classFound,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    Gap(6.dp)
                    Text(
                        when {
                            state.targetClass.isBlank() -> "留空则自动取 Manifest 的 LAUNCHER"
                            state.classFound ->
                                "已找到 · 父类 " +
                                    (state.classSuperType?.let(::simpleName) ?: "?")
                            else -> "包里没有这个类，请检查拼写"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.targetClass.isNotBlank() && !state.classFound) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    if (state.launcherCandidates.size > 1) {
                        Gap(8.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.launcherCandidates.take(3).forEach { c ->
                                AssistChip(
                                    onClick = { vm.setTargetClass(c) },
                                    label = { Text(c.substringAfterLast('.')) },
                                )
                            }
                        }
                    }

                    Gap(12.dp)
                    MethodSelector(
                        points = state.lifecyclePoints,
                        selected = state.targetMethod,
                        existsCheck = state::pointExists,
                        onSelect = vm::selectLifecyclePoint,
                        onCustom = { vm.setTargetMethod("") },
                    )

                    if (state.lifecyclePoints.none { it.name == state.targetMethod }) {
                        Gap(8.dp)
                        ToolSingleLineInput(
                            value = state.targetMethod,
                            onValueChange = vm::setTargetMethod,
                            label = "自定义方法名",
                            placeholder = "方法名",
                        )
                    }
                }
            }

            // 结果
            state.result?.let { r ->
                item {
                    ResultCard(
                        result = r,
                        saved = state.savedOutput,
                        onInstall = {
                            state.savedOutput?.let { OutputStore.installApk(context, it) }
                        },
                        onLocate = {
                            state.savedOutput?.let { s ->
                                if (!FilePicker.locateInMt(context, s.file)) {
                                    vm.showMessage("未安装 MT 管理器")
                                }
                            }
                        },
                        onShare = {
                            state.savedOutput?.let { OutputStore.shareFile(context, it) }
                        },
                        onReset = vm::reset,
                    )
                }
            }

            // 日志
            if (state.steps.isNotEmpty()) {
                item { LogCard(state.steps) }
            }
        }

        // 底部动作区，避开导航栏
        BottomBar(state = state, onRun = vm::run)
    }
}

// ══════════════════════════ 组件 ══════════════════════════

@Composable
private fun PhaseBadge(phase: Phase) {
    val (text, color) = when (phase) {
        Phase.DONE -> "完成" to MaterialTheme.colorScheme.primary
        Phase.FAILED -> "失败" to MaterialTheme.colorScheme.error
        Phase.IDLE -> "就绪" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "运行中" to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** M3 Card 包裹的区块。零阴影 + surfaceContainerLow，与 jian_box 工具页一致。 */
@Composable
private fun SectionCard(
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
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(14.dp)
        ) {
            SectionTitle(title, trailing = trailing)
            Gap(10.dp)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    configs: List<ConfigStore.Config>,
    activeId: String?,
    onApply: (ConfigStore.Config) -> Unit,
    onClear: () -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = configs.firstOrNull { it.id == activeId }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("配置", trailing = if (configs.isEmpty()) null else "${configs.size}")

            if (configs.isEmpty()) {
                Gap(8.dp)
                Text(
                    "还没有配置。一套配置 = 调用行 + payload dex。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(8.dp)
                OutlinedButton(onClick = onOpenConfig) { Text("去创建配置") }
            } else {
                Gap(8.dp)
                // M3 标准的可选下拉：ExposedDropdownMenuBox + menuAnchor。
                // 之前用 Box + DropdownMenu 手动定位，锚点算不准会跑到屏幕左边；
                // menuAnchor 把菜单绑定到输入框本身，位置由框架保证。
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = active?.name ?: "选择一套配置",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                        supportingText = {
                            Text(
                                active?.let {
                                    "${it.callCount} 条 · ${it.targetMethod} · " +
                                        if (it.hasDex) "dex ${it.dexSizeKb}KB" else "无 dex"
                                } ?: "${configs.size} 套可用",
                            )
                        },
                        trailingIcon = {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 180f else 0f,
                                label = "cfgArrow",
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.rotate(rotation),
                            )
                        },
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
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
                                onClick = {
                                    onApply(cfg)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                if (active != null) {
                    TextButton(onClick = onClear) { Text("取消套用") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodSelector(
    points: List<LifecyclePoints.Point>,
    selected: String,
    existsCheck: (LifecyclePoints.Point) -> Boolean,
    onSelect: (LifecyclePoints.Point) -> Unit,
    onCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = points.firstOrNull { it.name == selected }
    val isCustom = current == null

    // 标准 M3 下拉。之前把 9 个方法平铺成 chip 行，占三行且视觉噪音大——
    // 注入点是一个「单选」语义，下拉才是对的控件。
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.ifBlank { "选择方法" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("注入到") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            supportingText = {
                Text(
                    when {
                        isCustom -> "自定义方法 · 不存在时会自动创建"
                        existsCheck(current!!) -> current.hint
                        else -> "${current.hint} · 类里没有，将自动创建"
                    },
                )
            },
            trailingIcon = {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "methodArrow",
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            points.forEach { p ->
                val exists = existsCheck(p)
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                )
                                if (!exists) {
                                    HGap(6.dp)
                                    Surface(
                                        shape = RoundedCornerShape(100.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                    ) {
                                        Text(
                                            "新建",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(
                                                horizontal = 6.dp, vertical = 1.dp
                                            ),
                                        )
                                    }
                                }
                            }
                            Text(
                                p.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(p)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("自定义方法…", style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onCustom()
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun FilePickRow(
    title: String,
    detail: String?,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (selected) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                onClear != null -> TextButton(onClick = onClear) { Text("清除") }
                else -> Text(
                    "选择",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: ApkInjectPipeline.Result,
    saved: OutputStore.Saved?,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    val ok = result.ok && saved != null

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (ok) "注入成功" else "注入失败",
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )

            if (!ok) {
                Gap(8.dp)
                Text(
                    result.error ?: "产物写入 Download 失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Gap(10.dp)
                OutlinedButton(onClick = onReset) { Text("重试") }
                return@Column
            }

            Gap(12.dp)
            Text(
                "输出路径",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(4.dp)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    saved.file.absolutePath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp),
                )
            }

            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall) { Text("安装") }
                OutlinedButton(onClick = onLocate) { Text("MT 定位") }
                OutlinedButton(onClick = onShare) { Text("分享") }
            }

            Gap(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MetricItem("${result.injectedCount}", "注入调用")
                MetricItem(
                    "${(result.stats?.outputSize ?: 0) / 1024 / 1024}",
                    "MB 产物",
                )
                MetricItem(
                    result.stats?.addedDex?.removeSuffix(".dex")?.removePrefix("classes") ?: "—",
                    "追加 dex",
                )
            }

            Gap(10.dp)
            Text(
                "未重签名，只能覆盖安装到已装同签名包的设备。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricItem(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogCard(steps: List<ApkInjectPipeline.Step>) {
    var open by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            // 整行可点：用 Surface + transparent 承载点击，避免嵌套 clickable 冲突
            Surface(
                onClick = { open = !open },
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "执行日志 (${steps.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (open) "收起" else "展开",
                        modifier = Modifier.rotate(if (open) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(top = 10.dp)) {
                    steps.forEach { s ->
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text(
                                if (s.warn) "!" else "›",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = if (s.warn) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                            HGap(6.dp)
                            Text(
                                s.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.detail.isNotBlank()) {
                                HGap(6.dp)
                                Text(
                                    s.detail,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = if (s.warn) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(state: InjectUiState, onRun: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 12.dp)
        ) {
            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InjectAnimation(
                        style = state.animationStyle,
                        phaseLabel = state.phase.label,
                        size = 76.dp,
                    )
                    HGap(16.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.phase.label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "处理中，请稍候",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Button(
                    onClick = onRun,
                    enabled = state.canRun,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("开始注入", style = MaterialTheme.typography.titleMedium)
                }
                if (state.missing.isNotEmpty()) {
                    Gap(6.dp)
                    Text(
                        "还缺：" + state.missing.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** `Landroid/app/Activity;` → `Activity` */
internal fun simpleName(descriptor: String): String =
    descriptor.trimStart('L').trimEnd(';').substringAfterLast('/')

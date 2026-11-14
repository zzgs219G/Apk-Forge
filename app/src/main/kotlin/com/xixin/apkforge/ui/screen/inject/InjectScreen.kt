package com.xixin.apkforge.ui.screen.inject

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.core.apk.ApkInjectPipeline
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.feature.inject.InjectUiState
import com.xixin.apkforge.feature.inject.InjectViewModel
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.OutputStore
import com.xixin.apkforge.feature.inject.Phase
import com.xixin.apkforge.ui.anim.InjectAnimation
import com.xixin.apkforge.ui.design.Collapsible
import com.xixin.apkforge.ui.design.CollapsibleHeader
import com.xixin.apkforge.ui.design.Dim
import com.xixin.apkforge.ui.design.Forge
import com.xixin.apkforge.ui.design.ForgeChip
import com.xixin.apkforge.ui.design.ForgeChipFlow
import com.xixin.apkforge.ui.design.ForgeCodeField
import com.xixin.apkforge.ui.design.ForgeEmptyState
import com.xixin.apkforge.ui.design.ForgeField
import com.xixin.apkforge.ui.design.ForgeFileRow
import com.xixin.apkforge.ui.design.ForgePopup
import com.xixin.apkforge.ui.design.ForgePopupItem
import com.xixin.apkforge.ui.design.ForgePrimaryButton
import com.xixin.apkforge.ui.design.ForgeSecondaryButton
import com.xixin.apkforge.ui.design.ForgeSelectRow
import com.xixin.apkforge.ui.design.ForgeSmallButton
import com.xixin.apkforge.ui.design.ForgeStatus
import com.xixin.apkforge.ui.design.ForgeTopBar
import com.xixin.apkforge.ui.design.HairLine
import com.xixin.apkforge.ui.design.KVRow
import com.xixin.apkforge.ui.design.Metric
import com.xixin.apkforge.ui.design.SectionLabel
import com.xixin.apkforge.ui.design.StatusKind
import com.xixin.apkforge.ui.design.TerminalBlock
import com.xixin.apkforge.ui.design.VGap
import com.xixin.apkforge.ui.design.pressBounce

/**
 * 注入页。
 *
 * 结构严格按「配置 → 输入 → 执行 → 结果」的依赖顺序，用 [Forge] 组件库拼装。
 * 不再有手写的临时布局——每个元素都来自 `ui/design/`。
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Forge.Colors.bg)
    ) {
        ForgeTopBar(
            title = "注入",
            subtitle = state.activeConfigName ?: "选择 APK 并粘贴调用",
            onMenu = onOpenDrawer,
            trailing = { PhaseStatus(state.phase) },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Dim.edge, end = Dim.edge, bottom = Dim.edge
            ),
            verticalArrangement = Arrangement.spacedBy(Dim.sectionGap),
        ) {
            // ① 配置
            item {
                Column {
                    SectionLabel(
                        "配置",
                        trailing = if (state.configs.isEmpty()) "无" else "${state.configs.size}",
                    )
                    VGap(10.dp)
                    if (state.configs.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Forge.Colors.surface, Forge.Shape.card)
                        ) {
                            ForgeEmptyState(
                                title = "还没有配置",
                                detail = "一套配置 = 调用行 + payload dex。建好后注入时直接选用。",
                                action = {
                                    ForgeSecondaryButton("去创建配置", onOpenConfig)
                                },
                            )
                        }
                    } else {
                        ConfigSelector(
                            configs = state.configs,
                            activeId = state.activeConfigId,
                            onApply = vm::applyConfig,
                            onClear = vm::clearActiveConfig,
                        )
                    }
                }
            }

            // ② APK
            item {
                Column {
                    SectionLabel(
                        "目标 APK",
                        trailing = state.apkFile?.let { "${it.length() / 1024 / 1024} MB" },
                    )
                    VGap(10.dp)
                    ForgeFileRow(
                        label = state.apkFile?.name ?: "选择 APK",
                        detail = state.apkFile?.let {
                            if (state.classFound) {
                                "LAUNCHER ${state.launcherCandidates.size} 个 · 已解析"
                            } else {
                                "已选择"
                            }
                        },
                        filled = state.apkFile != null,
                        busyLabel = if (state.parsingApk) "解析中" else null,
                        onPick = { apkPicker.launch(FilePicker.pickApkIntent(context)) },
                        onClear = state.apkFile?.let { { vm.clearApk() } },
                    )
                }
            }

            // ③ 注入点
            item {
                Column {
                    SectionLabel(
                        "注入点",
                        trailing = state.classSuperType?.let(::simpleName),
                    )
                    VGap(10.dp)
                    ForgeField(
                        value = state.targetClass,
                        onValueChange = vm::setTargetClass,
                        placeholder = "com.example.app.MainActivity",
                        keyboardType = KeyboardType.Uri,
                        trailing = if (state.targetClass.isNotBlank()) {
                            if (state.classFound) "✓" else "?"
                        } else null,
                        trailingColor = if (state.classFound) {
                            Forge.Colors.accent
                        } else {
                            Forge.Colors.danger
                        },
                    )

                    if (state.launcherCandidates.size > 1) {
                        VGap(9.dp)
                        ForgeChipFlow(items = state.launcherCandidates, perRow = 3) { c ->
                            ForgeChip(
                                text = c.substringAfterLast('.'),
                                selected = c == state.targetClass,
                                onClick = { vm.setTargetClass(c) },
                            )
                        }
                    }

                    VGap(16.dp)
                    MethodSelector(
                        points = state.lifecyclePoints,
                        selected = state.targetMethod,
                        existsCheck = state::pointExists,
                        onSelect = vm::selectLifecyclePoint,
                        onCustom = { vm.setTargetMethod("") },
                    )

                    // 只有非标准方法才需要输入框
                    if (state.lifecyclePoints.none { it.name == state.targetMethod }) {
                        VGap(9.dp)
                        ForgeField(
                            value = state.targetMethod,
                            onValueChange = vm::setTargetMethod,
                            placeholder = "自定义方法名",
                            keyboardType = KeyboardType.Ascii,
                        )
                    }

                    VGap(9.dp)
                    Text(
                        "签名 ${state.targetSignature}",
                        style = Forge.Type.microMono,
                        color = Forge.Colors.textFaint,
                    )
                }
            }

            // ④ 调用
            item {
                Column {
                    SectionLabel("调用", trailing = "${state.callCount} 条")
                    VGap(10.dp)
                    ForgeCodeField(
                        value = state.callsText,
                        onValueChange = vm::setCallsText,
                        placeholder = "invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V",
                        isError = state.callErrors.isNotEmpty(),
                        minHeight = 124.dp,
                    )
                    if (state.callErrors.isNotEmpty()) {
                        VGap(8.dp)
                        state.callErrors.forEach {
                            Text(it, style = Forge.Type.microMono, color = Forge.Colors.danger)
                        }
                    }
                }
            }

            // ⑤ payload
            item {
                Column {
                    SectionLabel("Payload dex")
                    VGap(10.dp)
                    ForgeFileRow(
                        label = state.payloadDex?.name ?: "选择 dex",
                        detail = state.payloadDex?.let { "${it.length() / 1024} KB" },
                        filled = state.payloadDex != null,
                        onPick = { dexPicker.launch(FilePicker.pickDexIntent(context)) },
                        onClear = state.payloadDex?.let { { vm.clearPayloadDex() } },
                    )
                }
            }

            // 结果
            state.result?.let { r ->
                item {
                    ResultSection(
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
                item { LogSection(state.steps) }
            }

            item { Spacer(Modifier.height(4.dp)) }
        }

        BottomAction(state, vm::run)
    }
}

// ══════════════════════════ 局部组件 ══════════════════════════

@Composable
private fun PhaseStatus(phase: Phase) {
    when {
        phase == Phase.DONE -> ForgeStatus("完成", StatusKind.OK)
        phase == Phase.FAILED -> ForgeStatus("失败", StatusKind.ERROR)
        phase == Phase.IDLE -> ForgeStatus("就绪", StatusKind.IDLE)
        else -> ForgeStatus("运行中", StatusKind.WARN)
    }
}

@Composable
private fun ConfigSelector(
    configs: List<ConfigStore.Config>,
    activeId: String?,
    onApply: (ConfigStore.Config) -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = configs.firstOrNull { it.id == activeId }

    Box {
        ForgeSelectRow(
            title = "选择一套配置",
            value = active?.name,
            detail = active?.let {
                "${it.callCount} 条调用 · ${it.targetMethod} · " +
                    if (it.hasDex) "dex ${it.dexSizeKb}KB" else "无 dex"
            } ?: "${configs.size} 套可用",
            accentBorder = active != null,
            onClick = { menu = true },
            trailingAction = if (active != null) {
                {
                    Box(
                        Modifier
                            .clip(Forge.Shape.chip)
                            .background(Forge.Colors.surfaceHigh)
                            .pressBounce(onClick = onClear)
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text("取消", style = Forge.Type.label, color = Forge.Colors.textFaint)
                    }
                }
            } else null,
        )
        ForgePopup(expanded = menu, onDismiss = { menu = false }) {
            configs.forEach { cfg ->
                ForgePopupItem(
                    title = cfg.name,
                    subtitle = "${cfg.callCount} 条 · ${cfg.targetMethod}" +
                        if (cfg.hasDex) " · 带 dex" else " · 无 dex",
                    badge = if (cfg.id == activeId) "当前" else null,
                    selected = cfg.id == activeId,
                    onClick = { onApply(cfg); menu = false },
                )
            }
        }
    }
}

@Composable
private fun MethodSelector(
    points: List<LifecyclePoints.Point>,
    selected: String,
    existsCheck: (LifecyclePoints.Point) -> Boolean,
    onSelect: (LifecyclePoints.Point) -> Unit,
    onCustom: () -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) points else points.take(6)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("注入到", style = Forge.Type.label, color = Forge.Colors.textFaint)
            Spacer(Modifier.weight(1f))
            if (points.size > 6) {
                Box(
                    Modifier
                        .pressBounce { showAll = !showAll }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (showAll) "收起" else "更多 ${points.size - 6}",
                        style = Forge.Type.label,
                        color = Forge.Colors.accent,
                    )
                }
            }
        }
        VGap(8.dp)
        ForgeChipFlow(items = shown, perRow = 3) { p ->
            ForgeChip(
                text = p.name,
                selected = p.name == selected,
                onClick = { onSelect(p) },
                badge = if (existsCheck(p)) null else "新",
            )
        }
        VGap(8.dp)
        ForgeChip(
            text = "自定义…",
            selected = points.none { it.name == selected },
            onClick = onCustom,
        )
    }
}

@Composable
private fun ResultSection(
    result: ApkInjectPipeline.Result,
    saved: OutputStore.Saved?,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    val ok = result.ok && saved != null

    Column {
        SectionLabel("结果", trailing = if (ok) "成功" else "失败")
        VGap(10.dp)

        Column(
            Modifier
                .fillMaxWidth()
                .background(Forge.Colors.surface, Forge.Shape.card)
                .padding(16.dp)
        ) {
            if (!ok) {
                Text(
                    result.error ?: "产物写入 Download 失败",
                    style = Forge.Type.code,
                    color = Forge.Colors.danger,
                )
                VGap(14.dp)
                ForgeSecondaryButton("重试", onReset, Modifier.fillMaxWidth())
                return@Column
            }

            Row(Modifier.fillMaxWidth()) {
                Metric(
                    "${result.injectedCount}",
                    "注入调用",
                    Modifier.weight(1f),
                    Forge.Colors.accent,
                )
                Metric(
                    "${(result.stats?.outputSize ?: 0) / 1024 / 1024}",
                    "MB 产物",
                    Modifier.weight(1f),
                )
                Metric(
                    result.stats?.addedDex?.removeSuffix(".dex")?.removePrefix("classes") ?: "—",
                    "追加 dex",
                    Modifier.weight(1f),
                    if (result.stats?.addedDex != null) Forge.Colors.accent
                    else Forge.Colors.textFaint,
                )
            }

            VGap(18.dp)
            SectionLabel("输出路径", Modifier)
            VGap(7.dp)
            TerminalBlock {
                Text(
                    saved.file.absolutePath,
                    style = Forge.Type.microMono,
                    color = Forge.Colors.accent,
                )
            }

            VGap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeSmallButton("安装", onInstall, filled = true)
                ForgeSmallButton("MT 定位", onLocate)
                ForgeSmallButton("分享", onShare)
            }

            VGap(16.dp)
            HairLine()
            VGap(12.dp)
            KVRow("包名", result.packageName ?: "?")
            KVRow("注入点", result.resolvedClass ?: "?")
            KVRow(
                "体积",
                "${(result.stats?.originalSize ?: 0) / 1024 / 1024} → " +
                    "${(result.stats?.outputSize ?: 0) / 1024 / 1024} MB",
            )
            VGap(10.dp)
            Text(
                "未重签名，只能覆盖安装到已装同签名包的设备。",
                style = Forge.Type.caption,
                color = Forge.Colors.textFaint,
            )
        }
    }
}

@Composable
private fun LogSection(steps: List<ApkInjectPipeline.Step>) {
    var open by remember { mutableStateOf(false) }
    Column {
        CollapsibleHeader(
            title = "执行日志",
            trailing = "${steps.size}",
            open = open,
            onToggle = { open = !open },
        )
        Collapsible(open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(Forge.Colors.surfaceHigh, Forge.Shape.data)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                steps.forEach { s ->
                    Row {
                        Text(
                            if (s.warn) "!" else "›",
                            style = Forge.Type.microMono,
                            color = if (s.warn) Forge.Colors.danger else Forge.Colors.accent,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(s.label, style = Forge.Type.microMono, color = Forge.Colors.textDim)
                        if (s.detail.isNotBlank()) {
                            Spacer(Modifier.width(7.dp))
                            Text(
                                s.detail,
                                style = Forge.Type.microMono,
                                color = if (s.warn) Forge.Colors.danger else Forge.Colors.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomAction(state: InjectUiState, onRun: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Forge.Colors.bg)
    ) {
        HairLine(Modifier)
        Column(Modifier.padding(horizontal = Dim.edge, vertical = 14.dp)) {
            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InjectAnimation(
                        style = state.animationStyle,
                        phaseLabel = state.phase.label,
                        size = 88.dp,
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.phase.label, style = Forge.Type.title, color = Forge.Colors.text)
                        VGap(4.dp)
                        Text(
                            "处理中，请稍候",
                            style = Forge.Type.caption,
                            color = Forge.Colors.textFaint,
                        )
                    }
                }
            } else {
                ForgePrimaryButton(
                    text = "开始注入",
                    onClick = onRun,
                    enabled = state.canRun,
                )
                if (state.missing.isNotEmpty()) {
                    VGap(8.dp)
                    Text(
                        "还缺：" + state.missing.joinToString("、"),
                        style = Forge.Type.caption,
                        color = Forge.Colors.textFaint,
                    )
                }
            }
        }
    }
}

/** `Landroid/app/Activity;` → `Activity` */
internal fun simpleName(descriptor: String): String =
    descriptor.trimStart('L').trimEnd(';').substringAfterLast('/')

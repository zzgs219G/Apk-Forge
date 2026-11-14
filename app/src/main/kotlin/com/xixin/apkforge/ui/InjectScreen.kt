package com.xixin.apkforge.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.inject.ApkInjectPipeline
import com.xixin.apkforge.inject.ConfigStore
import com.xixin.apkforge.inject.FilePicker
import com.xixin.apkforge.inject.InjectUiState
import com.xixin.apkforge.inject.InjectViewModel
import com.xixin.apkforge.inject.OutputStore
import com.xixin.apkforge.inject.Phase

/**
 * 注入主界面。
 *
 * 结构取向：**配置优先**。用户日常是「选中一个已存配置 → 选 APK → 注入」，
 * 而不是每次重新粘调用。所以配置区放在最上面，主流程紧随其后。
 *
 * 注入过程：进度动画 + 阶段名直接占据底部主按钮位置，
 * 完成时**立刻**把路径和「安装 / MT 定位 / 分享」三个按钮顶到眼前，
 * 不需要用户翻日志找结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InjectScreen(
    vm: InjectViewModel = viewModel(),
    onOpenDrawer: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }


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

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("注入", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            if (state.activeConfigName != null) "配置：${state.activeConfigName}"
                            else "选 APK，粘调用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onOpenDrawer) {
                        Text("☰", fontSize = 17.sp)
                    }
                },
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 14.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ───── 配置选择器（只选，不在这里编辑；编辑在配置页） ─────
                if (state.configs.isNotEmpty()) {
                    item {
                        ConfigPicker(
                            configs = state.configs,
                            activeId = state.activeConfigId,
                            onApply = vm::applyConfig,
                            onClear = vm::clearActiveConfig,
                        )
                    }
                }

                // ───── ① 目标 APK ─────
                item {
                    Block("① 目标 APK") {
                        FileRow(
                            label = state.apkFile?.let {
                                "${it.name}  ·  ${it.length() / 1024 / 1024} MB"
                            },
                            hint = "选择要注入的 APK",
                            onPick = { apkPicker.launch(FilePicker.pickApkIntent(context)) },
                            onClear = state.apkFile?.let { { vm.clearApk() } },
                        )
                        if (state.parsingApk) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }

                // ───── ② 注入点（生命周期方法用下拉框选，不让人手输） ─────
                item {
                    Block("② 注入点") {
                        OutlinedTextField(
                            value = state.targetClass,
                            onValueChange = vm::setTargetClass,
                            label = { Text("类名", fontSize = 11.sp) },
                            placeholder = { Text("com.example.app.MainActivity", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            trailingIcon = {
                                if (state.classFound) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            supportingText = if (state.targetClass.isNotBlank()) {
                                {
                                    Text(
                                        when {
                                            state.classFound ->
                                                "已找到 · 父类 " +
                                                    (state.classSuperType?.let(::simpleName) ?: "?")
                                            else -> "包里没有这个类，请检查拼写"
                                        },
                                        fontSize = 10.sp,
                                        color = if (state.classFound) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            } else null,
                        )

                        if (state.launcherCandidates.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "多个 LAUNCHER：",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.launcherCandidates.forEach { c ->
                                    TextButton(onClick = { vm.setTargetClass(c) }) {
                                        Text(
                                            c.substringAfterLast('.'),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 生命周期方法下拉框：直接列出，并标注「已有 / 将新建」
                        var methodMenu by remember { mutableStateOf(false) }
                        Text(
                            "注入到哪个方法",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            onClick = { methodMenu = true },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    state.targetMethod,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                val exists = state.classMethods.any {
                                    it.name == state.targetMethod
                                }
                                Text(
                                    if (exists) "已有" else "将新建",
                                    fontSize = 9.sp,
                                    color = if (exists) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "▾",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = methodMenu,
                            onDismissRequest = { methodMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("（自定义）", fontSize = 12.sp)
                                },
                                onClick = { methodMenu = false },
                            )
                            state.lifecyclePoints.forEach { p ->
                                val exists = state.pointExists(p)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                p.label,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                if (exists) "已有" else "新建",
                                                fontSize = 9.sp,
                                                color = if (exists) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.tertiary
                                                }
                                            )
                                        }
                                    },
                                    onClick = {
                                        vm.selectLifecyclePoint(p)
                                        methodMenu = false
                                    },
                                )
                            }
                        }

                        // 选了「自定义」时才给手输框
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = state.targetMethod,
                            onValueChange = vm::setTargetMethod,
                            label = { Text("或手动输入方法名", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "签名 ${state.targetSignature}" +
                                (if (state.classMethods.any { it.name == state.targetMethod }) "" else " · 该方法不存在，将自动创建并补 super 调用"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ───── ③ 调用 + dex（同一张卡：它们是强关联的一套） ─────
                item {
                    Block("③ 调用 + payload dex") {
                        OutlinedTextField(
                            value = state.callsText,
                            onValueChange = vm::setCallsText,
                            placeholder = {
                                Text(
                                    "invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 88.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            isError = state.callErrors.isNotEmpty(),
                        )

                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.callCount > 0) "已识别 ${state.callCount} 条" else "无有效调用",
                                fontSize = 10.sp,
                                color = if (state.callCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = vm::loadSampleDex) {
                                Text("示例", fontSize = 11.sp)
                            }
                        }
                        state.callErrors.forEach {
                            Text(
                                it,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        FileRow(
                            label = state.payloadDex?.let { "${it.name}  ·  ${it.length() / 1024} KB" },
                            hint = "选择 payload dex",
                            onPick = { dexPicker.launch(FilePicker.pickDexIntent(context)) },
                            onClear = state.payloadDex?.let { { vm.clearPayloadDex() } },
                        )
                    }
                }

                // ───── 结果（成功/失败都直接顶上来，不放日志下面） ─────
                state.result?.let { r ->
                    item {
                        ResultPanel(
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

                // ───── 日志（可折叠，默认收起） ─────
                if (state.steps.isNotEmpty()) {
                    item {
                        LogSection(state.steps)
                    }
                }

                // ───── 动画风格 ─────
                item {
                    AnimationStylePicker(
                        current = state.animationStyle,
                        onChange = vm::setAnimationStyle,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            HorizontalDivider()
            ActionBar(state = state, onRun = vm::run)
        }
    }
}

// ────────────────────────── 组件 ──────────────────────────

@Composable
private fun ResultPanel(
    result: ApkInjectPipeline.Result,
    saved: OutputStore.Saved?,
    onInstall: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    val ok = result.ok && saved != null
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (ok) "✓ 注入成功" else "✗ 注入失败",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )

            if (ok) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "输出路径",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    saved.file.absolutePath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp)
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionPill("安装", MaterialTheme.colorScheme.primary, onInstall)
                    ActionPill("MT 定位", MaterialTheme.colorScheme.primary, onLocate)
                    ActionPill("分享", MaterialTheme.colorScheme.primary, onShare)
                }

                Spacer(Modifier.height(10.dp))
                ResultLine("包名", result.packageName ?: "?")
                ResultLine("注入点", result.resolvedClass ?: "?")
                ResultLine("调用", "${result.injectedCount} 条")
                result.stats?.addedDex?.let { ResultLine("追加", it) }
                ResultLine(
                    "体积",
                    "${(result.stats?.originalSize ?: 0) / 1024 / 1024} MB → " +
                        "${(result.stats?.outputSize ?: 0) / 1024 / 1024} MB"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "未重签名：只能覆盖安装到已装同签名包的设备。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    result.error ?: "产物写入 Download 失败",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onReset) { Text("再次注入", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun ResultLine(k: String, v: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            k,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Text(v, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LogSection(steps: List<ApkInjectPipeline.Step>) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 6.dp, horizontal = 2.dp)
        ) {
            Text(
                "执行日志 (${steps.size})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (open) "收起" else "展开",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        AnimatedVisibility(visible = open) {
            Column {
                steps.forEach { s ->
                    Row(Modifier.padding(vertical = 1.dp, horizontal = 2.dp)) {
                        Text(
                            if (s.warn) "!" else "›",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (s.warn) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            s.label,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (s.detail.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                s.detail,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (s.warn) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun FileRow(
    label: String?,
    hint: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label ?: hint,
                fontSize = 12.sp,
                fontFamily = if (label != null) FontFamily.Monospace else FontFamily.Default,
                color = if (label != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onClear != null) {
                TextButton(onClick = onClear) { Text("清除", fontSize = 11.sp) }
            } else {
                Text("选择", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 底部主按钮。注入中时它**变成进度动画 + 当前阶段**，
 * 而不是只多出一行日志——用户点了要立刻看到反馈。
 */
@Composable
private fun ActionBar(state: InjectUiState, onRun: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (state.running) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    InjectAnimation(
                        style = state.animationStyle,
                        phaseLabel = state.phase.label,
                        size = 116.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            val enabled = state.canRun
            Surface(
                onClick = { if (enabled) onRun() },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "开始注入",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.missing.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "还缺：" + state.missing.joinToString("、"),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * 主页面上的配置选择器：只负责「选一套用」，编辑/删除全部在配置页。
 *
 * 之前把增删改查全塞在主页面，导致注入主线被一堆管理按钮淹没。
 * 拆开后这里只剩一个下拉 + 当前配置摘要。
 */
@Composable
private fun ConfigPicker(
    configs: List<ConfigStore.Config>,
    activeId: String?,
    onApply: (ConfigStore.Config) -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = configs.firstOrNull { it.id == activeId }

    Surface(
        onClick = { menu = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    active?.name ?: "选择注入配置",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    active?.let {
                        "${it.callCount} 条调用 · " +
                            (if (it.hasDex) "dex ${it.dexSizeKb}KB" else "无 dex")
                    } ?: "${configs.size} 个可用配置",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active != null) {
                TextButton(onClick = onClear) { Text("取消", fontSize = 10.sp) }
            }
            Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        configs.forEach { cfg ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(cfg.name, fontSize = 12.sp)
                        Text(
                            "${cfg.callCount} 条 · ${cfg.targetMethod}" +
                                if (cfg.hasDex) " · 带 dex" else " · 无 dex",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { onApply(cfg); menu = false },
            )
        }
    }
}

/** `Landroid/app/Activity;` → `Activity` */
private fun simpleName(descriptor: String): String =
    descriptor.trimStart('L').trimEnd(';').substringAfterLast('/')

package com.xixin.apkforge.ui.screen.config

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.feature.config.ConfigEditorState
import com.xixin.apkforge.feature.config.ConfigViewModel
import com.xixin.apkforge.ui.lifted.page.AppTopBar
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.components.input.ToolSingleLineInput
import com.xixin.apkforge.ui.lifted.components.input.ToolInputField

/**
 * 配置页。全部使用 M3 标准组件。
 */
@Composable
fun ConfigScreen(
    vm: ConfigViewModel = viewModel(),
    onOpenDrawer: () -> Unit = {},
    /** 从控制台/注入配置页推入时给返回箭头；点它走 popBackStack，回到来处。 */
    onBack: (() -> Unit)? = null,
    onBackToConsole: () -> Unit = {},
    /** 保存成功后通知外部刷新（注入配置页的配置下拉要立刻看得到新配置）。 */
    onSaved: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current

    var showEditor by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ConfigStore.Config?>(null) }
    var deleting by remember { mutableStateOf<ConfigStore.Config?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    val dexPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "dex") }
                .onSuccess { vm.setDex(it) }
                .onFailure { vm.showMessageSafely("dex 读取失败：${it.message}") }
        }
    }

    LaunchedEffect(Unit) { vm.refresh() }

    renaming?.let { cfg ->
        NameDialog(
            title = "重命名",
            initial = cfg.name,
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(cfg.id, it); renaming = null; onSaved() },
        )
    }
    deleting?.let { cfg ->
        ConfirmDialog(
            title = "删除配置",
            body = "「${cfg.name}」及其绑定的 dex 会被删除，不可恢复。",
            confirmText = "删除",
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(cfg.id); deleting = null; onSaved() },
        )
    }
    if (confirmingDiscard) {
        ConfirmDialog(
            title = "放弃修改？",
            body = "当前编辑内容尚未保存。",
            confirmText = "放弃",
            onDismiss = { confirmingDiscard = false },
            onConfirm = { confirmingDiscard = false; showEditor = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = if (showEditor) "编辑配置" else "配置",
            leadingIcon = if (showEditor || onBack == null) Icons.Filled.Menu
            else Icons.AutoMirrored.Rounded.ArrowBack,
            leadingLabel = if (showEditor || onBack == null) "打开侧边栏" else "返回",
            onMenuClick = {
                if (showEditor) {
                    if (state.dirty) confirmingDiscard = true else showEditor = false
                } else if (onBack != null) {
                    onBack()
                } else {
                    onOpenDrawer()
                }
            },
            actions = {
                if (showEditor) {
                    Button(
                        onClick = {
                            vm.save {
                                showEditor = false
                                onSaved()
                            }
                        },
                        enabled = state.canSave,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("保存") }
                } else {
                    FilledTonalButton(
                        onClick = { vm.startNew(); showEditor = true },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("新建")
                    }
                }
            },
        )

        Box(Modifier.weight(1f)) {
            if (showEditor) {
                ConfigEditor(
                    state = state,
                    onName = vm::setName,
                    onCalls = vm::setCalls,
                    onPoint = vm::setLifecyclePoint,
                    onMethod = vm::setMethod,
                    onPickDex = { dexPicker.launch(FilePicker.pickDexIntent(context)) },
                    onClearDex = { vm.setDex(null) },
                    onSample = vm::loadSampleDex,
                )
            } else if (state.configs.isEmpty()) {
                EmptyConfigs(
                    onCreate = { vm.startNew(); showEditor = true },
                    onBackToConsole = onBackToConsole,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AppConstants.SCREEN_EDGE_PADDING,
                        end = AppConstants.SCREEN_EDGE_PADDING,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.configs, key = { it.id }) { cfg ->
                        ConfigCardItem(
                            cfg = cfg,
                            onEdit = { vm.edit(cfg); showEditor = true },
                            onRename = { renaming = cfg },
                            onDuplicate = { vm.duplicate(cfg) },
                            onDelete = { deleting = cfg },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConfigs(onCreate: () -> Unit, onBackToConsole: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有配置", style = MaterialTheme.typography.titleMedium)
        Gap(6.dp)
        Text(
            "一套配置 = 若干条 smali 调用 + 一个 payload dex。\n建好后，注入时直接选用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Gap(16.dp)
        Button(onClick = onCreate) { Text("新建配置") }
        Gap(4.dp)
        TextButton(onClick = onBackToConsole) { Text("回控制台") }
    }
}

@Composable
private fun ConfigCardItem(
    cfg: ConfigStore.Config,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cfg.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("复制一份") },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            val firstCall = cfg.calls.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (firstCall != null) {
                Text(
                    firstCall,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPill("${cfg.callCount} 条")
                InfoPill(cfg.targetMethod)
                InfoPill(
                    if (cfg.hasDex) "dex ${cfg.dexSizeKb}KB" else "无 dex",
                    warn = !cfg.hasDex,
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (warn) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (warn) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ConfigEditor(
    state: ConfigEditorState,
    onName: (String) -> Unit,
    onCalls: (String) -> Unit,
    onPoint: (LifecyclePoints.Point) -> Unit,
    onMethod: (String) -> Unit,
    onPickDex: () -> Unit,
    onClearDex: () -> Unit,
    onSample: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppConstants.SCREEN_EDGE_PADDING,
            end = AppConstants.SCREEN_EDGE_PADDING,
            top = 12.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EditorCard("名称") {
                ToolSingleLineInput(
                    value = state.name,
                    onValueChange = onName,
                    label = "配置名",
                    placeholder = "给这套配置起个名字",
                )
            }
        }

        item {
            EditorCard("调用行", "${state.callCount} 条") {
                ToolInputField(
                    value = state.calls,
                    onValueChange = onCalls,
                    label = "调用行",
                    placeholder = "invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V",
                    isError = state.callErrors.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
                if (state.callErrors.isNotEmpty()) {
                    Gap(6.dp)
                    state.callErrors.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Gap(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "一行一条，从 MT / baksmali 粘贴",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSample) { Text("载入示例") }
                }
            }
        }

        item {
            EditorCard("注入到") {
                val isStandard = LifecyclePoints.ACTIVITY.any { it.name == state.targetMethod }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LifecyclePoints.ACTIVITY.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { p ->
                                FilterChip(
                                    selected = p.name == state.targetMethod,
                                    onClick = { onPoint(p) },
                                    label = {
                                        Text(
                                            p.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (!isStandard) {
                    Gap(8.dp)
                    ToolSingleLineInput(
                        value = state.targetMethod,
                        onValueChange = onMethod,
                        label = "自定义方法名",
                        placeholder = "方法名",
                    )
                }
                Gap(6.dp)
                Text(
                    "签名 ${state.targetSignature}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            EditorCard("Payload dex") {
                OutlinedCard(
                    onClick = onPickDex,
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
                                state.dexFile?.name ?: "选择 dex",
                                style = if (state.dexExists) {
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.dexExists) {
                                Text(
                                    "${state.dexFile!!.length() / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.dexPath != null) {
                            TextButton(onClick = onClearDex) { Text("清除") }
                        } else {
                            Text(
                                "选择",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Gap(6.dp)
                Text(
                    "dex 会复制进 App 私有目录，与原始文件解耦",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun EditorCard(
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

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

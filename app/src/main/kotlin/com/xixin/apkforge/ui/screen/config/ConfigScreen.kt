package com.xixin.apkforge.ui.screen.config

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.feature.config.ConfigEditorState
import com.xixin.apkforge.data.ConfigStore
import com.xixin.apkforge.feature.config.ConfigViewModel
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.core.dex.LifecyclePoints
import com.xixin.apkforge.ui.design.Chevron
import com.xixin.apkforge.ui.design.Dim
import com.xixin.apkforge.ui.design.Forge
import com.xixin.apkforge.ui.design.ForgeCard
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
import com.xixin.apkforge.ui.design.ForgeSmallButton
import com.xixin.apkforge.ui.design.ForgeStatus
import com.xixin.apkforge.ui.design.ForgeTopBar
import com.xixin.apkforge.ui.design.KVRow
import com.xixin.apkforge.ui.design.LabeledField
import com.xixin.apkforge.ui.design.SectionLabel
import com.xixin.apkforge.ui.design.StatusKind
import com.xixin.apkforge.ui.design.VGap
import com.xixin.apkforge.ui.design.pressBounce

/**
 * 配置页。管理「调用 + dex」这套组合，与注入页共用 [Forge] 组件库。
 */
@Composable
fun ConfigScreen(
    vm: ConfigViewModel = viewModel(),
    onOpenDrawer: () -> Unit = {},
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
            onConfirm = { vm.rename(cfg.id, it); renaming = null },
        )
    }
    deleting?.let { cfg ->
        ConfirmDialog(
            title = "删除配置",
            body = "「${cfg.name}」及其绑定的 dex 会被删除，不可恢复。",
            confirmText = "删除",
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(cfg.id); deleting = null },
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Forge.Colors.bg)
    ) {
        ForgeTopBar(
            title = if (showEditor) "编辑配置" else "配置",
            subtitle = if (showEditor) {
                if (state.editingNew) "新建" else state.name.ifBlank { "未命名" }
            } else {
                "${state.configs.size} 套"
            },
            onMenu = {
                if (showEditor) {
                    if (state.dirty) confirmingDiscard = true else showEditor = false
                } else {
                    onOpenDrawer()
                }
            },
            trailing = {
                if (showEditor) {
                    ForgeSmallButton(
                        text = "保存",
                        onClick = { vm.save { showEditor = false } },
                        color = if (state.canSave) Forge.Colors.accent else Forge.Colors.textFaint,
                        filled = state.canSave,
                    )
                } else {
                    ForgeSmallButton(
                        text = "新建",
                        onClick = { vm.startNew(); showEditor = true },
                        filled = true,
                    )
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
                ForgeEmptyState(
                    title = "还没有配置",
                    detail = "一套配置 = 若干条 smali 调用 + 一个 payload dex。\n建好后，注入时直接选用。",
                    action = {
                        ForgePrimaryButton(
                            "新建配置",
                            { vm.startNew(); showEditor = true },
                            Modifier.fillMaxWidth(0.7f),
                        )
                    },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Dim.edge, end = Dim.edge, top = 4.dp, bottom = Dim.edge
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.configs, key = { it.id }) { cfg ->
                        ConfigRowItem(
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
private fun ConfigRowItem(
    cfg: ConfigStore.Config,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Box {
        ForgeCard(Modifier.pressBounce(onClick = onEdit)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cfg.name,
                        style = Forge.Type.title,
                        color = Forge.Colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(Forge.Shape.chip)
                            .pressBounce { menu = true }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text("⋯", style = Forge.Type.caption, color = Forge.Colors.textFaint)
                    }
                }

                val firstCall = cfg.calls.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (firstCall != null) {
                    VGap(7.dp)
                    Text(
                        firstCall,
                        style = Forge.Type.microMono,
                        color = Forge.Colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                VGap(11.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ForgeChip(
                        text = "${cfg.callCount} 条",
                        selected = false,
                        onClick = onEdit,
                    )
                    ForgeChip(
                        text = cfg.targetMethod,
                        selected = false,
                        onClick = onEdit,
                    )
                    if (cfg.hasDex) {
                        ForgeStatus("dex ${cfg.dexSizeKb}KB", StatusKind.OK)
                    } else {
                        ForgeStatus("无 dex", StatusKind.WARN)
                    }
                }
            }
        }

        ForgePopup(expanded = menu, onDismiss = { menu = false }) {
            ForgePopupItem("编辑", onClick = { menu = false; onEdit() })
            ForgePopupItem("重命名", onClick = { menu = false; onRename() })
            ForgePopupItem("复制一份", onClick = { menu = false; onDuplicate() })
            ForgePopupItem(
                title = "删除",
                subtitle = "不可恢复",
                badge = "危险",
                badgeColor = Forge.Colors.danger,
                destructive = true,
                onClick = { menu = false; onDelete() },
            )
        }
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
            start = Dim.edge, end = Dim.edge, top = 4.dp, bottom = 44.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Dim.sectionGap),
    ) {
        item {
            LabeledField("名称") {
                ForgeField(
                    value = state.name,
                    onValueChange = onName,
                    placeholder = "给这套配置起个名字",
                )
            }
        }

        item {
            Column {
                SectionLabel("调用行", trailing = "${state.callCount} 条")
                VGap(10.dp)
                ForgeCodeField(
                    value = state.calls,
                    onValueChange = onCalls,
                    placeholder = "invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V",
                    isError = state.callErrors.isNotEmpty(),
                    minHeight = 148.dp,
                )
                if (state.callErrors.isNotEmpty()) {
                    VGap(8.dp)
                    state.callErrors.forEach {
                        Text(it, style = Forge.Type.microMono, color = Forge.Colors.danger)
                    }
                }
                VGap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "一行一条，从 MT / baksmali 粘贴",
                        style = Forge.Type.caption,
                        color = Forge.Colors.textFaint,
                        modifier = Modifier.weight(1f),
                    )
                    ForgeSmallButton("载入示例", onSample)
                }
            }
        }

        item {
            Column {
                SectionLabel("注入到")
                VGap(10.dp)
                val isStandard = LifecyclePoints.ACTIVITY.any { it.name == state.targetMethod }
                ForgeChipFlow(items = LifecyclePoints.ACTIVITY, perRow = 3) { p ->
                    ForgeChip(
                        text = p.name,
                        selected = p.name == state.targetMethod,
                        onClick = { onPoint(p) },
                    )
                }
                if (!isStandard) {
                    VGap(9.dp)
                    ForgeField(
                        value = state.targetMethod,
                        onValueChange = onMethod,
                        placeholder = "自定义方法名",
                    )
                }
                VGap(9.dp)
                KVRow("签名", state.targetSignature)
            }
        }

        item {
            Column {
                SectionLabel("Payload dex")
                VGap(10.dp)
                ForgeFileRow(
                    label = state.dexFile?.name ?: "选择 dex",
                    detail = if (state.dexExists) {
                        "${state.dexFile!!.length() / 1024} KB"
                    } else null,
                    filled = state.dexExists,
                    onPick = onPickDex,
                    onClear = if (state.dexPath != null) onClearDex else null,
                )
                VGap(7.dp)
                Text(
                    "dex 会复制进 App 私有目录，与原始文件解耦",
                    style = Forge.Type.caption,
                    color = Forge.Colors.textFaint,
                )
            }
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
        containerColor = Forge.Colors.surfaceHigh,
        title = { Text(title, style = Forge.Type.title, color = Forge.Colors.text) },
        text = { ForgeField(value = v, onValueChange = { v = it }, placeholder = "名称") },
        confirmButton = {
            TextButton(onClick = { onConfirm(v) }) {
                Text("确定", color = Forge.Colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Forge.Colors.textDim)
            }
        },
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
        containerColor = Forge.Colors.surfaceHigh,
        title = { Text(title, style = Forge.Type.title, color = Forge.Colors.text) },
        text = { Text(body, style = Forge.Type.caption, color = Forge.Colors.textDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = Forge.Colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Forge.Colors.textDim)
            }
        },
    )
}

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.inject.ConfigStore
import com.xixin.apkforge.inject.ConfigViewModel
import com.xixin.apkforge.inject.FilePicker
import com.xixin.apkforge.inject.LifecyclePoints

/**
 * 配置页：独立管理「调用 + dex」这套组合。
 *
 * 【为什么单独一页】主注入页要专注「选包 → 注入」这条主线，
 * 配置的增删改查塞进去会让页面变成一锅粥（上一版就是这个毛病）。
 * 拆出来之后：
 *  - 这里可以慢慢编辑、改名、复制、删除，不碰 APK
 *  - 主页面只负责「挑一个已有配置 → 注入」
 *
 * 【交互】左侧列表 + 右侧编辑区在小屏放不下，所以用「列表 / 编辑器」两种视图切换：
 * 默认列表，点某条或点「新建」进入编辑器，返回键回列表（有未保存改动会拦一下）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    vm: ConfigViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val state = vm.state
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    renaming?.let { cfg ->
        NameDialog(
            title = "重命名配置",
            initial = cfg.name,
            onDismiss = { renaming = null },
            onConfirm = {
                vm.rename(cfg.id, it)
                renaming = null
            },
        )
    }

    deleting?.let { cfg ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除配置", fontSize = 15.sp) },
            text = {
                Text(
                    "「${cfg.name}」及其绑定的 dex 文件会被删除，不可恢复。",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(cfg.id)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("放弃修改？", fontSize = 15.sp) },
            text = { Text("当前编辑内容尚未保存。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    showEditor = false
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (showEditor) "编辑配置" else "注入配置",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        Text(
                            if (showEditor) {
                                if (state.editingNew) "新建" else "修改中"
                            } else {
                                "${state.configs.size} 个配置"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (showEditor) {
                            if (state.dirty) confirmingDiscard = true else showEditor = false
                        } else {
                            onBack()
                        }
                    }) {
                        Text(if (showEditor) "返回" else "← 注入", fontSize = 12.sp)
                    }
                },
                actions = {
                    if (showEditor) {
                        TextButton(onClick = {
                            vm.save { showEditor = false }
                        }, enabled = state.canSave) {
                            Text(
                                "保存",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (state.canSave) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        TextButton(onClick = {
                            vm.startNew()
                            showEditor = true
                        }) { Text("新建", fontSize = 12.sp) }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (showEditor) {
                ConfigEditor(
                    state = state,
                    onName = vm::setName,
                    onCalls = vm::setCalls,
                    onMethod = vm::setMethod,
                    onPoint = vm::setLifecyclePoint,
                    onPickDex = { dexPicker.launch(FilePicker.pickDexIntent(context)) },
                    onClearDex = { vm.setDex(null) },
                    onSample = vm::loadSampleDex,
                )
            } else {
                ConfigList(
                    configs = state.configs,
                    onCreate = {
                        vm.startNew()
                        showEditor = true
                    },
                    onEdit = { cfg ->
                        vm.edit(cfg)
                        showEditor = true
                    },
                    onDuplicate = vm::duplicate,
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                )
            }
        }
    }
}

// ────────────────────────── 列表 ──────────────────────────

@Composable
private fun ConfigList(
    configs: List<ConfigStore.Config>,
    onCreate: () -> Unit,
    onEdit: (ConfigStore.Config) -> Unit,
    onDuplicate: (ConfigStore.Config) -> Unit,
    onRename: (ConfigStore.Config) -> Unit,
    onDelete: (ConfigStore.Config) -> Unit,
) {
    if (configs.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("还没有配置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "一套配置 = 若干条 smali 调用 + 一个 payload dex。\n存好之后，注入时直接选用，不用重复粘贴。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = onCreate,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Text(
                    "新建配置",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(configs, key = { it.id }) { cfg ->
            ConfigCard(
                cfg = cfg,
                onEdit = { onEdit(cfg) },
                onDuplicate = { onDuplicate(cfg) },
                onRename = { onRename(cfg) },
                onDelete = { onDelete(cfg) },
            )
        }
    }
}

@Composable
private fun ConfigCard(
    cfg: ConfigStore.Config,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cfg.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onEdit() }
                )
                Box {
                    Surface(
                        onClick = { menu = true },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            "⋯",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑", fontSize = 12.sp) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名", fontSize = 12.sp) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("复制一份", fontSize = 12.sp) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text("删除", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 调用行预览：只显示第一条，让用户能认出是哪套配置
            val firstCall = cfg.calls.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (firstCall != null) {
                Text(
                    firstCall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("${cfg.callCount} 条调用")
                Tag(cfg.targetMethod)
                Tag(
                    if (cfg.hasDex) "dex ${cfg.dexSizeKb}KB" else "无 dex",
                    warn = !cfg.hasDex,
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (warn) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Text(
            text,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = if (warn) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ────────────────────────── 编辑器 ──────────────────────────

@Composable
private fun ConfigEditor(
    state: com.xixin.apkforge.inject.ConfigEditorState,
    onName: (String) -> Unit,
    onCalls: (String) -> Unit,
    onMethod: (String) -> Unit,
    onPoint: (LifecyclePoints.Point) -> Unit,
    onPickDex: () -> Unit,
    onClearDex: () -> Unit,
    onSample: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EditorBlock("名称") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    placeholder = { Text("给这套配置起个名字", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            EditorBlock("调用行") {
                Text(
                    "一行一条，从 MT / baksmali 直接复制粘贴。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.calls,
                    onValueChange = onCalls,
                    placeholder = {
                        Text(
                            "invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp),
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
                        color = if (state.callCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSample) { Text("载入示例", fontSize = 11.sp) }
                }
                state.callErrors.forEach {
                    Text(
                        it,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            EditorBlock("注入到哪个方法") {
                var menu by remember { mutableStateOf(false) }
                Surface(
                    onClick = { menu = true },
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
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.weight(1f))
                        Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    LifecyclePoints.ACTIVITY.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        p.label,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        p.hint,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { onPoint(p); menu = false },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.targetMethod,
                    onValueChange = onMethod,
                    label = { Text("或手动输入", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "签名 ${state.targetSignature}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            EditorBlock("payload dex") {
                Surface(
                    onClick = onPickDex,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.dexFile?.name ?: "未选择 dex",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.dexExists) {
                                Text(
                                    "${state.dexFile!!.length() / 1024} KB",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.dexPath != null) {
                            TextButton(onClick = onClearDex) {
                                Text("清除", fontSize = 11.sp)
                            }
                        } else {
                            Text("选择", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "dex 会被复制进应用私有目录，配置与原始文件解耦。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun EditorBlock(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
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
        title = { Text(title, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                label = { Text("名称", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

package com.xixin.apkforge.ui.screen.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.data.ApkFolderScanner
import com.xixin.apkforge.feature.batch.BatchItem
import com.xixin.apkforge.feature.batch.BatchUiState
import com.xixin.apkforge.feature.batch.BatchViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap

/**
 * 批量注入。
 *
 * 【交互为什么是这样】用户的心智是「把这个目录里的包全插一遍」，
 * 所以流程只有三步：选目录 → 挑一套配置 → 开始。中间不插任何逐项确认——
 * 需要逐项确认的场景，他应该用控制台单个注入。
 *
 * 【为什么进度用线性条 + 每项状态】批量任务的「整体进度」需要一眼可见，
 * 但更重要的是**哪几个失败了**：串行跑到一半挂掉时，用户需要知道是第 7 个包的问题
 * 还是配置的问题。所以每一项都带自己的状态与失败原因。
 */
@Composable
fun BatchScreen(
    vm: BatchViewModel,
    onBack: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
) {
    val state = vm.state
    val context = LocalContext.current

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshConfigs()
        onPauseOrDispose { }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            // 拿持久读权限：批量可能跑几分钟，中途 URI 权限过期会让第 N 个包读不到。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.pickFolder(uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BatchTopBar(
            running = state.running,
            onBack = onBack,
            onPick = { folderPicker.launch(com.xixin.apkforge.data.FilePicker.pickFolderIntent()) },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 6.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ConfigPicker(
                    state = state,
                    onSelect = vm::setConfig,
                    onOpenConfig = onOpenConfig,
                )
            }

            if (state.items.isEmpty()) {
                item { EmptyHint(onPick = { folderPicker.launch(com.xixin.apkforge.data.FilePicker.pickFolderIntent()) }) }
            } else {
                item {
                    ProgressBlock(state = state)
                }
                items(state.items, key = { it.id }) { item ->
                    QueueRow(item = item, running = state.running, onRemove = { vm.remove(item.id) })
                }
            }
        }

        BottomBar(
            state = state,
            onStart = vm::start,
            onCancel = vm::cancel,
            onClear = vm::clearQueue,
        )
    }
}

@Composable
private fun BatchTopBar(running: Boolean, onBack: () -> Unit, onPick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "批量注入",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "选一个目录，把里面的 APK 排成队列逐个注入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!running) {
                TextButton(onClick = onPick) { Text("选目录", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPicker(
    state: BatchUiState,
    onSelect: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "用哪套配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Gap(8.dp)
            if (state.configs.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "还没有配置。批量注入也需要一份「注入什么」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenConfig) { Text("去新建") }
                }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = state.activeConfig?.name ?: "选一套配置",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                        supportingText = {
                            Text(
                                state.activeConfig?.let {
                                    "${it.callCount} 条 · ${it.mode.label} · " +
                                        if (it.hasDex) "dex ${it.dexSizeKb}KB" else "无 dex"
                                } ?: "同一套配置会应用到队列里的每一个包",
                            )
                        },
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        state.configs.forEach { cfg ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(cfg.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${cfg.callCount} 条 · ${cfg.mode.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingIcon = if (cfg.id == state.configId) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                                } else null,
                                onClick = {
                                    onSelect(cfg.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(state: BatchUiState) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${state.done}/${state.items.size}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                )
                HGap(8.dp)
                Text(
                    if (state.running) "进行中" else if (state.done == state.items.size && state.done > 0) "已完成" else "待开始",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                if (state.ok > 0) {
                    Text(
                        "成功 ${state.ok}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HGap(8.dp)
                }
                if (state.failed > 0) {
                    Text(
                        "失败 ${state.failed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Gap(10.dp)
            LinearProgressIndicator(
                progress = { if (state.done == 0 && !state.running) 0f else maxOf(state.progress, if (state.running) 0.02f else 0f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun QueueRow(item: BatchItem, running: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateBadge(item.state)
            HGap(10.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        item.packageName ?: item.name,
                        ApkFolderScanner.sizeText(item.size),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!running && item.state == BatchItem.State.QUEUED) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "移出队列",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: BatchItem.State) {
    val (color, icon) = when (state) {
        BatchItem.State.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant to null
        BatchItem.State.RUNNING -> MaterialTheme.colorScheme.tertiary to null
        BatchItem.State.OK -> MaterialTheme.colorScheme.primary to Icons.Rounded.Check
        BatchItem.State.FAILED -> MaterialTheme.colorScheme.error to Icons.Rounded.ErrorOutline
    }
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        } else {
            // 排队中是静止小点，运行中是稍大的点——不加动画，队列里几十行一起脉动会很吵
            Box(
                Modifier
                    .size(if (state == BatchItem.State.RUNNING) 9.dp else 6.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
private fun EmptyHint(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .size(28.dp),
            )
        }
        Gap(14.dp)
        Text("还没有队列", style = MaterialTheme.typography.titleMedium)
        Gap(4.dp)
        Text(
            "选一个目录，里面（含子目录）的 .apk 会被逐个识别并排队",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(16.dp)
        Button(onClick = onPick, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
            HGap(6.dp)
            Text("选择目录")
        }
    }
}

@Composable
private fun BottomBar(
    state: BatchUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.running) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.StopCircle, null, Modifier.size(18.dp))
                    HGap(6.dp)
                    Text("中止")
                }
            } else {
                if (state.items.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClear,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.height(50.dp),
                    ) { Text("清空") }
                }
                Button(
                    onClick = onStart,
                    enabled = state.items.isNotEmpty() && state.activeConfig != null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    HGap(6.dp)
                    Text(
                        if (state.done == state.items.size && state.done > 0) "全部重跑" else "开始注入",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

package com.xixin.apkforge.ui.screen.plugin

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.data.PluginPack
import com.xixin.apkforge.feature.plugin.PluginUiState
import com.xixin.apkforge.feature.plugin.PluginViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.components.rememberCopyText
import com.xixin.apkforge.ui.lifted.components.input.ToolInputField

/**
 * 插件包。
 *
 * 【这个页面在做什么】它不是「配置」，而是「造一份可以带走的载荷」：
 * 改弹窗的标题/正文/按钮 → 实时看到 JSON → 导出 zip（dex + 配置 + 调用行）。
 * 导出时顺带建一条配置，所以用户拿到的不是三个文件，而是「能直接注入的东西」。
 *
 * 【为什么页面里同时有表单和 JSON】本工程面向的是会去 MT 里翻 assets 的人，
 * 他会想知道「我填的这些到底变成了什么」。把 JSON 明明白白放在下面，
 * 比让他导出后再解压看文件要诚实得多。
 */
@Composable
fun PluginScreen(
    vm: PluginViewModel,
    onBack: () -> Unit = {},
) {
    val state = vm.state
    val copyText = rememberCopyText()

    Column(Modifier.fillMaxSize()) {
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
                        "插件包",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "改弹窗内容，导出可直接注入的 dex + 配置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::resetToDefault) {
                    Text("重置", style = MaterialTheme.typography.labelMedium)
                }
            }
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
            item { PackCard(state = state) }

            item {
                FormCard(state = state, vm = vm)
            }

            item {
                JsonCard(state = state, onCopy = copyText)
            }

            item {
                CallCard(state = state, onCopy = copyText)
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 10.dp)
            ) {
                Button(
                    onClick = { vm.export() },
                    enabled = !state.busy && state.form.valid,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(20.dp))
                    HGap(8.dp)
                    Text(
                        if (state.busy) "正在导出…" else "导出插件包",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                state.exportedZip?.let {
                    Text(
                        "已导出到 $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PackCard(state: PluginUiState) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("包里有什么", trailing = if (state.dexSize > 0) "${state.dexSize / 1024} KB dex" else null)
            Gap(10.dp)
            PackLine("PayloadDialog.dex", "弹窗载荷，注入后作为 classesN.dex 追加")
            PackLine("ApkForgeDialog.json", "写进宿主的 assets/，改文案不用重编 dex")
            PackLine("call.txt", "对应的 smali 调用行")
            Gap(10.dp)
            Text(
                "导出时还会顺手建一条配置，回到控制台就能选它直接注入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PackLine(name: String, desc: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        HGap(8.dp)
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormCard(state: PluginUiState, vm: PluginViewModel) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("弹窗内容")
            Gap(10.dp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用弹窗", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关掉后载荷仍会被注入，但不会显示任何东西",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.form.enabled, onCheckedChange = vm::setEnabled)
            }
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("点击空白关闭", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关掉后只能通过按钮关闭，适合强提示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.form.cancelable, onCheckedChange = vm::setCancelable)
            }

            Gap(12.dp)
            OutlinedTextField(
                value = state.form.title,
                onValueChange = vm::setTitle,
                label = { Text("标题") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(10.dp)
            ToolInputField(
                value = state.form.message,
                onValueChange = vm::setMessage,
                label = "正文",
                placeholder = "支持 <b>HTML</b> 标签，如 <br> 换行",
                maxHeight = 200.dp,
            )

            Gap(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "按钮",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.form.buttons.size < 3) {
                    TextButton(onClick = vm::addButton) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        HGap(4.dp)
                        Text("加一个", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Gap(6.dp)
            state.form.buttons.forEachIndexed { i, b ->
                ButtonEditor(
                    index = i,
                    button = b,
                    canRemove = state.form.buttons.size > 1,
                    onChange = { vm.setButton(i, it) },
                    onRemove = { vm.removeButton(i) },
                )
                if (i != state.form.buttons.lastIndex) Gap(8.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonEditor(
    index: Int,
    button: PluginPack.DialogButton,
    canRemove: Boolean,
    onChange: (PluginPack.DialogButton) -> Unit,
    onRemove: () -> Unit,
) {
    val slotName = when (button.slot) {
        0 -> "左（确定）"
        1 -> "右（取消）"
        else -> "中（中立）"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$slotName 按钮",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "删除按钮",
                        tint = if (canRemove) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Gap(4.dp)
            OutlinedTextField(
                value = button.text,
                onValueChange = { onChange(button.copy(text = it)) },
                label = { Text("文字") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(8.dp)
            ActionPicker(
                action = button.action,
                onSelect = { onChange(button.copy(action = it)) },
            )
            if (button.action == "url" || button.action == "custom") {
                Gap(8.dp)
                OutlinedTextField(
                    value = button.arg,
                    onValueChange = { onChange(button.copy(arg = it)) },
                    label = {
                        Text(if (button.action == "url") "网址" else "URI（如 myapp://page）")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Gap(8.dp)
            OutlinedTextField(
                value = button.toast,
                onValueChange = { onChange(button.copy(toast = it)) },
                label = { Text("点击提示（可空）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionPicker(action: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = PluginPack.ACTIONS.firstOrNull { it.first == action }?.second ?: "仅关闭"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("点击后") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            trailingIcon = {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PluginPack.ACTIONS.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun JsonCard(state: PluginUiState, onCopy: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    PluginPack.CONFIG_NAME,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopy(state.jsonc) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制 JSON",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Gap(8.dp)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        state.jsonc,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CallCard(state: PluginUiState, onCopy: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("调用行", modifier = Modifier.weight(1f))
                IconButton(onClick = { onCopy(PluginPack.CALL) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制调用行",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Gap(6.dp)
            SelectionContainer {
                Text(
                    PluginPack.CALL,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

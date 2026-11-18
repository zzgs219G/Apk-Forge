package com.xixin.apkforge.ui.screen.plugin

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.data.PluginPack
import com.xixin.apkforge.feature.plugin.PluginUiState
import com.xixin.apkforge.feature.plugin.PluginViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.components.input.ToolInputField
import com.xixin.apkforge.ui.lifted.components.rememberCopyText

/**
 * 插件包。
 *
 * 【这个页面在做什么】造一份可以带走的载荷：调弹窗的每一项 → 预览将被烘焙的配置 →
 * 导出 zip（**只有 dex 与调用行**，配置在 dex 里）→ 顺手建一条配置，回控制台就能注入。
 *
 * 【为什么字段这么多】上一版只做了「标题 + 正文 + 按钮文字」，用户的原话是
 * 「为什么你不复刻完整人家 3 个按钮」。对照清羽弹窗 7.0，一个弹窗的完整形态至少包含：
 * 总开关、智能弹出（24h）、静默天数、强制性、点击外部关闭、弹窗风格、背景透明、
 * 标题与正文（含颜色、HTML）、三个按钮各自的开关/文字/颜色/动作/参数/提示。
 * 少一项都算是「能力缩水」——用户手里的老配置会有一半字段无处安放。
 *
 * 【为什么不再有 json 文件】见 `PluginPack` 顶部注释。这里只留一个**只读预览**，
 * 让用户确认「我填的东西到底变成了什么」，导出物里不会有这个文件。
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
                        "调弹窗 → 导出 dex（配置已烘焙进去）",
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
            item { PackCard(state) }
            item { ContentCard(state, vm) }
            item { BehaviorCard(state, vm) }
            item { ButtonsCard(state, vm) }
            item { JsonCard(state, copyText) }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 10.dp)
            ) {
                state.blockers.forEach { b ->
                    Text(
                        "・$b",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.blockers.isNotEmpty()) Gap(6.dp)
                Button(
                    onClick = { vm.export() },
                    enabled = state.canExport,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(20.dp))
                    HGap(8.dp)
                    Text(
                        if (state.busy) "正在烘焙…" else "导出插件包",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                state.exportedZip?.let {
                    Gap(4.dp)
                    Text(
                        "已导出 $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ══════════════════════ 包里有什么 ══════════════════════

@Composable
private fun PackCard(state: PluginUiState) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(
                "包里有什么",
                trailing = when {
                    state.bakedSize > 0 -> "${state.bakedSize / 1024} KB（烘焙后）"
                    state.templateSize > 0 -> "${state.templateSize / 1024} KB（模板）"
                    else -> null
                },
            )
            Gap(10.dp)
            PackLine("PayloadDialog.dex", "弹窗载荷，注入后作为 classesN.dex 追加")
            PackLine("call.txt", "对应的 smali 调用行")
            Gap(10.dp)
            Text(
                // 这句是给用户的一个明确承诺：包里只多一个文件。上一版往 assets 写 json，
                // 用户当场就问「你把我包里的 assets 改了？」——这个质疑是对的。
                "配置已经烘焙进 dex。注入产物里只会多出 classesN.dex 一个条目，" +
                    "不会碰宿主的 assets，也不会多出任何配置文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(8.dp)
            Text(
                "代价：改文案要重新导出 dex 再重新注入一次。",
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

// ══════════════════════ 文案 ══════════════════════

@Composable
private fun ContentCard(state: PluginUiState, vm: PluginViewModel) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("文案")
            Gap(10.dp)

            OutlinedTextField(
                value = state.form.title,
                onValueChange = vm::setTitle,
                label = { Text("标题") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(8.dp)
            ColorField("标题颜色", state.form.titleColor, vm::setTitleColor)

            Gap(12.dp)
            ToolInputField(
                value = state.form.message,
                onValueChange = vm::setMessage,
                label = "正文",
                placeholder = "支持 <b>HTML</b>，<br> 换行；多行会自动分行",
                maxHeight = 200.dp,
            )
            Gap(8.dp)
            ColorField("正文字色", state.form.messageColor, vm::setMessageColor)
            Gap(6.dp)
            Text(
                "标题和正文至少写一个，否则弹出来是个空白框。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 颜色输入。
 *
 * 【为什么不放调色板】手写 `#rrggbb` 对目标用户（会改 smali、用 MT 的人）不是负担，
 * 而一个取色器要占掉半屏、还要处理 alpha。这里只做一件事：色块预览 + 明文输入。
 */
@Composable
private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    val parsed = remember(value) { parseHex(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val swatch = if (parsed != null) {
            Modifier.background(Color(parsed), MaterialTheme.shapes.small)
        } else {
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
        }
        Box(Modifier.size(28.dp).then(swatch))
        HGap(10.dp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null && value.isNotBlank(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun parseHex(v: String): Long? {
    val s = v.trim().removePrefix("#")
    if (s.length != 6) return null
    return s.toLongOrNull(16)?.let { 0xFF000000L or it }
}

// ══════════════════════ 行为 ══════════════════════

@Composable
private fun BehaviorCard(state: PluginUiState, vm: PluginViewModel) {
    val f = state.form
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("什么时候弹、长什么样")
            Gap(10.dp)

            SwitchRow(
                title = "启用弹窗",
                desc = "关掉后 dex 仍会被注入，但不会显示任何东西",
                checked = f.enabled,
                onChange = vm::setEnabled,
            )
            SwitchRow(
                title = "点击外部关闭",
                desc = "关掉后只能靠按钮关，适合强提示",
                checked = f.outsideClose,
                onChange = vm::setOutsideClose,
            )
            SwitchRow(
                title = "智能弹出（24 小时）",
                desc = "同一次安装里，弹出后 24 小时内不再弹",
                checked = f.smart,
                onChange = vm::setSmart,
            )
            SwitchRow(
                title = "强制性",
                desc = "返回键与外部点击都关不掉，只能点按钮。风险较高，慎用",
                checked = f.forced,
                onChange = vm::setForced,
            )
            SwitchRow(
                title = "背景透明",
                desc = "弹窗后面的页面不压暗",
                checked = f.dimBehind,
                onChange = vm::setDimBehind,
            )

            Gap(12.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("静默天数", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "安装后前 N 天不弹（0 = 不启用）。从首次运行算起",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = if (f.silentDays > 0) f.silentDays.toString() else "",
                    onValueChange = { vm.setSilentDays(it.filter(Char::isDigit).toIntOrNull() ?: 0) },
                    singleLine = true,
                    placeholder = { Text("关") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(width = 88.dp, height = 56.dp),
                )
            }

            Gap(12.dp)
            Picker(
                label = "弹窗风格",
                current = f.style,
                options = PluginPack.STYLES.map { it to it },
                onSelect = vm::setStyle,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HGap(8.dp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ══════════════════════ 三个按钮 ══════════════════════

@Composable
private fun ButtonsCard(state: PluginUiState, vm: PluginViewModel) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("三个按钮", trailing = "左 / 右 / 中")
            Gap(6.dp)
            Text(
                "位置固定：同意在左、拒绝在右、中立居中（AlertDialog 的约定）。" +
                    "开关关掉的按钮不会出现。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(10.dp)
            listOf(0, 1, 2).forEach { slot ->
                val b = state.form.buttons.firstOrNull { it.slot == slot }
                    ?: PluginPack.Button(slot = slot, on = false, text = "")
                ButtonEditor(button = b, onChange = { vm.setButton(slot, it) })
                if (slot != 2) Gap(8.dp)
            }
        }
    }
}

@Composable
private fun ButtonEditor(button: PluginPack.Button, onChange: (PluginPack.Button) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    PluginPack.slotLabel(button.slot),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = button.on,
                    onCheckedChange = { onChange(button.copy(on = it)) },
                )
            }
            if (!button.on) {
                Text(
                    "不显示这个按钮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Gap(6.dp)
            OutlinedTextField(
                value = button.text,
                onValueChange = { onChange(button.copy(text = it)) },
                label = { Text("文字") },
                singleLine = true,
                isError = button.text.isBlank(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(8.dp)
            Picker(
                label = "点击后",
                current = button.actionLabel,
                options = PluginPack.ACTIONS.map { it.second to it.first },
                onSelect = { onChange(button.copy(action = it)) },
            )
            if (button.needsArg) {
                Gap(8.dp)
                OutlinedTextField(
                    value = button.arg,
                    onValueChange = { onChange(button.copy(arg = it)) },
                    label = { Text(if (button.action == "网址") "网址" else "QQ 号") },
                    singleLine = true,
                    isError = button.arg.isBlank(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Gap(8.dp)
            OutlinedTextField(
                value = button.toast,
                onValueChange = { onChange(button.copy(toast = it)) },
                label = { Text("点击提示（可空）") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(8.dp)
            ColorField("按钮文字色", button.color, { onChange(button.copy(color = it)) })
        }
    }
}

// ══════════════════════ 配置预览 ══════════════════════

@Composable
private fun JsonCard(state: PluginUiState, onCopy: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("将被烘焙的配置", modifier = Modifier.weight(1f))
                IconButton(onClick = { onCopy(state.json) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制配置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Gap(6.dp)
            Text(
                "这份文本会被写进 dex 的 PayloadConfig.baked()，注入后不再有独立文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(8.dp)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        // 收起时只给前几行——但**不截断单行**，只是行数少。
                        // 省略号在这里是错的：用户要复制给 AI 的是完整配置。
                        if (expanded) state.json else state.json.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .let { if (expanded) it.heightIn(max = 320.dp) else it }
                            .let { if (expanded) it.verticalScroll(rememberScrollState()) else it }
                            .padding(10.dp),
                    )
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) "收起" else "展开全部 ${state.json.lines().size} 行",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ══════════════════════ 通用下拉 ══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (display, value) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

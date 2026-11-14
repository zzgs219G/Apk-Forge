package com.xixin.apkforge.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单行输入。移植思路来自 jian_box `wrapper/ui/components/input/ToolInputFields`，
 * 但用 BasicTextField 全自绘：M3 TextField 的浮动标签 + 内边距在深色底上会虚胖，
 * 且 `TextFieldDefaults` 的容器色与自定义色板冲突。
 */
@Composable
fun ForgeField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    trailing: String? = null,
    trailingColor: androidx.compose.ui.graphics.Color = Forge.Colors.accent,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dim.button)
            .clip(Forge.Shape.card)
            .background(Forge.Colors.surface)
            .border(1.dp, Forge.Colors.lineSoft, Forge.Shape.card)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = Forge.Type.code,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                textStyle = Forge.Type.code.copy(color = Forge.Colors.text),
                cursorBrush = SolidColor(Forge.Colors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = Forge.Type.code, color = trailingColor)
        }
    }
}

/**
 * 多行代码输入。左侧强调条 + 等宽字体，明确「这是给机器看的」。
 * 等宽 + 无行高压缩，保证多行 smali 能对齐阅读。
 */
@Composable
fun ForgeCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    minHeight: Dp = 120.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(Forge.Shape.card)
            .background(Forge.Colors.surface)
            .border(
                1.dp,
                if (isError) Forge.Colors.danger.copy(alpha = 0.5f) else Forge.Colors.lineSoft,
                Forge.Shape.card,
            ),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = minHeight)
                .background(
                    if (isError) Forge.Colors.danger else Forge.Colors.accent.copy(alpha = 0.5f)
                )
        )
        Box(Modifier.padding(horizontal = 13.dp, vertical = 13.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = Forge.Type.code, color = Forge.Colors.textFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Forge.Type.code.copy(color = Forge.Colors.text),
                cursorBrush = SolidColor(Forge.Colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 标签 + 输入的组合块，统一「标题在上、控件在下」的间距。 */
@Composable
fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    field: @Composable () -> Unit,
) {
    Column(modifier) {
        SectionLabel(label, trailing = trailingLabel)
        Spacer(Modifier.height(10.dp))
        field()
    }
}

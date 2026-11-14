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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 浮动选择面板。
 *
 * 【为什么不用 M3 DropdownMenu】它的定位是「能放下就居中，放不下就翻到另一侧」。
 * 锚点在屏幕右侧、菜单又宽时，菜单整个翻到左边——手指在右边点、选项从左边冒出来。
 *
 * 这里用 [Popup] 手动锚定，配合调用方给的 [Alignment] 决定贴哪边，
 * 并限制最大高度可滚动。
 */
@Composable
fun ForgePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    align: Alignment = Alignment.TopEnd,
    widthIn: Dp = 260.dp,
    maxHeight: Dp = 320.dp,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = align,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(min = widthIn, max = widthIn + 40.dp)
                .heightIn(max = maxHeight)
                .clip(Forge.Shape.card)
                .background(Forge.Colors.surfaceMax)
                .border(1.dp, Forge.Colors.line, Forge.Shape.card)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) { content() }
    }
}

/** 弹出项。 */
@Composable
fun ForgePopupItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    badge: String? = null,
    badgeColor: Color = Forge.Colors.accent,
    mono: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Forge.Colors.accent.copy(alpha = 0.10f) else Color.Transparent)
            .pressBounce(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (mono) Forge.Type.code else Forge.Type.body,
                color = when {
                    destructive -> Forge.Colors.danger
                    selected -> Forge.Colors.accent
                    else -> Forge.Colors.text
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = Forge.Type.caption,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            Text(badge, style = Forge.Type.label, color = badgeColor)
        }
    }
}

/**
 * 选项 chip。
 *
 * 【为什么用 chip 行而不是下拉】注入点方法这类选项数固定（≤9）、名字短，
 * 直接铺开会比「点开下拉 → 再选」少一次交互，且用户能一眼看到全部选项。
 *
 * @param badge 右侧小标记，如「新」表示该方法不存在、会被新建
 */
@Composable
fun ForgeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = Forge.Colors.amber,
) {
    Row(
        modifier
            .clip(Forge.Shape.chip)
            .background(
                if (selected) Forge.Colors.accent.copy(alpha = 0.15f) else Forge.Colors.surfaceHigh
            )
            .border(
                1.dp,
                if (selected) Forge.Colors.accent.copy(alpha = 0.55f) else Forge.Colors.lineSoft,
                Forge.Shape.chip,
            )
            .pressBounce(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = Forge.Type.microMono,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Forge.Colors.accent else Forge.Colors.textDim,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text(badge, style = Forge.Type.label, color = badgeColor)
        }
    }
}

/**
 * 流式 chip 布局：每行固定 [perRow] 个。
 * 不用 `FlowRow`（ExperimentalLayoutApi）——chip 数量与宽度都可预测，不需要通用流式。
 */
@Composable
fun <T> ForgeChipFlow(
    items: List<T>,
    perRow: Int = 3,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { content(it) }
            }
        }
    }
}

/**
 * 行式选择器：左侧文案 + 右侧值 + 箭头，整行可点弹出 [ForgePopup]。
 * 用于配置选择这类「值较长、需要预览」的场景。
 */
@Composable
fun ForgeSelectRow(
    title: String,
    value: String?,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentBorder: Boolean = false,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Forge.Shape.card)
            .background(Forge.Colors.surface)
            .border(
                1.dp,
                if (accentBorder) Forge.Colors.accent.copy(alpha = 0.4f) else Forge.Colors.lineSoft,
                Forge.Shape.card,
            )
            .pressBounce(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                value ?: title,
                style = if (value != null) Forge.Type.title else Forge.Type.body,
                color = if (value != null) Forge.Colors.text else Forge.Colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    style = Forge.Type.microMono,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingAction != null) {
            Spacer(Modifier.width(8.dp))
            trailingAction()
        }
        Spacer(Modifier.width(8.dp))
        Chevron(open = false)
    }
}

/**
 * 文件选择行：文件名 + 元信息 + 选择/清除。整行可点唤起选择器。
 */
@Composable
fun ForgeFileRow(
    label: String,
    detail: String?,
    filled: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    busyLabel: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Forge.Shape.card)
            .background(Forge.Colors.surface)
            .border(
                1.dp,
                if (filled) Forge.Colors.accent.copy(alpha = 0.35f) else Forge.Colors.lineSoft,
                Forge.Shape.card,
            )
            .pressBounce(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = if (filled) Forge.Type.code else Forge.Type.body,
                color = if (filled) Forge.Colors.text else Forge.Colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    style = Forge.Type.microMono,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            busyLabel != null -> ForgeStatus(busyLabel, StatusKind.WARN)
            onClear != null -> Box(
                Modifier
                    .clip(Forge.Shape.chip)
                    .pressBounce(onClick = onClear)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("清除", style = Forge.Type.label, color = Forge.Colors.textFaint)
            }
            else -> Text("选择", style = Forge.Type.label, color = Forge.Colors.accent)
        }
    }
}

/** 区块容器：统一上下留白，避免各页面自己算间距。 */
@Composable
fun ForgeSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) { content() }
}

/** 小间距常量，避免在各处写魔法数。 */
val GapXS = 4.dp
val GapS = 8.dp
val GapM = 12.dp
val GapL = 18.dp
val GapXL = 26.dp

/** 竖直间隔。 */
@Composable
fun VGap(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))

/** 水平间隔。 */
@Composable
fun HGap(w: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(w))

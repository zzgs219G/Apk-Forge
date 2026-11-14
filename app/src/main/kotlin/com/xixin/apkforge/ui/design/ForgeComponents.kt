package com.xixin.apkforge.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页面顶栏。
 *
 * 【为什么不用 M3 TopAppBar】M3 的 TopAppBar 自带高度、标题槽、actions 槽的固定布局，
 * 且 `TopAppBarDefaults` 的颜色在深色底上会额外叠一层 surface tint，与自定义色板打架。
 * 这里手绘一个 64dp 高的顶栏：左菜单键 + 标题/副标题 + 右侧状态槽，完全可控。
 *
 * 结构：
 * ```
 * ┌──────────────────────────────────────────┐
 * │ [≡]  标题                    [状态胶囊]  │
 * │      副标题                              │
 * ├──────────────────────────────────────────┤ ← HairLine
 * ```
 */
@Composable
fun ForgeTopBar(
    title: String,
    subtitle: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dim.topBar)
                .padding(horizontal = Dim.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 菜单键：两条横线，比 hamburger 图标更克制
            Box(
                Modifier
                    .size(40.dp)
                    .clip(Forge.Shape.chip)
                    .background(Forge.Colors.surfaceHigh)
                    .border(1.dp, Forge.Colors.line, Forge.Shape.chip)
                    .pressBounce(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(2) {
                        Box(
                            Modifier
                                .width(16.dp)
                                .height(1.5.dp)
                                .background(Forge.Colors.text)
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = Forge.Type.display,
                    color = Forge.Colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    style = Forge.Type.caption,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        HairLine()
    }
}

/**
 * 主按钮（实心）。移植自 jian_box `ToolPrimaryButton`，简化掉 loading 内嵌进度
 * （本项目进度由注入动画承担，按钮不需要再画一个）。
 */
@Composable
fun ForgePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
    color: Color = Forge.Colors.accent,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dim.button)
            .clip(Forge.Shape.card)
            .background(if (enabled && !loading) color else Forge.Colors.surfaceHigh)
            .pressBounce(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) (loadingText ?: text) else text,
            style = Forge.Type.title,
            fontWeight = FontWeight.SemiBold,
            color = when {
                loading -> color
                enabled -> Forge.Colors.accentInk
                else -> Forge.Colors.textFaint
            },
        )
    }
}

/** 次要按钮（线框）。 */
@Composable
fun ForgeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Forge.Colors.accent,
) {
    Box(
        modifier
            .height(Dim.button)
            .clip(Forge.Shape.card)
            .border(
                1.dp,
                if (enabled) color.copy(alpha = 0.45f) else Forge.Colors.line,
                Forge.Shape.card,
            )
            .pressBounce(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Forge.Type.body,
            fontWeight = FontWeight.Medium,
            color = if (enabled) color else Forge.Colors.textFaint,
        )
    }
}

/** 小尺寸操作药丸（结果区的安装/定位/分享）。 */
@Composable
fun ForgeSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Forge.Colors.accent,
    filled: Boolean = false,
) {
    Box(
        modifier
            .clip(Forge.Shape.chip)
            .background(if (filled) color else Color.Transparent)
            .border(
                1.dp,
                if (filled) Color.Transparent else color.copy(alpha = 0.4f),
                Forge.Shape.chip,
            )
            .pressBounce(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = Forge.Type.caption,
            fontWeight = FontWeight.Medium,
            color = if (filled) Forge.Colors.accentInk else color,
        )
    }
}

/**
 * 徽标（短文本 + 圆角底色）。移植自 jian_box `AppBadge`。
 *
 * 注意：这不是 material3 的 `Badge`（那是图标角标计数器），是行内标签。
 */
@Composable
fun ForgeBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
) {
    Row(
        modifier
            .clip(Forge.Shape.pill)
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(Modifier.size(4.dp).background(contentColor, CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = Forge.Type.label,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 状态徽标：预置四种语义色。 */
@Composable
fun ForgeStatus(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val (container, content) = when (kind) {
        StatusKind.OK -> Forge.Colors.accent.copy(alpha = 0.14f) to Forge.Colors.accent
        StatusKind.WARN -> Forge.Colors.amber.copy(alpha = 0.14f) to Forge.Colors.amber
        StatusKind.ERROR -> Forge.Colors.danger.copy(alpha = 0.14f) to Forge.Colors.danger
        StatusKind.IDLE -> Forge.Colors.surfaceMax to Forge.Colors.textFaint
    }
    ForgeBadge(text, container, content, modifier, showDot = kind != StatusKind.IDLE)
}

enum class StatusKind { OK, WARN, ERROR, IDLE }

/**
 * 操作行（图标 + 标题 + 说明，整行可点）。移植自 jian_box `AppActionRow` 的
 * PressBounceRow 分支——本项目只需要这一种形态，不做双分支。
 */
@Composable
fun ForgeActionRow(
    glyph: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    statusText: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .pressBounce(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标槽：32dp 圆角底，用字符而非 ImageVector
        // （本项目没有 material-icons-extended 依赖，且不希望为一个图标加 2MB）
        Box(
            Modifier
                .size(34.dp)
                .clip(Forge.Shape.chip)
                .background(
                    if (destructive) Forge.Colors.danger.copy(alpha = 0.15f)
                    else Forge.Colors.surfaceHigh
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = Forge.Type.caption,
                color = if (destructive) Forge.Colors.danger else Forge.Colors.accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = Forge.Type.body,
                fontWeight = FontWeight.Medium,
                color = when {
                    destructive -> Forge.Colors.danger
                    enabled -> Forge.Colors.text
                    else -> Forge.Colors.textFaint
                },
            )
            if (description != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    description,
                    style = Forge.Type.caption,
                    color = Forge.Colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (statusText != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText,
                    style = Forge.Type.label,
                    color = if (enabled) Forge.Colors.accent else Forge.Colors.danger,
                )
            }
        }
    }
}

/** 分隔线。 */
@Composable
fun HairLine(modifier: Modifier = Modifier, color: Color = Forge.Colors.lineSoft) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/** 区块标题：竖条 + 全大写 + 字距 + 右侧可选计数。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(11.dp)
                .background(Forge.Colors.accent, Forge.Shape.pill)
        )
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = Forge.Type.label, color = Forge.Colors.textDim)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = Forge.Type.microMono, color = Forge.Colors.textFaint)
        }
    }
}

/** 大号读数 + 小标签。 */
@Composable
fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Forge.Colors.text,
) {
    Column(modifier) {
        Text(value, style = Forge.Type.metric, color = color, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), style = Forge.Type.label, color = Forge.Colors.textFaint)
    }
}

/** 键值行。 */
@Composable
fun KVRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Forge.Colors.text,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            key.uppercase(),
            style = Forge.Type.label,
            color = Forge.Colors.textFaint,
            modifier = Modifier.width(84.dp).padding(top = 2.dp),
        )
        Text(
            value,
            style = Forge.Type.microMono,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 终端块：左侧强调条 + 等宽内容。 */
@Composable
fun TerminalBlock(
    modifier: Modifier = Modifier,
    accent: Color = Forge.Colors.accent,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Forge.Shape.data)
            .background(Forge.Colors.surfaceHigh)
            .border(1.dp, Forge.Colors.lineSoft, Forge.Shape.data),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 36.dp)
                .background(accent.copy(alpha = 0.7f))
        )
        Box(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) { content() }
    }
}

/** 折叠区标题（带箭头）。 */
@Composable
fun CollapsibleHeader(
    title: String,
    trailing: String?,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressBounce(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(title, Modifier.weight(1f), trailing)
        Spacer(Modifier.width(8.dp))
        Chevron(open)
    }
}

/** 展开箭头。 */
@Composable
fun Chevron(open: Boolean, color: Color = Forge.Colors.textFaint) {
    val rot by animateFloatAsState(
        if (open) 180f else 0f,
        tween(Motion.DUR_FAST),
        label = "chevron",
    )
    Text(
        "▾",
        style = Forge.Type.caption,
        color = color,
        modifier = Modifier.rotate(rot),
    )
}

/** 折叠容器。 */
@Composable
fun Collapsible(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(Motion.Spring.ExpandContent) + fadeIn(tween(Motion.DUR_FAST)),
        exit = shrinkVertically(tween(Motion.DUR_EXIT)) + fadeOut(tween(Motion.DUR_FAST)),
    ) { content() }
}

/** 空状态。 */
@Composable
fun ForgeEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Forge.Type.title, color = Forge.Colors.textDim)
        Spacer(Modifier.height(7.dp))
        Text(
            detail,
            style = Forge.Type.caption,
            color = Forge.Colors.textFaint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 卡片容器。统一圆角/底色/描边，避免各处手写。 */
@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(Forge.Shape.card)
            .background(Forge.Colors.surface)
            .border(
                1.dp,
                accentBorder?.copy(alpha = 0.4f) ?: Forge.Colors.lineSoft,
                Forge.Shape.card,
            )
    ) { content() }
}

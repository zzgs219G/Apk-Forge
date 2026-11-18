package com.xixin.apkforge.ui.lifted.components.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.design.AppRadius
import com.xixin.apkforge.ui.lifted.m3e.M3E
import com.xixin.apkforge.ui.lifted.m3e.pressBounce
import com.xixin.apkforge.ui.lifted.m3e.pressBounceToggleable

/**
 * 卡内「行」的统一承载者 —— 本工程所有可点/可选项的唯一形态。
 *
 * 移植自 `克隆仓库x/jian_box` 的 `CreateTaskComponents.kt::CheckRow`，
 * 并按 `docs/UI设计规范.md` §3.3 对齐选中态。
 *
 * ## 阻尼从哪来（这一条是本组件存在的核心理由）
 *
 * ⚠️ 「有动画」不等于「有阻尼」。`MotionScheme` 的 spatial/effects 槽位是
 * **贝塞尔缓动**（有加减速，但**没有刚度、没有阻尼比、没有过冲**）。
 * 真正的物理回弹只能由 `spring(dampingRatio, stiffness)` 驱动。
 *
 * 所以本组件的状态变化走**双轨**：
 *  · **颜色**（底色/字色）→ `animateColorAsState` + `fastEffectsSpec()`
 *    （颜色不该弹，弹起来像故障）
 *  · **尺寸/位移**（指示器缩放、chevron 旋转、展开区高度）→ `M3E.Spring.*`
 *    （弹簧物理，有阻尼与过冲）
 *
 * 三条关键裁决照抄 jian_box 备查：
 *
 * **① 选中态用 `secondaryContainer` 不透明底，不用 `primaryContainer`。**
 *   规范 §3.3：`primary` 是**强调色**，页面只留给唯一 CTA；
 *   把选中铺成 `primaryContainer` 会让整页「全是主操作」，反而失焦。
 *
 * **② 未选中态 `Color.Transparent` 底，不铺 `surfaceContainerHigh`。**
 *   否则与「次级子项」语义撞车，且行行有色块，卡片内又变回一堆小卡片。
 *
 * **③ 不加竖条。** jian_box 曾加 3dp 竖条，后按用户裁决删除——装饰冒充信息。
 *
 * @param role `Role.RadioButton` 单选 / `Role.Checkbox` 多选 / `null` 表示这不是选项，
 *   只是一行可点的动作（右侧出 `>` 箭头而非指示器）
 * @param expanded 展开态（仅当 [expandContent] 非空时有效）
 * @param expandContent 行内展开区。挂 `animateContentSize(M3E.Spring.ExpandContent)`，
 *   子内容用普通 `if` 即可——高度由父容器弹性跟随，不会闪变
 */
@Composable
fun AppRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 行尾等宽技术摘要（大小、计数、文件名） */
    meta: String? = null,
    selected: Boolean = false,
    role: Role? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
    expandContent: (@Composable () -> Unit)? = null,
) {
    // ── 颜色：缓动，不弹 ──
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "AppRowContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "AppRowContent",
    )
    val subColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "AppRowSub",
    )

    // ── 尺寸/旋转：弹簧，有阻尼 ──
    // 圆角随选中态在 shapes.small ↔ shapes.medium 之间 morph：
    // 未选中是一行字，选中后浮起一块，圆角收紧一档。用 spring 而非 tween，
    // 收尾才有那一点点"咬合"感——这是 M3E 与普通 M3 的观感分界。
    //
    // ⚠️ 目标值必须走 AppRadius，不能写 12.dp / 18.dp 字面量：
    //    animateDpAsState 要的是 Dp（Shape 反解不出数值），所以动画只能用 Dp 形式；
    //    但数值必须与 AppShapes 同源，否则档位一改就出现「静态 24dp、动画 18dp」的双轨。
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) AppRadius.small else AppRadius.medium,
        animationSpec = M3E.Spring.Corner,
        label = "AppRowCorner",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = M3E.Spring.Standard,
        label = "AppRowChevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 展开区高度弹性跟随内容。子内容用普通 if 不会闪变——
            // 高度由这里动，不是子内容自己裁剪。
            .then(
                if (expandContent != null) {
                    Modifier.animateContentSize(M3E.Spring.ExpandContent)
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
                .background(containerColor)
                .then(
                    when {
                        onClick == null || !enabled -> Modifier
                        // 选项行走 toggleable：向 TalkBack 暴露 checked 状态；
                        // 普通动作行走 clickable。两者都挂 pressBounce 缩放反馈。
                        role != null -> Modifier.pressBounceToggleable(
                            value = selected,
                            role = role,
                            targetScale = 0.98f,
                        ) { onClick() }
                        else -> Modifier.pressBounce(targetScale = 0.98f, onClick = onClick)
                    }
                )
                // §3.4：父行 56dp、子项 48dp
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }

            if (role != null) {
                // onCheckedChange / onClick 传 null：点击统一由整行处理
                if (role == Role.RadioButton) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                } else {
                    Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                }
                Spacer(Modifier.width(4.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.size(1.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = subColor,
                    maxLines = 1,
                )
            }

            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }

            if (role == null && onClick != null) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = subColor,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }

        if (expandContent != null && expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp, end = 16.dp, bottom = 12.dp)
            ) {
                expandContent()
            }
        }
    }
}

/**
 * 卡内「弹出选项」的摘要行。
 *
 * 与 [AppRow] 的区别：它右侧是 `⌄`（旋转 90° 的 chevron），表示「点开有面板」
 * 而不是「进入另一页」。这是 M3 里两种不同的导航语义，不可混用。
 *
 * **行本身永远固定高度**：展开在 bottom sheet 里发生。
 * 上一版把它做成手风琴（就地展开），展开会把下面内容整体顶下去、页面高度跳变，
 * 用户原话「屏幕直接下拉出 UI 选项」「下拉抽屉」——那是网页 FAQ 的形态。
 */
@Composable
fun AppSummaryRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 当前待办项：标题转 primary 色。全页最多一处，且不加竖条、不加底色 */
    pending: Boolean = false,
    warn: Boolean = false,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = when {
            pending -> MaterialTheme.colorScheme.primary
            warn -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "SummaryTitle",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (onClick != null) {
                    Modifier.pressBounce(targetScale = 0.985f, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                ),
                fontWeight = FontWeight.Medium,
                color = if (warn) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.size(1.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        // 旋转 90° = 「向下展开」，与 AppRow 的「进入」区分
                        .rotate(90f),
                )
            }
        }
    }
}

package com.xixin.apkforge.ui.lifted.m3e

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 通用 M3 缩放按压反馈积木：替代各页面手工声明的
 * interactionSource + animateFloatAsState + graphicsLayer 样板代码。
 *
 * @param targetScale 按下时的缩放目标值
 * @param onLongClick 可选长按回调（存在时内部使用 combinedClickable，兼容工具箱长按固定等场景）
 * @param onClick 点击回调（置于末位以支持 trailing lambda 写法）
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun Modifier.pressBounce(
    targetScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scaled = pressBounceScale(targetScale, interactionSource)
    if (onLongClick != null) {
        scaled.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        scaled.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    }
}

/**
 * 带「勾选语义」的缩放按压积木：与 [pressBounce] 共用同一缩放物理
 * （同参数、同 `fastSpatialSpec`），差别只在底层手势用 `toggleable` 而非 `clickable`。
 *
 * 为什么必须单独存在：`clickable` 无法向 TalkBack 暴露 `checked` 状态，
 * 勾选/单选项用 `clickable` 读屏时只会读成普通按钮（"双击激活"），
 * 用户在读屏下无从判断该项是否已选。`toggleable(value, role)` 才会播报"已选中 / 未选中"。
 *
 * 用法（整行可点的勾选行，不必精准点到复选框）：
 * ```
 * Row(Modifier.pressBounceToggleable(checked, Role.Checkbox) { onToggle(it) }) { … }
 * ```
 *
 * @param value 当前勾选状态（同时决定读屏播报与语义树中的 checked）
 * @param role [Role.Checkbox] 多选行 / [Role.RadioButton] 单选行
 * @param targetScale 按下时的缩放目标值，默认与 [pressBounce] 一致
 * @param onValueChange 勾选变化回调（置于末位以支持 trailing lambda 写法）
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun Modifier.pressBounceToggleable(
    value: Boolean,
    role: Role = Role.Checkbox,
    targetScale: Float = 0.96f,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    pressBounceScale(targetScale, interactionSource).toggleable(
        value = value,
        interactionSource = interactionSource,
        indication = null,   // 缩放本身即反馈，再叠涟漪会双层反馈
        enabled = enabled,
        role = role,
        onValueChange = onValueChange
    )
}

/**
 * 缩放物理本体（[pressBounce] 与 [pressBounceToggleable] 共用，保证两处手感完全一致）。
 * 动画槽位与既有实现一字未改：`fastSpatialSpec()`。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.pressBounceScale(
    targetScale: Float,
    interactionSource: MutableInteractionSource
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        // 原写法 spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)（= 0.5 / 400）
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressBounceScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

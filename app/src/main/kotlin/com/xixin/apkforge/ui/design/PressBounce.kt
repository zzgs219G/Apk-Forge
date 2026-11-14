package com.xixin.apkforge.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 缩放按压反馈。移植自 jian_box `wrapper/ui/m3e/PressBounceModifier.kt`。
 *
 * 为什么必须用它而不是 `Modifier.clickable`：
 *  - 本项目视觉语言偏「硬件」，涟漪（ripple）在这个调性里是噪音
 *  - 缩放本身即反馈，且能在深色底上清晰可见
 *
 * 注意 `indication = null`：缩放已经是反馈，再叠涟漪就是双层反馈。
 *
 * @param targetScale 按下时缩放目标值，默认 [Motion.PRESS_SCALE]
 * @param onLongClick 可选长按（存在时内部走 combinedClickable）
 * @param onClick 点击回调（置于末位以支持 trailing lambda）
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun Modifier.pressBounce(
    targetScale: Float = Motion.PRESS_SCALE,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scaled = pressBounceScale(targetScale, interactionSource)
    if (onLongClick != null) {
        scaled.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        scaled.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

/**
 * 带勾选语义的按压反馈。
 *
 * 为什么必须单独存在：`clickable` 无法向 TalkBack 暴露 `checked` 状态，
 * 勾选/单选项用 `clickable` 读屏只会读成普通按钮，用户无从判断是否已选。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun Modifier.pressBounceToggleable(
    value: Boolean,
    role: Role = Role.Checkbox,
    targetScale: Float = Motion.PRESS_SCALE,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    pressBounceScale(targetScale, interactionSource).toggleable(
        value = value,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onValueChange = onValueChange,
    )
}

/** 缩放物理本体，`pressBounce` 与 `pressBounceToggleable` 共用，保证手感一致。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.pressBounceScale(
    targetScale: Float,
    interactionSource: MutableInteractionSource,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = Motion.Spring.Press,
        label = "pressBounceScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

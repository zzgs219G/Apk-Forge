package com.xixin.apkforge.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 注入动画 —— v1「环形」版（Canvas 手绘，非 SVG）。
 *
 * 三层叠加，靠速度差产生「齿轮咬合」的层次感：
 *  1. 外圈：sweepGradient 渐变弧，匀速正转
 *  2. 中圈：两段短弧，反向旋转（与内圈速度不同 → 视觉层次）
 *  3. 内圈：脉冲呼吸的实心圆点 + 三颗绕行轨道粒子
 *
 * 零依赖：不用 Lottie/SVG 库，全部用 Compose Canvas 现算，
 * 颜色跟 MaterialTheme 自动联动。
 *
 * 与 v2 [InjectArtAnimation] 的区别：
 *  - v1 是「纯程序化几何」，观感干净、转起来有机械感
 *  - v2 是「真实 SVG path 描边生长」，有具象的图形叙事（六边形/箭头）
 * 两者都保留，由 [InjectAnimationStyle] 选择。
 */
@Composable
fun InjectSpinner(
    phaseLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    color: Color,
    trackColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "spin-v1")

    val outer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "outer",
    )
    val middle by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "middle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.055f
            val r = this.size.minDimension / 2 - stroke

            // 底轨
            drawCircle(
                color = trackColor,
                radius = r,
                style = Stroke(width = stroke * 0.7f),
            )

            // 外圈：旋转的渐变弧
            rotate(outer) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(color.copy(alpha = 0.05f), color, color)
                    ),
                    startAngle = 0f,
                    sweepAngle = 200f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // 中圈：反向短弧，两段不等长制造层次
            val r2 = r * 0.66f
            rotate(middle) {
                drawArc(
                    color = color.copy(alpha = 0.55f),
                    startAngle = 0f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - r2, center.y - r2),
                    size = Size(r2 * 2, r2 * 2),
                    style = Stroke(width = stroke * 0.6f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color.copy(alpha = 0.3f),
                    startAngle = 180f,
                    sweepAngle = 40f,
                    useCenter = false,
                    topLeft = Offset(center.x - r2, center.y - r2),
                    size = Size(r2 * 2, r2 * 2),
                    style = Stroke(width = stroke * 0.6f, cap = StrokeCap.Round),
                )
            }

            // 内点：脉冲
            drawCircle(
                color = color.copy(alpha = pulse),
                radius = r * 0.18f * (0.8f + pulse * 0.4f),
            )

            // 轨道粒子
            val rr = r * 0.9f
            repeat(3) { i ->
                val a = Math.toRadians((orbit + i * 120f).toDouble())
                drawCircle(
                    color = color.copy(alpha = 0.9f - i * 0.25f),
                    radius = stroke * 0.5f,
                    center = Offset(
                        center.x + (rr * cos(a)).toFloat(),
                        center.y + (rr * sin(a)).toFloat(),
                    ),
                )
            }
        }
    }
}

/** 动画风格选择。改这里即可切换注入中的动画表现。 */
enum class InjectAnimationStyle {
    /** v1 环形（Canvas 手绘） */
    RING,

    /** v2 六边形（真实 SVG path 描边生长） */
    SVG_HEX,
}

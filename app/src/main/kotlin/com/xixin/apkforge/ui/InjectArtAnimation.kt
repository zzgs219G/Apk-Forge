package com.xixin.apkforge.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 注入过程的动画组件。
 *
 * 【设计取向】不用「一个圆圈转」——那是 demo 观感。
 * 这里用**真实 SVG path**（六边形轨道 + 内部注入箭头）做描边生长，
 * 再叠加轨道粒子与脉冲核心，形成「数据正被写入」的叙事感。
 *
 * SVG path 全部写在 [PATHS] 里，是标准 SVG `d` 语法（120x120 viewBox），
 * 可被任何 SVG 编辑器打开验证。解析见 [SvgPath.parse]。
 */
object InjectArt {

    const val VIEW = 120f

    /** 外六边形轨道（顺时针，起点在正上方） */
    val HEX = "M60,8 L104,34 L104,86 L60,112 L16,86 L16,34 Z"

    /** 内六边形（逆时针，用于反向描边） */
    val HEX_INNER = "M60,26 L88,42 L88,78 L60,94 L32,78 L32,42 Z"

    /** 注入箭头：从中心向右下插入 */
    val ARROW = "M48,60 L60,60 M54,54 L60,60 L54,66"

    /** 三个数据节点 */
    val NODE_A = "M60,44 a4,4 0 1,0 0.1,0"
    val NODE_B = "M40,72 a4,4 0 1,0 0.1,0"
    val NODE_C = "M80,72 a4,4 0 1,0 0.1,0"
}

/**
 * 主动画：三阶段依次点亮（轨道 → 内环 → 箭头/节点），整体循环。
 *
 * @param phaseProgress 0..1，由调用方按阶段推进；传 -1 表示自动无限循环
 */
@Composable
fun InjectArtAnimation(
    phaseLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    dimColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    showLabel: Boolean = true,
) {
    val t = rememberInfiniteTransition(label = "inject-art")

    val hexGrow by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "hex",
    )
    val innerSpin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "inner",
    )
    val arrowGrow by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "arrow",
    )
    val pulse by t.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val orbit by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit",
    )

    // 路径只解析一次，动画帧里复用（每帧重新 parse 会明显掉帧）
    val hexPath: Path = remember { SvgPath.parse(InjectArt.HEX, Size(InjectArt.VIEW, InjectArt.VIEW), 1f) }
    val innerPath: Path = remember { SvgPath.parse(InjectArt.HEX_INNER, Size(InjectArt.VIEW, InjectArt.VIEW), 1f) }
    val arrowPath: Path = remember { SvgPath.parse(InjectArt.ARROW, Size(InjectArt.VIEW, InjectArt.VIEW), 1f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val k = this.size.minDimension / InjectArt.VIEW
                val cx = this.size.width / 2
                val cy = this.size.height / 2
                val sw = 2.4f * k

                // 所有路径按 viewBox 设计，统一缩放到画布
                scale(k, k, pivot = Offset.Zero) {
                    with(SvgPath) {
                        // 外六边形：暗底轨 + 按进度描亮
                        drawPath(hexPath, dimColor, style = Stroke(sw, cap = StrokeCap.Round))
                        drawPartial(hexPath, hexGrow, color, sw)

                        // 内六边形：反向旋转，制造层次
                        rotate(
                            -innerSpin * 0.30f,
                            pivot = Offset(InjectArt.VIEW / 2, InjectArt.VIEW / 2)
                        ) {
                            drawPath(
                                innerPath,
                                dimColor.copy(alpha = 0.12f),
                                style = Stroke(sw * 0.62f)
                            )
                            drawPartial(
                                innerPath, (innerSpin % 150f) / 150f,
                                color.copy(alpha = 0.55f), sw * 0.62f
                            )
                        }

                        // 注入箭头：来回生长
                        drawPartial(arrowPath, arrowGrow, color, sw * 1.1f)
                    }
                }

                // 中心核心与轨道粒子用屏幕坐标直接画（不受 viewBox 缩放影响）
                drawCircle(
                    color = color.copy(alpha = 0.10f * pulse),
                    radius = this.size.minDimension * 0.19f,
                )
                drawCircle(
                    color = color.copy(alpha = 0.85f * pulse),
                    radius = this.size.minDimension * 0.040f,
                )

                val orbitR = this.size.minDimension * 0.055f
                repeat(3) { i ->
                    val a = Math.toRadians((orbit + i * 120f).toDouble())
                    drawCircle(
                        color = color.copy(alpha = 0.20f + 0.6f * pulse),
                        radius = sw * 0.75f,
                        center = Offset(
                            cx + (orbitR * cos(a)).toFloat(),
                            cy + (orbitR * sin(a)).toFloat(),
                        ),
                    )
                }
            }
        }

        if (showLabel && phaseLabel.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                phaseLabel,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = color,
            )
        }
    }
}

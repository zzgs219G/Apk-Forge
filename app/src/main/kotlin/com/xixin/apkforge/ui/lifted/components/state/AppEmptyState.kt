package com.xixin.apkforge.ui.lifted.components.state

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空状态。
 *
 * ## 为什么不能只写一行灰字
 *
 * 上一版把控制台空态做成「还没有任务。产物会出现在 Download/ApkForge。」一行小字，
 * 理由是「不该让产品对自己说话」。这话只对了一半——**删掉解释，也删掉了生气**：
 * 一片纯黑上飘一行 13sp 灰字，用户第一眼看到的是「这 app 没做完」。
 *
 * 空态要回答三件事，一件都不能省：
 *   ① **这是什么**（标题）
 *   ② **为什么现在是空的**（一句说明，不超过一行）
 *   ③ **我现在能做什么**（操作入口，由调用方给——本组件只留槽位）
 *
 * ## 呼吸感从哪来
 *
 * 品牌六边形做**极慢的透明度呼吸**（3.2s 一个来回，`RepeatMode.Reverse`）。
 * 关键是"极慢"：快于 1.5s 会让人焦虑（像加载卡住），慢到 3s 以上才是「活着」。
 * 这里刻意不给缩放/旋转——空态不该抢注意力，它只是让画面别像死了。
 *
 * 常量说明：`RepeatMode.Reverse` + `tween` 是无状态周期动画的标准写法，
 * 不属于规范 §2.5 黑名单第 1 条（那条针对的是「一次性手填、不可复用的过渡参数」）。
 */
@Composable
fun AppEmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    // 呼吸：0.55 ↔ 1.0，3200ms 单程。慢到这个程度才像"活着"而不是"在加载"。
    val breath by rememberInfiniteTransition(label = "emptyBreath").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "emptyBreathAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ForgeMark(
            modifier = Modifier.size(76.dp),
            alpha = breath,
        )
        Column(
            Modifier.padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (action != null) {
            Box(Modifier.padding(top = 22.dp)) { action() }
        }
    }
}

/**
 * 品牌标记：六边形 + 穿透它的注入箭头。
 *
 * 与运行页动画同源（`ui/anim` 的六边形），但这里只描边、不做描边生长——
 * 空态需要的是一个**静止的标记**当视觉锚点，唯一的动是 [AppEmptyState] 给的呼吸。
 */
@Composable
private fun ForgeMark(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val color = MaterialTheme.colorScheme.primary
    // 描边与箭头的透明度错开，让图形有层次而不是一块糊
    val outlineAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "forgeMarkOutline",
    )

    Canvas(modifier) {
        val w = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = w * 0.40f

        // 外六边形
        val hex = Path()
        repeat(6) { i ->
            val a = Math.toRadians((-90 + i * 60).toDouble())
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        drawPath(
            path = hex,
            color = color.copy(alpha = 0.22f * outlineAlpha),
            style = Stroke(width = w * 0.045f, join = StrokeJoin.Round),
        )

        // 注入箭头
        val arrow = Path().apply {
            moveTo(cx - r * 0.55f, cy - r * 0.55f)
            lineTo(cx + r * 0.22f, cy + r * 0.22f)
            moveTo(cx + r * 0.22f, cy - r * 0.10f)
            lineTo(cx + r * 0.22f, cy + r * 0.22f)
            lineTo(cx - r * 0.10f, cy + r * 0.22f)
        }
        drawPath(
            path = arrow,
            color = color.copy(alpha = outlineAlpha),
            style = Stroke(
                width = w * 0.055f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // 落点光斑
        drawCircle(
            color = color.copy(alpha = 0.85f * outlineAlpha),
            radius = w * 0.032f,
            center = Offset(cx + r * 0.52f, cy + r * 0.52f),
        )
        drawCircle(
            color = color.copy(alpha = 0.30f * outlineAlpha),
            radius = w * 0.068f,
            center = Offset(cx + r * 0.52f, cy + r * 0.52f),
        )
    }
}

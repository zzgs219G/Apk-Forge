package com.xixin.apkforge.ui.lifted.m3e.loading

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.apkforge.ui.lifted.m3e.M3E
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material 3 Expressive (M3E) 波浪环下载进度指示器
 *
 * 还原 Google M3E "Download progress" 核心设计语言：
 * 一条波浪线沿着圆周做径向正弦起伏，并持续游动旋转 360°（液态波纹感）。
 * - 细圆环为轨道（track）
 * - 进度弧是一条波浪线（wave line），波浪相位无限旋转 → 视觉上整条波浪环围绕圆心游动
 * - 波浪弧明显粗于轨道，凸显 M3E 液态层次
 * - 中心实时百分比；进度变化使用弹性 spring，符合 M3E 物理回弹特性
 * - progress = 1f 时切换为完成勾；progress = null 时为不确定模式（波浪弧整体旋转）
 *
 * @param progress 下载进度 0f..1f；null 表示不确定（正在准备/解析阶段）
 * @param waveCount 整圈波浪数量（一个波浪 = 一个正弦波峰+波谷）
 * @param waveAmplitude 径向波浪振幅（相对进度弧线宽的倍数）
 * @param waveCycleMs 波浪游动旋转一圈的耗时（毫秒）
 */
@Composable
fun M3ExpressiveDownloadProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    // 轨道线宽随 size 自适应缩放：小尺寸（如按钮内 24dp）自动变细，避免波浪挤成一团
    strokeWidth: Dp = size * 0.045f,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    finishedIcon: ImageVector = Icons.Rounded.Check,
    waveCount: Int = M3E.Download.WaveCount,
    waveAmplitude: Float = M3E.Download.WaveAmplitude,
    waveCycleMs: Int = M3E.Download.WaveCycleMs
) {
    // 进度弹簧平滑（M3E 弹性质感：回弹略超调再稳定）
    val target = if (progress == null) 0.75f else progress.coerceIn(0f, 1f)
    val filled by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "download_progress"
    )

    // 波浪相位：无限循环 → 波浪线沿圆周游动旋转 360°
    val infiniteTransition = rememberInfiniteTransition(label = "m3e_download_wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(waveCycleMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    // 不确定模式：整条波浪弧绕圆心旋转（类似 spinner）
    val spinPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_spin"
    )

    val isIndeterminate = progress == null
    val isDone = progress != null && progress >= 1f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = this.center
            val radius = this.size.minDimension / 2f
            val trackStroke = strokeWidth.toPx()
            val waveStroke = trackStroke * 1.7f
            val amp = waveStroke * waveAmplitude
            val waveFrequency = waveCount.toFloat()
            val phaseRad = wavePhase * 2f * PI.toFloat()

            // 1. 轨道：细圆环
            drawCircle(
                color = trackColor,
                radius = radius,
                center = center,
                style = Stroke(width = trackStroke)
            )

            // 2. 波浪进度弧：沿圆周的径向正弦起伏线，相位无限旋转
            //    （从顶部 -90° 开始，顺时针随进度扫过 arcSpan；coerce 防止弹簧超调导致弧超过一整圈回绕）
            val arcSpan = filled.coerceIn(0f, 1f) * 2f * PI.toFloat()
            val startAngle = -PI.toFloat() / 2f + if (isIndeterminate) spinPhase * 2f * PI.toFloat() else 0f
            val steps = 96
            val wavePath = Path()
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val angle = startAngle + arcSpan * t
                // 径向正弦起伏：波浪线沿圆周游动
                val waveR = radius + amp * sin(waveFrequency * angle + phaseRad)
                val px = center.x + waveR * cos(angle)
                val py = center.y + waveR * sin(angle)
                if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
            }
            drawPath(
                path = wavePath,
                color = fillColor,
                style = Stroke(width = waveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 3. 中心内容：完成勾 / 百分比（不确定模式不显示中心内容）
        when {
            isDone -> Icon(
                imageVector = finishedIcon,
                contentDescription = "下载完成",
                tint = fillColor,
                modifier = Modifier.size(size * 0.34f)
            )

            else -> Text(
                text = "${((progress ?: 0f) * 100).roundToInt()}",
                fontSize = size.value.sp * 0.3f,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

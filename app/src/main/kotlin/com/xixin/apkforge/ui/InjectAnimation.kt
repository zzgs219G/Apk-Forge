package com.xixin.apkforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 注入动画的统一入口：按 [style] 分派到 v1 环形或 v2 SVG 六边形。
 *
 * 之所以保留两版并存而不是二选一：
 *  - v1 环形程序化几何，干净、机械感强，小尺寸下更清楚
 *  - v2 SVG 描边生长，图形叙事更具体（轨道/箭头），大尺寸观感更「重」
 * 两种取向没有客观优劣，交给用户按喜好选，成本只是多一个 `when`。
 */
@Composable
fun InjectAnimation(
    style: InjectAnimationStyle,
    phaseLabel: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 116.dp,
) {
    when (style) {
        InjectAnimationStyle.RING -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = modifier
            ) {
                InjectSpinner(
                    phaseLabel = phaseLabel,
                    size = size,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    phaseLabel,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        InjectAnimationStyle.SVG_HEX -> {
            InjectArtAnimation(
                phaseLabel = phaseLabel,
                modifier = modifier,
                size = size,
            )
        }
    }
}

/** 风格切换控件（放在界面上让用户自己挑）。 */
@Composable
fun AnimationStylePicker(
    current: InjectAnimationStyle,
    onChange: (InjectAnimationStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "动画",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        InjectAnimationStyle.entries.forEach { s ->
            val active = s == current
            Surface(
                onClick = { onChange(s) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Text(
                    when (s) {
                        InjectAnimationStyle.RING -> "环形"
                        InjectAnimationStyle.SVG_HEX -> "六边形"
                    },
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 小尺寸预览，让用户在点之前就看到效果
        InjectAnimationSmallPreview(current)
    }
}

@Composable
private fun InjectAnimationSmallPreview(style: InjectAnimationStyle) {
    when (style) {
        InjectAnimationStyle.RING -> InjectSpinner(
            phaseLabel = "",
            size = 26.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        InjectAnimationStyle.SVG_HEX -> InjectArtAnimation(
            phaseLabel = "",
            size = 38.dp,
            showLabel = false,
        )
    }
}

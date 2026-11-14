package com.xixin.apkforge.ui.lifted

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 极小的布局辅助。
 *
 * jian_box 里这些是内联写的（`Spacer(Modifier.height(x))`），
 * 本项目页面里用得频繁，抽成函数只为少写几行，不引入任何视觉决策。
 */

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

/**
 * 区块标题。
 *
 * 对应 jian_box 工具页 `ToolSectionCard` 内部那段
 * `Text(title, style = titleSmall, fontWeight = SemiBold, color = contentColor)`，
 * 这里抽出来复用，样式与其保持一致。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

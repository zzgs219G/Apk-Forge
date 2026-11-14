package com.xixin.apkforge.ui.lifted.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * APK 图标。
 *
 * 【为什么值得单独一个组件】列表里认包的第一依据是图标，不是文件名。
 * 但图标可能取不到（加固包、PackageManager 拒绝解析、被裁剪的应用信息），
 * 那时必须有一致的兜底外观——否则会出现「有的行有图、有的行空一块」的参差感。
 *
 * 兜底用**首字母方块**：取应用名首个字符，配色走 primaryContainer。
 * 这不是装饰，是让每一行的高度与左侧对齐稳定，滚动时不跳。
 */
@Composable
fun ApkIcon(
    icon: ImageBitmap?,
    fallback: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.24f)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fallback.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

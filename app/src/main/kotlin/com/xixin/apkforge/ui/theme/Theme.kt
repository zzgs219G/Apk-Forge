package com.xixin.apkforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.xixin.apkforge.ui.design.AppTypography
import com.xixin.apkforge.ui.design.rememberColorScheme

/**
 * 应用主题。
 *
 * 配色逻辑见 `design/Palette.kt`（material-kolor 色阶 + 浅色画布提白 + 深色主色保真）。
 * 组件层只消费 `MaterialTheme.colorScheme` / `MaterialTheme.typography`，不出现字面色值。
 */
@Composable
fun ComposeEmptyActivityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = rememberColorScheme(isDark = darkTheme),
        typography = AppTypography,
        content = content,
    )
}

package com.xixin.apkforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.xixin.apkforge.ui.design.Forge

/**
 * 应用主题。
 *
 * 刻意**不跟随系统浅色/深色**：这是二进制工具，深色是它视觉语言的一部分
 * （终端、代码、仪表盘的质感都建立在深色底上）。浅色模式会让整个设计散架。
 *
 * 配色与字阶全部来自 [Forge] 设计系统，不使用 M3 默认值。
 */
@Composable
fun ComposeEmptyActivityTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = Forge.colorScheme,
        typography = Forge.typography,
        content = content,
    )
}

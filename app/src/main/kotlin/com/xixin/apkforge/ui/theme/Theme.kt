package com.xixin.apkforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.xixin.apkforge.ui.design.AppTypography
import com.xixin.apkforge.ui.design.rememberColorScheme

/**
 * 应用主题。
 *
 * 【为什么固定深色，不再跟随系统】这不是偏好，是设计约束：二进制工具的视觉语言
 * （终端、代码块、仪表盘读数）建立在深色底上。上一版跟着 `isSystemInDarkTheme()`
 * 走，踩出一类很难看的故障：
 *
 *   `themes.xml` 的 `windowBackground` 写死近黑（`#0A0B0D`），Compose 却按系统设置
 *   渲染浅色方案。页面根节点没有 `Surface`，画布是透明的 —— 于是透出黑底，
 *   而文字用的是浅色方案的 `onSurface`（近黑）。**黑字画在黑底上**，
 *   表现为「标题看不见、整页像被抽空了」。用户那句「我本来就是深色模式，
 *   为什么还有黑色文本」就是从这来的。
 *
 * 两处一起改才彻底：
 *   1. 固定 `darkTheme = true`，配色与 `themes.xml` 的窗口底色就不再有机会分叉；
 *   2. 用 `Surface` 兜住整棵树，画布由 Compose 自己绘制。
 *      任何页面忘记画背景也不会漏出窗口底色，文字对比度永远由同一个方案保证。
 *
 * [darkTheme] 参数保留，是为了将来真要做浅色时只需改这一行默认值——但那时
 * `themes.xml` 的窗口底色也要跟着分支，否则同一个故障会原样复现。
 */
@Composable
fun ComposeEmptyActivityTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = rememberColorScheme(isDark = darkTheme)
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
    ) {
        Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}

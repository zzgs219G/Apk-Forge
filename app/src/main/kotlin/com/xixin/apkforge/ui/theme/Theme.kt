package com.xixin.apkforge.ui.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.xixin.apkforge.ui.design.AppShapes
import com.xixin.apkforge.ui.design.AppTypography
import com.xixin.apkforge.ui.design.rememberColorScheme

/**
 * 应用主题。
 *
 * ## ⚠️ 为什么必须用 `MaterialExpressiveTheme` + `MotionScheme.expressive()`
 *
 * 这是本项目「没有阻尼、没有呼吸感、UI 一闪一闪」的**根因**，不是审美问题。
 *
 * 上一版写的是 `MaterialTheme(...)`，它不带 `motionScheme` 参数——
 * `MaterialTheme.motionScheme` 于是回落到 `MotionScheme.standard()`：
 * 一条克制、快速、几乎看不出物理感的默认曲线。所有写着
 * `MaterialTheme.motionScheme.defaultSpatialSpec()` 的组件（抽屉、行、按压反馈）
 * 拿到的都是这条标准曲线，**看起来就像没有动画**。
 *
 * 对照 `克隆仓库x/jian_box` 的 `Theme.kt:450`：
 * ```
 * MaterialExpressiveTheme(
 *     colorScheme = colorScheme,
 *     motionScheme = MotionScheme.expressive(),
 *     typography = ...,
 *     shapes = ...,
 * )
 * ```
 * 它的「现代感」不是靠手调 spring 数值堆出来的，而是**整棵树换了一套动效语言**：
 * expressive 的 spatial 曲线带真实的加减速与轻微过冲，effects 曲线有呼吸感。
 * 这正是 M3 Expressive 与普通 M3 的分界线。
 *
 * ## 为什么固定深色，不跟随系统
 *
 * 这不是偏好，是设计约束：二进制工具的视觉语言（终端、代码块、仪表盘读数）
 * 建立在深色底上。上一版跟着 `isSystemInDarkTheme()` 走，踩出一类很难看的故障：
 *
 *   `themes.xml` 的 `windowBackground` 写死近黑（`#0A0B0D`），Compose 却按系统设置
 *   渲染浅色方案。页面根节点没有 `Surface`，画布是透明的 —— 于是透出黑底，
 *   而文字用的是浅色方案的 `onSurface`（近黑）。**黑字画在黑底上**，
 *   表现为「标题看不见、整页像被抽空了」。
 *
 * 两处一起改才彻底：
 *   1. 固定 `darkTheme = true`，配色与 `themes.xml` 的窗口底色不再有机会分叉；
 *   2. 用 `Surface` 兜住整棵树，画布由 Compose 自己绘制。
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
    MaterialExpressiveTheme(
        colorScheme = scheme,
        // M3E 动效语言：spatial 带真实加减速与轻微过冲，effects 有呼吸感。
        // 换掉它，全站组件立刻退回「跳变」。
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}

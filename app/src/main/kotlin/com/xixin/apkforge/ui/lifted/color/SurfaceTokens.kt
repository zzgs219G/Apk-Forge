// app/src/main/java/com/xixin/box/wrapper/ui/SurfaceTokens.kt
package com.xixin.apkforge.ui.lifted.color

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 限"设置页与关于页"的局部明暗语义覆盖(CompositionLocal,默认 null = 其他页面行为完全不变)。
 *
 * ⚠️ 2026-10-22 用户反馈调档后,本机制已**实质停用**:设置页与关于页现在都 `provide null`,
 *    即"不提供覆盖",全 App 卡片统一走 `ColorRole.CardSurface`
 *    (深色 `surfaceContainerHighest`,浅色 `surfaceContainerHigh`;见 AppColorRoles.kt 的 lightSlot)。
 *    ⚠️ 2026-10-25 修正:此前设置/关于页的兜底写死 `surfaceContainer`,并没有真的跟随 CardSurface,
 *    本文件的历史描述因此与画面不符 —— 现两页兜底都改为 roleColor(CardSurface),才真正统一。
 *
 * 撤销前的历史行为(留档,勿再照此实现):
 *   - 浅色:灰背景(surfaceContainerHigh)+ 近白卡片(surfaceContainerLow)
 *   - 深色:稍抬背景(surfaceContainerLow)+ 更高卡片(surfaceContainerHigh)
 *   - AMOLED 纯黑:背景保纯黑省电、卡片抬到 High
 * 撤销原因(material-kolor 5.0.0 字节码反查 + WCAG 对比实测,深海蓝种子):
 *   深色 Low  vs surface = 1.04(≈看不出,用户原话"主页卡片和背景太相近")
 *   深色 High vs surface = 1.18(用户原话"设置页卡片凉了一点点")
 *   两者都不是用户要的"中间值",而 `surfaceContainer` = 1.10 恰好落在中间,且深浅两模式同时适用。
 *
 * CompositionLocal 机制本身保留不删:走廊若日后要为单页做差异化,仍走这条通道,
 * 无需重新引入全局改动(全局改会把整个 App 的背景/卡片都改掉)。
 */
val LocalSurfaceBackdrop: ProvidableCompositionLocal<Color?> = compositionLocalOf { null }

val LocalCardContainer: ProvidableCompositionLocal<Color?> = compositionLocalOf { null }

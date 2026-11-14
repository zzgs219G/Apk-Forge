package com.xixin.apkforge.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 配色。
 *
 * 移植自 `克隆仓库x/jian_box` 的 `Theme.kt` buildColorScheme 逻辑（263 文件版本，
 * 不是 AndroidCSProjects 里那份旧拷贝）。
 *
 * 三层叠加，缺一层观感就不对：
 *
 * 【第 1 层】`dynamicColorScheme(种子色, TonalSpot, SPEC_2025)` 生成完整 M3 色阶。
 *   SPEC_2025 是 M3 Expressive 的新色阶，material3 本身没实现，只能经 material-kolor 取。
 *
 * 【第 2 层 · 仅浅色】`surface`/`background` 对齐 `surfaceContainerLowest`（纯白）。
 *   病因不是某个颜色错了，而是**整张画布本身是灰的**：SPEC_2025 浅色 surface = tone 98
 *   带彩度的灰白，卡片是更灰的灰，一层灰压一层灰，怎么调都"脏"。
 *   只改 surface 不动 surfaceContainer*：改后者等于同时改所有页面的卡片观感，
 *   改前者只影响画布本身，卡片与画布的**相对关系**反而更清楚。
 *
 * 【第 3 层 · 仅深色】用 `Fidelity` 重算 primary，**只替换 primary/onPrimary 两个槽**。
 *   TonalSpot 在深色下会把 Primary 的 Chroma 压到极低，导致主色发灰（紫蓝都变水泥色）。
 *   ⚠️ 容器系槽位（primaryContainer/onPrimaryContainer）绝不能跟着换：
 *   Fidelity 的 primaryContainer 取种子色自身 tone 且不打折，onPrimaryContainer
 *   会按对比度反推成近黑 —— 表现为"卡片图标芯片底刺眼、图标黑成一片"。
 */
object ThemeSeeds {

    /** 深海静蓝：默认种子。低彩度、高质感，不是高饱和"黑客风"。 */
    val OceanBlue = Color(0xFF2563EB)

    /** 松石冷绿 */
    val SageGreen = Color(0xFF0F766E)

    /** 极光冰蓝 */
    val CyanAurora = Color(0xFF0284C7)

    /** 焦糖琥珀 */
    val AmberGold = Color(0xFFB45309)

    /** 极简板岩：最克制的一档 */
    val SlateModern = Color(0xFF334155)
}

/** M3 Expressive 色阶（material3 未实现，经 material-kolor 取得）。 */
private val SPEC = ColorSpec.SpecVersion.SPEC_2025

/** 保守平衡的调色算法，不出现跳脱色相。 */
private val STYLE = PaletteStyle.TonalSpot

fun buildColorScheme(seed: Color, isDark: Boolean): ColorScheme {
    var scheme = dynamicColorScheme(
        seedColor = seed,
        isDark = isDark,
        style = STYLE,
        specVersion = SPEC,
    )

    // 第 2 层：浅色画布提到纯白
    if (!isDark) {
        scheme = scheme.copy(
            surface = scheme.surfaceContainerLowest,
            background = scheme.surfaceContainerLowest,
        )
    }

    // 第 3 层：深色主色保真（只动 primary/onPrimary）
    if (isDark) {
        val fidelity = dynamicColorScheme(
            seedColor = seed,
            isDark = true,
            style = PaletteStyle.Fidelity,
            specVersion = SPEC,
        )
        scheme = scheme.copy(
            primary = fidelity.primary,
            onPrimary = fidelity.onPrimary,
        )
    }

    return scheme
}

@Composable
fun rememberColorScheme(seed: Color = ThemeSeeds.OceanBlue, isDark: Boolean): ColorScheme =
    remember(seed, isDark) { buildColorScheme(seed, isDark) }

/**
 * 字阶。
 *
 * 沿用 M3 命名与语义，组件层一律通过 `MaterialTheme.typography.xxx` 取。
 * 比默认 Typography 稍微拉开层级（默认 bodyMedium 与 titleSmall 都是 14sp，区分不足）。
 * 中文字形视觉重量大，标题不过重，避免整屏发黑。
 */
val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** 圆角：与 jian_box 工具页一致（卡片 20~24dp，输入框 28dp）。 */
object Shape {
    val card = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val section = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    val field = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val chip = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val sheet = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
}

/** 间距。 */
object Spacing {
    val screenH = 16.dp
    val section = 22.dp
    val block = 12.dp
    val item = 8.dp
    val tight = 4.dp
}

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
    /**
     * 页面主标题。
     *
     * ⚠️ 必须显式定义。此前这一档缺失，`AppTopBar` 取到的是 M3 默认的
     * **32sp / weight 400**，代码再叠一层 `FontWeight.ExtraBold` ——
     * 那是全 App 最重的一处，用在了每页最显眼的位置，与本文件开头
     * 「中文字形视觉重量大，标题不过重，避免整屏发黑」直接矛盾。
     * 现在收回到 22sp / Medium，与 titleLarge(19sp) 拉开一档而不过分。
     */
    headlineLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
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

// ⚠️ 这里曾有第二个圆角对象 `object Shape { card=20dp, section=24dp, field=28dp,
//    chip=10dp, sheet=28dp }`，与下面 AppShapes 的 6/12/18/24/32 **两套数值并存**。
//    它零引用（全项目 0 处调用），但只要留着，下一个改 UI 的人就会从中挑一个用——
//    于是同一屏出现 20dp 和 24dp 两种"卡片圆角"。已删除：圆角只允许 AppShapes 一处。

/**
 * M3 圆角档位 —— 五档语义，禁止新增。
 *
 * 移植自 `克隆仓库x/jian_box` 的 `Theme.shapes()`，数值一字未改。
 * 语义映射见 jian_box `docs/UI设计规范.md` §1.1：
 *
 * | 档位 | 用途 |
 * |---|---|
 * | `extraLarge` 32dp | 页面级大卡片（Section 卡） |
 * | `large` 24dp | 对话框、错误提示卡 |
 * | `medium` 18dp | 卡内子项、图标芯片、缩略图、可点行 |
 * | `small` 12dp | 小徽标、Chip |
 * | `extraSmall` 6dp | 行内展开区的次级容器 |
 *
 * ⚠️ 为什么必须有这一层：上一版全项目 60 处 `RoundedCornerShape(xx.dp)` 字面量、
 * 11 种不同档位（4/8/10/12/14/16/18/20/24/28/100），同一个「卡片」概念在不同页面
 * 用了 20dp 和 28dp 两种圆角。用户看不到代码，只会说「东拼西凑」。
 * 组件一律写 `MaterialTheme.shapes.extraLarge`，不再写字面量。
 */
/**
 * 五档圆角的 **Dp 值本身**（上面 [AppShapes] 与下面这些常量必须同源）。
 *
 * 为什么需要 Dp 形式：`animateDpAsState` / `Modifier.animateContentSize` 之类的
 * 动画 API 要的是 `Dp`，而 `Shapes` 里装的是 `Shape`（无法反解出数值）。
 * 如果动画目标值直接写 `12.dp / 18.dp` 字面量，就会出现「静态圆角走令牌、
 * 动画圆角走字面量」的双轨——档位一改，静态的变了、动画的没变，两者对不上。
 *
 * 因此：**静态用 `MaterialTheme.shapes.*`，动画用 `AppRadius.*`，数值只有这一处。**
 */
object AppRadius {
    val extraSmall = 6.dp
    val small = 12.dp
    val medium = 18.dp
    val large = 24.dp
    val extraLarge = 32.dp
}

val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.extraSmall),
    small = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.small),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.medium),
    large = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.large),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.extraLarge),
)

/** 间距。 */
object Spacing {
    val screenH = 16.dp
    val section = 22.dp
    val block = 12.dp
    val item = 8.dp
    val tight = 4.dp
}

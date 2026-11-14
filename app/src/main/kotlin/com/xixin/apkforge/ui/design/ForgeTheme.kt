package com.xixin.apkforge.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ApkForge 设计系统。
 *
 * 【为什么不直接用 M3 默认】M3 默认色板 + 默认字阶是为「通用 App」调的：
 * 正文 14sp 起、卡片统一圆角、色彩克制。这套东西放在一个**二进制工具**上，
 * 观感就是「demo」——因为它的视觉语言没有传达任何关于工具性质的信息。
 *
 * 本设计系统的取向：**终端 + 仪表盘**。
 *  - 深色打底，单一高饱和强调色（青绿），其余全用亮度分层
 *  - 数据一律等宽字体，标签一律小号大写 + 字距，形成「仪表读数」感
 *  - 字阶拉开：数字 28sp 对比标签 10sp，避免所有文字挤在 12~14sp 的中间地带
 *  - 形状分三级：数据块 4dp（硬朗）、卡片 14dp、容器 28dp（柔软），混用产生层次
 */
object Forge {

    // ── 色彩 ────────────────────────────────────────────────
    // 深色为唯一主题。浅色模式下二进制工具的气质会散掉，且代码块在浅底上更难读。
    object Colors {
        val bg = Color(0xFF0A0B0D)
        val surface = Color(0xFF121418)
        val surfaceHigh = Color(0xFF191C22)
        val surfaceMax = Color(0xFF21252D)

        val line = Color(0xFF2A2F38)
        val lineSoft = Color(0xFF1E222A)

        /** 主强调：青绿。用于「进行中/成功/可交互」 */
        val accent = Color(0xFF5EEAD4)
        val accentDim = Color(0xFF1F4A45)
        val accentInk = Color(0xFF04201D)

        /** 次强调：琥珀。用于「将新建/警告」 */
        val amber = Color(0xFFFBBF24)
        val amberDim = Color(0xFF3D2E0A)

        /** 错误：柔红 */
        val danger = Color(0xFFF87171)
        val dangerDim = Color(0xFF3D1717)

        val text = Color(0xFFE8EAED)
        val textDim = Color(0xFF9BA3AF)
        val textFaint = Color(0xFF5F6773)
    }

    // ── 字阶 ────────────────────────────────────────────────
    // 关键：拉开对比。数字要大，标签要小且带字距，中间地带尽量少。
    object Type {
        private val mono = FontFamily.Monospace

        /** 28sp 等宽 —— 体积、条目数这类「读数」 */
        val metric = TextStyle(
            fontFamily = mono,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.5).sp,
        )

        /** 20sp —— 页面主标题 */
        val display = TextStyle(
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        )

        /** 15sp —— 区块标题 */
        val title = TextStyle(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )

        /** 14sp —— 正文 */
        val body = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        )

        /** 13sp 等宽 —— 代码、路径、smali */
        val code = TextStyle(
            fontFamily = mono,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )

        /** 12sp —— 次要说明 */
        val caption = TextStyle(
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        /** 10sp 大字距 —— 全大写标签，仪表盘感的核心 */
        val label = TextStyle(
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        )

        /** 11sp 等宽小字 —— 路径、元数据 */
        val microMono = TextStyle(
            fontFamily = mono,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }

    // ── 形状 ────────────────────────────────────────────────
    object Shape {
        /** 数据块：硬朗，像终端里的一块输出 */
        val data = RoundedCornerShape(4.dp)
        val chip = RoundedCornerShape(8.dp)
        val card = RoundedCornerShape(14.dp)
        val sheet = RoundedCornerShape(28.dp)
        val pill = RoundedCornerShape(100.dp)
    }

    // ── 间距 ────────────────────────────────────────────────
    object Space {
        val hair = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 14.dp
        val l = 20.dp
        val xl = 28.dp
        val xxl = 40.dp
    }

    // ── M3 映射 ─────────────────────────────────────────────
    val colorScheme = darkColorScheme(
        primary = Colors.accent,
        onPrimary = Colors.accentInk,
        primaryContainer = Colors.accentDim,
        onPrimaryContainer = Colors.accent,
        secondary = Colors.amber,
        onSecondary = Color(0xFF2A1F00),
        secondaryContainer = Colors.amberDim,
        onSecondaryContainer = Colors.amber,
        tertiary = Colors.accent,
        background = Colors.bg,
        onBackground = Colors.text,
        surface = Colors.surface,
        onSurface = Colors.text,
        surfaceVariant = Colors.surfaceHigh,
        onSurfaceVariant = Colors.textDim,
        surfaceContainer = Colors.surface,
        surfaceContainerLow = Colors.surface,
        surfaceContainerHigh = Colors.surfaceHigh,
        surfaceContainerHighest = Colors.surfaceMax,
        outline = Colors.line,
        outlineVariant = Colors.lineSoft,
        error = Colors.danger,
        onError = Color(0xFF2A0A0A),
        errorContainer = Colors.dangerDim,
        onErrorContainer = Colors.danger,
        scrim = Color(0xCC000000),
    )

    val typography: Typography
        @Composable get() = Typography(
            displaySmall = Type.display,
            headlineMedium = Type.display,
            headlineSmall = Type.display,
            titleLarge = Type.title,
            titleMedium = Type.title,
            titleSmall = Type.title,
            bodyLarge = Type.body,
            bodyMedium = Type.body,
            bodySmall = Type.caption,
            labelLarge = Type.label,
            labelMedium = Type.label,
            labelSmall = Type.label,
        )
}

/** 供 Composable 内快速取字阶：`Forge.Type.code` 也可，但 `ForgeText.code` 更短。 */
val ForgeTypography: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyLarge

/** 语义化的间距常量，避免在调用点堆魔法数字。 */
@Immutable
data class ForgeSpacing(
    val screenH: androidx.compose.ui.unit.Dp = 18.dp,
    val sectionGap: androidx.compose.ui.unit.Dp = 26.dp,
    val blockGap: androidx.compose.ui.unit.Dp = 14.dp,
)

val LocalForgeSpacing = staticCompositionLocalOf { ForgeSpacing() }

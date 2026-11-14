package com.xixin.apkforge.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 动效与尺寸的**单一真相源**。
 *
 * 【为什么要这个文件】移植自 jian_box 的 `wrapper/ui/m3e/M3E.kt`。
 * 之前的 UI 之所以散，根子就在这里——弹簧参数、转场曲线、按钮高度、
 * 屏幕边距这些值全部散落在各个 Composable 里，写一次变一个样，
 * 结果就是「每个组件单看还行，拼在一起互不相干」。
 *
 * 铁律（沿用 jian_box 的约束）：
 * 1. 所有浮点参数必须保持原始精度，禁止四舍五入
 * 2. 业务代码里禁止自己写 `spring(...)` / `tween(...)` 字面量，一律引用本文件
 * 3. 新增动效槽位必须注释「为什么不用现成的 Motion.X」
 */
object Motion {

    // ── 弹簧 ────────────────────────────────────────────────
    object Spring {
        /** 通用按压回弹：阻尼 0.5 / 刚度 400（M3E fastSpatial 手感） */
        val Press = spring<Float>(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        )

        /** 胶囊选择：临界阻尼 + 刚度 350，无过冲 */
        val Pill = spring<Float>(
            dampingRatio = 1.0f,
            stiffness = 350f,
        )

        /**
         * 容器展开跟高。必须写 `spring<IntSize>` ——
         * `Modifier.animateContentSize` 要求 `FiniteAnimationSpec<IntSize>`，
         * 写成 `spring<Float>` 直接编译不过。
         */
        val ExpandContent = spring<IntSize>(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        )

        /** 折叠回弹（顶栏、抽屉收尾有过冲） */
        val Bouncy = spring<Float>(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
        )
    }

    // ── 缓动曲线 ─────────────────────────────────────────────
    object Ease {
        /** 标准进出（Material 3 expressive 同款） */
        val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
        /** 减速进：起步快、收尾慢，用于进入屏幕 */
        val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
        /** 加速出：起步慢、收尾快，用于离开屏幕 */
        val Accelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    }

    // ── 时长（毫秒） ─────────────────────────────────────────
    const val DUR_FAST = 150
    const val DUR_BASE = 300
    const val DUR_SLOW = 320
    const val DUR_EXIT = 250
    const val DUR_EXIT_FAST = 200

    // ── 页面转场 ─────────────────────────────────────────────
    /** 方向感知：dir = 1 向右滑，-1 向左滑。位移只取屏幕宽度的 12%，避免过度位移。 */
    object Transition {
        fun enterSlide(dir: Int) = slideInHorizontally(
            animationSpec = tween(DUR_SLOW, easing = Ease.Decelerate),
            initialOffsetX = { (it * 0.12f * dir).toInt() },
        )

        fun exitSlide(dir: Int) = slideOutHorizontally(
            animationSpec = tween(DUR_EXIT, easing = Ease.Accelerate),
            targetOffsetX = { -(it * 0.12f * dir).toInt() },
        )

        val EnterFade = fadeIn(animationSpec = tween(DUR_EXIT))
        val ExitFade = fadeOut(animationSpec = tween(DUR_EXIT_FAST))
    }

    // ── 按压缩放 ─────────────────────────────────────────────
    const val PRESS_SCALE = 0.96f
}

/**
 * 尺寸 token。移植自 jian_box `AppConstants` 的 UI 规范段。
 */
object Dim {
    /** 屏幕边缘安全间距 */
    val edge: Dp = 18.dp

    /** 标准按钮高度（触控区 ≥ 48dp） */
    val button: Dp = 52.dp

    /** 图标尺寸 */
    val icon: Dp = 20.dp
    val iconSmall: Dp = 16.dp

    /** 顶栏高度 */
    val topBar: Dp = 64.dp

    /** 区块间距 */
    val sectionGap: Dp = 26.dp
    val blockGap: Dp = 14.dp
    val rowGap: Dp = 8.dp
}

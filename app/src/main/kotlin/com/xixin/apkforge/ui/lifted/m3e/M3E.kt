package com.xixin.apkforge.ui.lifted.m3e

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring as ComposeSpring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntSize

/**
 * ============================================================
 * ⚠️ M3E 设计系统 AI 开发铁律（必须遵守）
 * ============================================================
 *
 * 1. 【切 Tab 必配缓存】任何列表/标签切换，必须配合 TabCache 内存缓存，
 *    严禁直接清空列表再请求，防止过渡容器在动画期间展示空数据（白屏）。
 *
 * 2. 【严禁重置滚动状态】切换 Tab 时绝对禁止调用 scrollToItem(0) 或
 *    强行重置 LazyListState，否则会破坏折叠顶栏的弹簧回弹体验。
 *
 * 3. 【复用过渡容器】所有列表切换必须使用 M3EAnimatedContentContainer，
 *    禁止在业务代码中自己手写 AnimatedContent 的 transitionSpec。
 *
 * 4. 【精度保持】所有浮点参数必须保持原始精度，禁止四舍五入。
 * ============================================================
 */
object M3E {
    // 加载指示器参数（原 AppLoadingIndicator 默认值，逐字搬迁，禁止改动）
    object Loading {
        const val CycleMs = 5600
        const val BurstWindow = 0.5f
        const val BaseX = 25.0f
        const val MorphY = 125.0f
        const val BaseScale = 0.43391687f
        const val PulseAmplitude = 0.024676055f
        const val LiquidSquish = 0.019507043f
        val Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
        // 缓动曲线控制点（供测试页默认值绑定，禁止改动）
        const val EasingP1x = 0.2f
        const val EasingP1y = 0.0f
        const val EasingP2x = 0.0f
        const val EasingP2y = 1.0f
    }

    // 弹簧手感（物理搬迁自 DynamicHeightTopAppBar 与 M3ExpressiveRefreshIndicator，零手感变化）
    object Spring {
        // 顶栏折叠回弹弹簧：原 BaseDynamicHeightTopAppBar.startSpringBack 参数
        // 原写法 spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        // = 阻尼 0.5f、刚度 400f
        val Standard = spring<Float>(
            dampingRatio = ComposeSpring.DampingRatioMediumBouncy,
            stiffness = ComposeSpring.StiffnessMediumLow
        )
        // 刷新指示器松手回弹弹簧：原 M3ExpressiveRefreshIndicator 刷新回弹参数
        // 原写法 spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        // = 阻尼 0.5f、刚度 200f
        val Bouncy = spring<Float>(
            dampingRatio = ComposeSpring.DampingRatioMediumBouncy,
            stiffness = ComposeSpring.StiffnessLow
        )
        // 胶囊选择弹簧（仓库页左侧 rail 等选择动效共用）：
        // 阻尼 1.0f（临界阻尼）+ 刚度 350f，与底栏 OrbitDock（CleanHydraulicSpring）完全一致，
        // 保证选择动效与底栏同一手感。（主页/各页 tab 栏 AppTabRow 为 NoBouncy/800，见 wrapper/ui/tab/）
        val Pill = spring<Float>(
            dampingRatio = 1.0f,
            stiffness = 350f
        )
        /**
         * 展开/收起「容器弹性跟高」弹簧（专供 `Modifier.animateContentSize`）。
         *
         * 原写法散落于 ToolboxPage.ToolSectionCard：
         *   spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
         * 数值一字未改（仅从页面私有实现收敛为项目级命名常量），故工具页既有手感零漂移。
         *
         * 为何不用官方 motionScheme 槽位：LowBouncy（0.75）在收尾处有轻微过冲，
         * 正是"抽屉 / 卡片展开"的专属物理手感；官方 spatial 槽承载的是位移与缩放，
         * 不表达"尺寸带过冲地跟随内容"，套用官方槽位会丢掉这段回弹，观感变"顶"。
         * 依据规范 §2.3：自定义 spring 须注释原因 + 同类场景复用同一命名常量。
         *
         * ⚠️ 类型固定为 IntSize：`Modifier.animateContentSize` 的入参是
         * `FiniteAnimationSpec<IntSize>`；若写成 `spring<Float>`（本项目 Spring.Standard
         * 等其它常量的类型）会直接编译不过。工具页原先能编译，正是因为裸 `spring()`
         * 的泛型由实参位置推断成了 IntSize —— 提取常量时必须把类型写下来。
         */
        val ExpandContent = spring<IntSize>(
            dampingRatio = ComposeSpring.DampingRatioLowBouncy,
            stiffness = ComposeSpring.StiffnessMediumLow
        )

        /**
         * 展开/收起「不过冲」变体：**临界阻尼**（= 1f），收尾不越过目标高度。
         *
         * 与 [ExpandContent] 的差别只在阻尼：LowBouncy(0.75) 会先缩得比目标更矮再弹回，
         * 在工具页里表现为「卡头背景撞进下方胶囊」（见 `ToolboxTiles.ToolboxExpandSpec` 注释）。
         * 凡展开区**紧邻另一个有色块**（工具页分类、结果卡）时用这个；单纯的抽屉展开用 [ExpandContent]。
         *
         * 泛型同样必须是 `IntSize`（`expandVertically` / `animateContentSize` 的规格类型）。
         */
        val ExpandContentNoOvershoot = spring<IntSize>(
            dampingRatio = ComposeSpring.DampingRatioNoBouncy,
            stiffness = ComposeSpring.StiffnessMediumLow
        )
    }

    /**
     * 下拉刷新指示器的颜色参数。
     *
     * ⚠️ 2026-11 用户裁决：**弧线不再分三段**。
     * 上一版把弧线按 1 / 0.55 / 0.25 三段浓度画，本意是「头亮尾淡」的拖尾，
     * 实际观感是「一根弧被切成三截」，用户直接问「为什么是三段式的」——那就去掉。
     * 现在整条弧一个颜色、一个不透明度（[ArcAlpha] 未达阈值 / [ArcAlphaReady] 可松手），
     * 与官方 `PullToRefreshDefaults.Indicator` 的弧线取色口径一致。
     *
     * 仍然不用 `PullToRefreshDefaults.Indicator(color = ...)`：那个参数是把同一颜色传两遍
     * （`containerColor = color` + `color = color`），得到「实心彩球 + 同色彩弧」，
     * 深色模式下彩球会把 2.5dp 的弧线糊掉。这里保留官方默认容器色
     * （`surfaceContainerHigh`，与下拉层同色系、边界自然），弧线只取主色本身。
     */
    object Refresh {
        /** 弧线拉出过程中的不透明度（官方同值）：提示「还没到阈值」 */
        const val ArcAlpha = 0.3f

        /** 到达刷新阈值后的不透明度：提示「可以松手了」 */
        const val ArcAlphaReady = 1f

        /**
         * 刷新阶段自转一圈的时长（ms）。
         *
         * ⚠️ 2026-11 二次返工：**光有自转角度是不够的**。
         * 上一版把刷新态画成 `sweepAngle = 360f` 的整圈，再叠 `rotate(spin)`——
         * 但圆环绕中心转任何角度，画面**一模一样**，转与不转在视觉上没有任何区别。
         * 官方 indeterminate 之所以看得出在转，是因为它画的弧**有缺口**
         * （sweep 在 36°~313° 之间呼吸），缺口位置绕着圈走。
         * 所以这里的自转必须配合 [SweepMin] / [SweepMax] 的缺口才成立。
         *
         * 1200ms/圈：接近官方 indeterminate 的缺口绕行速度，小尺寸下看得清但不慌。
         */
        const val SpinCycleMs = 1200

        /**
         * 刷新阶段弧长呼吸一个来回的时长（ms）。
         *
         * 只有匀速自转会很单调（一根不变形的弧绕着圈跑）；官方 indeterminate
         * 是「绕行 + 弧长伸缩」两件事叠在一起，才有「在转」的层次。
         * 取 [SpinCycleMs] 的 2 倍：每转一圈长短变化半次，不会显得抽搐。
         */
        const val BreatheCycleMs = 2400

        /** 刷新态弧长占整圈的最小比例（缺口最大），与 [SweepMax] 构成呼吸区间 */
        const val SweepMin = 0.18f

        /** 刷新态弧长占整圈的最大比例（缺口最小） */
        const val SweepMax = 0.82f

        /**
         * 刷新指示器的**最短显示时长**（ms）。
         *
         * 数据源是云端定时生成的静态 JSON（`cache-control: max-age=14400`），一次刷新通常
         * 100～300ms 就回来；`isRefreshing` 一落回 false，指示器立刻收起 ——
         * 看起来就是「松手，转半圈，没了」，用户原话「像静态的、没有旋转感」。
         * 这里保底让刷新态至少活满这个时长：**它不会拖慢刷新，只是让动画看得见**。
         *
         * 必须 ≳ [SpinCycleMs]：少于一圈的话缺口走不满 360°，看起来仍然像「晃了一下」。
         * 现取 1400ms（约 1.15 圈），多出的 200ms 抵掉 Crossfade 淡入（180ms）吃掉的可见时长。
         */
        const val MinShowMs = 1400L
    }

    // 过渡动画参数（方向感知函数：dir = 1 向右滑，-1 向左滑）
    object Transition {
        fun enterSlide(dir: Int) = slideInHorizontally(
            animationSpec = tween(320, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)),
            initialOffsetX = { (it * 0.12f * dir).toInt() }
        )
        fun exitSlide(dir: Int) = slideOutHorizontally(
            animationSpec = tween(250, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)),
            targetOffsetX = { -(it * 0.12f * dir).toInt() }
        )
        val EnterFade = fadeIn(animationSpec = tween(250))
        val ExitFade = fadeOut(animationSpec = tween(200))
    }

    // 下载进度波浪参数（原 M3ExpressiveDownloadProgress 默认值，逐字搬迁）
    object Download {
        const val WaveCycleMs = 1400
        const val WaveCount = 10
        const val WaveAmplitude = 0.30f
    }
}

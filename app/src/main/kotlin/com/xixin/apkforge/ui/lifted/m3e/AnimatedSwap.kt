package com.xixin.apkforge.ui.lifted.m3e

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 同位置双状态过渡 —— 空 ↔ 有数据、待运行 ↔ 已完成、复制 ↔ 已复制。
 *
 * ## 为什么要有这个组件
 *
 * `M3E.kt` 头的铁律第 3 条写着「**复用过渡容器**：禁止在业务代码中自己手写
 * `AnimatedContent` 的 `transitionSpec`」。原因不是洁癖：
 *
 *  · `transitionSpec` 是普通 lambda（**非 `@Composable`**），读不了
 *    `MaterialTheme.motionScheme`。想用官方语义槽，就必须先在 `@Composable`
 *    作用域里把 spec hoist 出来——这一步每个调用点都要重写一遍，
 *    而写错的表现是编译报错，不是运行时异常，所以「抄一半」很难被发现。
 *  · 手写时容易只给 `fadeIn/fadeOut`（见本组件改造前的 RunScreen:133），
 *    结果是「文字原地淡入淡出」——没有位移、没有缩放，观感上就是**内容闪了一下**。
 *    这正是用户说的「UI 闪烁」。M3E 的双状态过渡必须**淡入 + 缩放同时进行**：
 *    淡入负责不突兀，缩放负责说明「新内容从哪来」。
 *
 * ## 为什么透明度用 effects、缩放用 spatial
 *
 * 官方 `MotionScheme` 的分工：`spatial` 承载位移/缩放（会改变布局占位感），
 * `effects` 承载透明度/颜色（不改变占位）。两者混用会让「快慢」对不上——
 * 缩放先到位而透明度还在爬，看起来就是半透明的块在跳。
 *
 * ## 泛型说明
 *
 * `AnimatedContent` 的 `contentKey` 默认为 `targetState` 本身，
 * 因此调用方传的 `targetState` 必须是**可比较的稳定值**（枚举 / Int / Boolean / String），
 * 不要传每次重组都新建的 data class 实例——那会导致每次重组都播一遍过渡。
 *
 * @param targetState 当前状态。相同则不播动画。
 * @param modifier 修饰
 * @param label 动画标签（Layout Inspector 里用于定位）
 * @param content 按状态渲染内容
 */
@Composable
fun <T> AnimatedSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "AnimatedSwap",
    content: @Composable (T) -> Unit,
) {
    // ⚠️ transitionSpec 是非 @Composable lambda，必须先在这里把 spec 取好。
    // ⚠️ 必须写明泛型 FiniteAnimationSpec<Float>：motionScheme 的两个 spec 函数是
    //    <T> 泛型，直接 val 赋值没有期望类型可推，会报「Cannot infer type for T」。
    val motionScheme = MaterialTheme.motionScheme
    val enterEffects: FiniteAnimationSpec<Float> = motionScheme.defaultEffectsSpec()
    val exitEffects: FiniteAnimationSpec<Float> = motionScheme.fastEffectsSpec()
    val spatial: FiniteAnimationSpec<Float> = motionScheme.defaultSpatialSpec()

    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(animationSpec = enterEffects) +
                scaleIn(initialScale = SwapScale, animationSpec = spatial)) togetherWith
                (fadeOut(animationSpec = exitEffects) +
                    scaleOut(targetScale = SwapScale, animationSpec = spatial))
        },
        label = label,
    ) { state -> content(state) }
}

/**
 * 双状态过渡的起止缩放比。
 *
 * 0.96 不是随手取的：小于 ~0.92 会读作「弹出」，大于 ~0.98 又看不出缩放（等于没做）；
 * 0.96 在 100~300ms 的过渡里刚好是「微微推近」的感知阈值。
 * 与 `jian_box` 的 `SWAP_SCALE` 同值，保持两个工程同一手感。
 */
private const val SwapScale = 0.96f

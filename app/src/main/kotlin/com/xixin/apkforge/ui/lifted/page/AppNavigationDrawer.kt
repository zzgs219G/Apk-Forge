package com.xixin.apkforge.ui.lifted.page

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 🎓 【设计思路】
//
// 核心问题：Material3 的 ModalNavigationDrawer 内置 anchoredDraggable，
// 手指稍有晃动就锁定触摸序列，导致蒙层点击失效。唯一出路是自己实现。
//
// 本实现的关键设计：
//
//  ┌──────────────────────────────────────────────────────────┐
//  │  offsetX（单一真相来源）                                  │
//  │   - 拖动时：直接跟手，实时更新，完全跟手                  │
//  │   - 松手时：用 animate() 以弹簧动画 settle 到 0 或 -width │
//  │   - 外部 state 变化时：同样用 animate() 平滑过渡          │
//  └──────────────────────────────────────────────────────────┘
//
//  所有视觉效果（面板位置、蒙层透明度）都从 offsetX 实时派生，
//  因此不存在"状态变了但动画还没跑完"的残影问题。
//
//  与外部 DrawerState 的同步：
//   - 外部调用 state.open() / state.close() → LaunchedEffect 捕获 → animate() 过渡
//   - 拖动 settle 结束 → 回写 state（让调用方感知到抽屉的实际状态）

@Composable
fun AppModalNavigationDrawer(
    state: DrawerState,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 官方 M3E 动效语义槽（expressive defaultSpatialSpec = 0.8/380，与本处原写法同值）；
    // 协程（LaunchedEffect / scope.launch）内无法读 MaterialTheme，故先在此取值再传入。
    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidthDp = screenWidthDp.dp * 0.82f
    /**
     * 抽屉宽度（px）。
     *
     * ⚠️ **必须兜底成 1f 以上，不能直接用 `toPx()`**。
     *
     * `LocalConfiguration.screenWidthDp` 在**旋转 / 分屏 / 折叠屏展开**的重配过程中
     * 会短暂返回 0（Compose 已知行为）。此时 `drawerWidthPx == 0f`，而下面
     * `progress = 1 + offsetX / drawerWidthPx` 会算出 `NaN`：
     *
     * ```
     *   offsetX / 0f  =  NaN           （-0f/0f 也是 NaN）
     *   progress      =  NaN
     *   isVisible     =  NaN > 0.001   → false
     *   offsetX       =  NaN           ← 被 animate() 以 NaN 为初值写回
     * ```
     *
     * 一旦 `offsetX` 变成 NaN，`coerceIn` 对 NaN 是**直接返回 NaN**，
     * 之后无论怎么点、怎么拖都恢复不了 —— 表现为「连点几次就崩/侧边栏再也打不开」。
     * 这条路径只在屏幕尺寸变化时命中，所以症状是「经常」而不是「每次」。
     *
     * 兜底到 1f 让除法永远是有限值；真实宽度为 0 时抽屉本来也没有可显示的空间。
     */
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }.let {
        if (it.isFinite() && it > 0f) it else 1f
    }

    // ── offsetX：抽屉面板的水平偏移，单位像素 ─────────────────────────────────
    // 取值范围：-drawerWidthPx（完全关闭）到 0f（完全打开）
    // 这是整个系统唯一的真相来源，所有视觉效果从它派生。
    //
    // ⚠️ key 必须是 drawerWidthPx：屏幕尺寸变化后要按**新宽度**重新落位，
    //    否则旧偏移量配新宽度，抽屉会停在半开的位置。
    var offsetX by remember(drawerWidthPx) {
        mutableFloatStateOf(
            if (state.currentValue == DrawerValue.Open) 0f else -drawerWidthPx
        )
    }

    // ── progress：从 offsetX 实时派生，0f=关闭，1f=打开 ──────────────────────
    //
    // ⚠️ `remember(drawerWidthPx)` 的 key 同样不能省：`derivedStateOf` 的 lambda
    //    捕获的是创建那次组合里的 `drawerWidthPx`。不重建的话，旋转之后
    //    progress 仍按旧宽度算，蒙层透明度与可见性判定都会错。
    //    再额外 coerceIn：派生出界外的值能挡住但不该出现，挡住是为了不把
    //    NaN/Infinity 传播给 alpha（Scrim 会因此整块变黑或消失）。
    val progress by remember(drawerWidthPx) {
        derivedStateOf {
            val p = 1f + (offsetX / drawerWidthPx)
            if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
        }
    }

    // ── 蒙层和面板是否应该存在（progress 归零后就完全移除，节省资源）────────────
    val isVisible by remember {
        derivedStateOf { progress > 0.001f }
    }

    // 唯一的写入闸门：任何对 offsetX 的写入都走这里。
    //
    // 为什么要一个函数而不是直接赋值：`offsetX` 有 4 个写入点（初始值 /
    // 外部 open-close 动画 / 拖动 / settle 收尾），其中任何一处漏掉有限性检查，
    // 都会让抽屉永久卡死 —— NaN 一旦写进去，`coerceIn` 会原样返回 NaN，
    // 之后再怎么点都恢复不了。收成一处之后，「不可能写出 NaN」只依赖这一个函数。
    fun setOffset(v: Float) {
        if (!v.isFinite()) return
        offsetX = v.coerceIn(-drawerWidthPx, 0f)
    }

    // ── 监听外部 DrawerState 变化，用 animate() 平滑过渡到目标位置 ───────────
    // 🎓 LaunchedEffect(key)：key 变化时重新执行，可被新的执行取消（自动取消旧动画）
    // 这样 state.open() / state.close() 调用就能触发我们自己的动画
    //
    // ⚠️ key 里带上 drawerWidthPx：屏幕尺寸变了要按新宽度重算目标位置。
    LaunchedEffect(state.targetValue, drawerWidthPx) {
        val targetOffsetX = if (state.targetValue == DrawerValue.Open) 0f else -drawerWidthPx
        // animate()：协程版本的动画，会挂起直到动画完成
        animate(
            initialValue = offsetX,
            targetValue = targetOffsetX,
            // 原写法 spring(dampingRatio = 0.8f, stiffness = 380f)（与官方值完全相同）
            animationSpec = motionScheme.defaultSpatialSpec()
        ) { value, _ -> setOffset(value) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── 第一层：主页面 ─────────────────────────────────────────────────────
        content()

        // ── 第二层：蒙层（从 progress 实时派生透明度）────────────────────────
        // 🎓 为什么不用 animateFloatAsState？
        //   因为透明度直接从 offsetX → progress 派生，offsetX 跟手变化，
        //   透明度就实时跟着变，无需额外的动画驱动，天然丝滑。
        if (isVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(
                            // 限制最大 0.32f，与 Material3 官方 scrim 默认值一致
                            alpha = (progress * 0.32f).coerceIn(0f, 0.32f)
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // 点击蒙层关闭：通过 scope 调用 state.close()
                        // state.close() 内部会改变 targetValue，触发上面的 LaunchedEffect
                        scope.launch { state.close() }
                    }
            )
        }

        // ── 第三层：抽屉面板 ───────────────────────────────────────────────────
        if (isVisible) {
            Box(
                modifier = Modifier
                    .width(drawerWidthDp)
                    .fillMaxHeight()
                    // 🎓 offset { IntOffset } vs Modifier.offset(dp)
                    //   前者在 Layout 阶段之后的 Draw 阶段偏移，不触发重新测量，性能更好
                    //   适合高频更新的跟手动画
                    .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
                    // 只有右侧两角圆：抽屉贴着屏幕左缘，左侧圆角看不见也没意义。
                    // 数值取自 shapes.extraLarge（令牌），不写 28.dp 字面量 ——
                    // 字面量的代价是换主题/改档位时它不跟着变（规范 §2.5 第 7 条）。
                    .clip(
                        MaterialTheme.shapes.extraLarge.copy(
                            topStart = CornerSize(0.dp),
                            bottomStart = CornerSize(0.dp),
                        )
                    )
                    // ── 拖动手势：直接修改 offsetX，完全跟手 ──────────────────
                    .pointerInput(drawerWidthPx) {
                        val velocityTracker = VelocityTracker()

                        detectHorizontalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPointerInputChange(change)

                                // 直接修改 offsetX → 面板实时跟手，无延迟
                                setOffset(offsetX + dragAmount)
                            },
                            onDragEnd = {
                                val velocityX = velocityTracker.calculateVelocity().x

                                // ── Settle 逻辑：决定最终停在开还是关 ──────────
                                // 优先判断速度（比位置更符合手势直觉）：
                                //   向左快速滑（< -300）→ 关闭
                                //   向右快速滑（> +300）→ 打开
                                //   速度不够快 → 根据当前位置判断过没过一半
                                val safeVelocity = if (velocityX.isFinite()) velocityX else 0f
                                val shouldClose = when {
                                    safeVelocity < -300f -> true
                                    safeVelocity > 300f -> false
                                    else -> offsetX < -drawerWidthPx / 2f
                                }

                                scope.launch {
                                    val target = if (shouldClose) -drawerWidthPx else 0f
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = target,
                                        animationSpec = motionScheme.defaultSpatialSpec()
                                    ) { value, _ -> setOffset(value) }
                                    // 回写 state，让外部感知
                                    if (shouldClose) state.close() else state.open()
                                }
                            }
                        )
                    }
            ) {
                ModalDrawerSheet(
                    // ⚠️ 不能用 surfaceContainerLowest：SPEC_2025 深色下它取 tone 0 = 纯黑，
                    // 会比全 App 基底（surface，tone 6）还暗，形成突兀黑块。
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawerContent()
                }
            }
        }
    }
}

// 🏠 头部 / 📌 菜单项

@Composable
fun DrawerHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 28.dp, top = 40.dp, bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, fontWeight = FontWeight.Medium) },
        selected = selected, onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

/** 抽屉底部信息区（本工程新增：jian_box 原文件没有，抽自 AppRoot 的内联写法）。 */
@Composable
fun DrawerFooter(lines: List<String>) {
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Column(modifier = Modifier.padding(start = 28.dp, bottom = 16.dp)) {
            lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

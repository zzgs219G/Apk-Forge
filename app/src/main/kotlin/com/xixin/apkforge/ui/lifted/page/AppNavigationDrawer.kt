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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val drawerWidthDp = LocalConfiguration.current.screenWidthDp.dp * 0.82f
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }

    // ── offsetX：抽屉面板的水平偏移，单位像素 ─────────────────────────────────
    // 取值范围：-drawerWidthPx（完全关闭）到 0f（完全打开）
    // 这是整个系统唯一的真相来源，所有视觉效果从它派生。
    // 初始值根据 DrawerState 的初始值决定
    var offsetX by remember {
        mutableFloatStateOf(
            if (state.currentValue == DrawerValue.Open) 0f else -drawerWidthPx
        )
    }

    // ── progress：从 offsetX 实时派生，0f=关闭，1f=打开 ──────────────────────
    // derivedStateOf：只有 offsetX 变化时才重新计算，避免多余重组
    val progress by remember {
        derivedStateOf { 1f + (offsetX / drawerWidthPx) }
    }

    // ── 蒙层和面板是否应该存在（progress 归零后就完全移除，节省资源）────────────
    val isVisible by remember {
        derivedStateOf { progress > 0.001f }
    }

    // ── 监听外部 DrawerState 变化，用 animate() 平滑过渡到目标位置 ───────────
    // 🎓 LaunchedEffect(key)：key 变化时重新执行，可被新的执行取消（自动取消旧动画）
    // 这样 state.open() / state.close() 调用就能触发我们自己的动画
    LaunchedEffect(state.targetValue) {
        val targetOffsetX = if (state.targetValue == DrawerValue.Open) 0f else -drawerWidthPx
        // animate()：协程版本的动画，会挂起直到动画完成
        // spring：弹簧动画参数与原生 Material3 Drawer 一致
        animate(
            initialValue = offsetX,
            targetValue = targetOffsetX,
            // 原写法 spring(dampingRatio = 0.8f, stiffness = 380f)（与官方值完全相同）
            animationSpec = motionScheme.defaultSpatialSpec()
        ) { value, _ ->
            offsetX = value
        }
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
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
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
                                // coerceIn：限制在合法范围内，防止拖出边界
                                offsetX = (offsetX + dragAmount).coerceIn(-drawerWidthPx, 0f)
                            },
                            onDragEnd = {
                                val velocityX = velocityTracker.calculateVelocity().x

                                // ── Settle 逻辑：决定最终停在开还是关 ──────────
                                // 优先判断速度（比位置更符合手势直觉）：
                                //   向左快速滑（< -300）→ 关闭
                                //   向右快速滑（> +300）→ 打开
                                //   速度不够快 → 根据当前位置判断过没过一半
                                val shouldClose = when {
                                    velocityX < -300f -> true
                                    velocityX > 300f  -> false
                                    else -> offsetX < -drawerWidthPx / 2f
                                }

                                scope.launch {
                                    if (shouldClose) {
                                        // 先做收尾动画，再同步 state
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = -drawerWidthPx,
                                            // 原写法 spring(dampingRatio = 0.8f, stiffness = 380f)
                                            animationSpec = motionScheme.defaultSpatialSpec()
                                        ) { value, _ -> offsetX = value }
                                        // 回写 state，让外部感知（不会触发重复动画，
                                        // 因为 targetValue 此时已经是 Closed）
                                        state.close()
                                    } else {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = 0f,
                                            // 原写法 spring(dampingRatio = 0.8f, stiffness = 380f)
                                            animationSpec = motionScheme.defaultSpatialSpec()
                                        ) { value, _ -> offsetX = value }
                                        state.open()
                                    }
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

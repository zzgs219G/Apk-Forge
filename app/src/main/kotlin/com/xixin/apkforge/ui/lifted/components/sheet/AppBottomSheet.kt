package com.xixin.apkforge.ui.lifted.components.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 全站唯一的底部弹层外壳。
 *
 * ## 它解决的问题（两个都是「动画被跳过」）
 *
 * **① 进场**：`rememberBottomSheetState(initialValue = Expanded)` 会让 sheet
 *   一进入组合就已经是展开态——`Hidden → Expanded` 的滑入过程**根本不发生**，
 *   蒙层也随之瞬间到位。用户看到的是「点了之后屏幕啪地变暗、卡片凭空出现」。
 *   这里把 `initialValue` 显式钉成 `Hidden`，让组件内部的 `LaunchedEffect`
 *   去 `expand()`，滑入与蒙层淡入都交给 M3 的 `motionScheme` 驱动。
 *
 * **② 退场**：`close = { onDismiss() }` 会让宿主把状态置 null，
 *   `ModalBottomSheet` **立刻从组合中移除**，收起动画来不及跑，卡片瞬间消失。
 *   这里先 `sheetState.hide()` 播完动画，再 `onDismiss()` 通知宿主移除节点。
 *
 * 两条都是「不要自己管框架已经管好的事」——手写状态机的代价是手动关掉动画。
 *
 * ## 为什么抽成组件而不是每处各写一遍
 *
 * 上面两段逻辑必须在每个弹层里**一字不差**地成立。散在三处时，任何一处抄漏
 * 就是一处闪烁，而且不会编译报错、不会崩溃，只能靠盯着屏幕看出来。
 * 入口只有一个，错就犯不出来。
 *
 * ## 关于 `invokeOnCompletion` 里的 `isVisible` 判断
 *
 * 不能无条件 `onDismiss()`：若用户在收起动画途中又触发了别的状态变化
 * （或被协程取消），此时 sheet 仍可见，无条件回调会误关。
 *
 * @param onDismiss 关闭回调（动画播完后才调用）
 * @param modifier 修饰
 * @param content 弹层内容；参数 `close` 供「选完就收起」使用，
 *   它内部走 `hide()` + 完成回调，不要在内容里直接调 `onDismiss()`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()

    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        // 底板比页面卡片高一档（卡是 surfaceContainer，浮层取 High）：
        // 浮层要压住内容，与卡同色就读不出层级。
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // 弹层里可能有输入框，键盘弹出时把弹层顶上去而不是盖住它
                .imePadding()
                .padding(bottom = 12.dp)
        ) {
            content(close)
        }
    }
}

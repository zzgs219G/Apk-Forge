package com.xixin.apkforge.ui.lifted.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 居中的模态对话框 —— **不可关闭**的那种。
 *
 * ## 什么时候该用 Dialog，而不是 BottomSheet
 *
 * 这两个不是风格选项，是语义选项。判据只有一条：
 *
 * > **这个浮层是「用户想不想看」，还是「必须看完」？**
 *
 * | | Dialog（居中、不可滑走） | BottomSheet（底部、可滑走） |
 * |---|---|---|
 * | 语义 | 系统在告诉你一件事，你没得选 | 你在挑一个选项，随时可以放弃 |
 * | 关闭 | 只有内容里的按钮能关 | 下滑、点蒙层、返回键都能关 |
 * | 典型 | 进度、错误、权限请求 | 选择器、详情、动作清单 |
 *
 * ## 本组件为什么必须存在（这不是洁癖）
 *
 * 上一版把「注入进行中」放进了 `AppBottomSheet`，调用方传的是：
 * ```
 * InjectProgressSheet(state = state, onDismiss = { })
 * ```
 * `onDismiss` 是空 lambda，而 `ModalBottomSheet` 的 `onDismissRequest` 照样被触发——
 * 用户往下一拖，sheet 滑走了、蒙层没了，可进度条**还在跑**，
 * 而宿主什么都不知道（因为 onDismiss 什么都没做）。
 * 界面从此停在一个「没有蒙层的半透明残影」状态。
 *
 * 根因是形态选错：**一次正在进行的注入不是一个可以放弃的选择**。
 * 它不该有拖拽把手，不该能下滑关闭，也不该能点蒙层消失。
 * `BasicAlertDialog` 正好是这样：没有 dragHandle、没有下滑手势，
 * 只能由内容里的按钮（或 `onDismissRequest` 里的显式判断）关闭。
 *
 * ## 为什么用 `BasicAlertDialog` 而不是 `AlertDialog`
 *
 * `AlertDialog` 的插槽是 `title / text / confirmButton / dismissButton`——
 * 那是「一句问话 + 两个按钮」的固定形态，塞不下一个 132dp 的波浪进度弧。
 * `BasicAlertDialog` 只提供一个 `Surface` 和内容槽，形态由调用方决定，
 * 但保留了 Dialog 该有的东西：居中、蒙层、焦点抢占、返回键拦截。
 *
 * @param onDismissRequest 返回键 / 点蒙层的处理。
 *   **传空 lambda 即可让对话框无法被关闭**——这正是「进度未完成时不许退出」需要的。
 * @param modifier 修饰
 * @param dismissOnClickOutside 点蒙层是否关闭，默认 false（模态语义）
 * @param dismissOnBackPress 返回键是否关闭，默认 false（模态语义）
 * @param content 内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = false,
    dismissOnBackPress: Boolean = false,
    /**
     * 内容内边距。默认 24dp 是 M3 对话框的标准安全边距。
     *
     * ⚠️ 内容**不要**再自带一层 24dp 水平内边距。曾经有个详情弹窗
     * 把为 bottom sheet 写的行（自己带 `padding(horizontal = 24.dp)`）塞进对话框，
     * 两层边距叠成 48dp：内容被挤到屏幕中间一小条，两边各空掉一个图标宽。
     * 需要更紧的节奏就改这个参数，不要在内容里再包一层。
     */
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        // 这两个开关是 BasicAlertDialog 的固有参数，默认值就是 false。
        // 显式写出来是为了让「这里刻意不可关闭」在调用处一眼可见——
        // 上一版的 bug 正是「以为不可关闭，其实能拖走」。
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress,
        ),
    ) {
        Surface(
            // Dialog 用 large（24dp）：比页面卡片大一级，说明它是浮在页面之上的另一层。
            // 与 bottom sheet 的顶部圆角不同，Dialog 四角全圆——它四面都离开屏幕边缘。
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

/**
 * 对话框标题。与 [AppDialog] 配套，保证全站标题字阶一致。
 */
@Composable
fun AppDialogTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

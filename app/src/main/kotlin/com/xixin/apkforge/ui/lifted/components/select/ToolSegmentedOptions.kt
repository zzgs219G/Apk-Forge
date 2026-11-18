package com.xixin.apkforge.ui.lifted.components.select

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * 工具页「互斥选项」分段选择器（官方 M3E `SingleChoiceSegmentedButtonRow` 封装）。
 *
 * 为什么封装：官方这个组件要手动给每一项算 `SegmentedButtonDefaults.itemShape(index, count)`，
 * 少传或算错会出现「首尾圆角丢失 / 中间出现圆角」的破形；工具页里这个模式出现了多次
 * （时间戳的秒/毫秒、二维码的输出尺寸），统一收在这里，调用方只给一组文案。
 *
 * 与 `FilterChip` 的区别：FilterChip 是「可多选、可取消」的筛选项；
 * 这里的语义是**互斥模式切换**，视觉上也应当连成一条，不要用 chips 拼。
 *
 * @param options 选项文案，顺序即展示顺序
 * @param selectedIndex 当前选中下标
 * @param onSelect 选中回调，回传下标
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSegmentedOptions(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // 标签在格子内居中且不折行：分段按钮的行高是固定的，
                // 一旦折行文字会顶出按钮外（实测「每次进 / 入」四条都变两行）。
                // 传短标签是调用方的责任，这里只保证「折了就截断」而不是破版。
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            )
        }
    }
}

package com.xixin.apkforge.ui.lifted.components.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.lifted.m3e.pressBounceToggleable

/**
 * 选项弹层 —— 手机上「从一组互斥选项里挑一个」的 M3 标准形态。
 *
 * ## 进场/退场两个坑已收进 [AppBottomSheet]
 *
 * 本组件只负责「标题 + 选项」的内容编排。弹层外壳——`Hidden` 初值（让滑入动画
 * 真的发生）、`hide()` 播完再回调（让收起动画真的发生）、底板配色、键盘避让——
 * 全部由 [AppBottomSheet] 承担。
 *
 * 抽出去的理由：这两段逻辑必须在每个弹层里**一字不差**地成立。散在三处时，
 * 任何一处抄漏就是一处闪烁，而且不报错、不崩溃，只能靠盯着屏幕看出来。
 * 入口只有一个，错就犯不出来。
 *
 * ## 为什么是 bottom sheet，而不是另两种形态
 *
 * **① 不用 `DropdownMenu`（锚定浮层）**：菜单语义（贴触发点、宽度只够文字、
 *   自己一套阴影圆角），在表单行下面弹出来像外挂补丁。用户原话「丑爆了」。
 *
 * **② 不用就地展开（手风琴）**：展开会把下面内容顶下去、页面高度跳变。
 *   用户原话「屏幕直接下拉出 UI 选项」「下拉抽屉」——那是网页 FAQ 的形态。
 *
 * **③ bottom sheet**：盖在内容之上（不重排页面）、蒙层建立焦点、
 *   圆角配色与主题同源、高度随内容自适应。M3/M3E 里「在当前页面之上做一次选择」
 *   的官方答案。
 *
 * @param title 弹层标题（说明这次在选什么）
 * @param onDismiss 关闭回调
 * @param content 内容；参数 `close` 供选中后收起
 */
@Composable
fun OptionSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss) { close ->
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        content(close)
    }
}

/**
 * 弹层里的单选/多选行。
 *
 * 选中信号只有「容器色 + 指示器 + 标题字重」——不加竖条（那是装饰冒充信息，
 * jian_box 里被用户裁决删除过）。
 *
 * 整行可点（不用精准点到指示器），指示器自身 `onClick = null`——
 * 由整行消费点击，否则点框不生效（经典坑）。
 */
@Composable
fun SheetOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    multi: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(
                Modifier.pressBounceToggleable(
                    value = selected,
                    role = if (multi) Role.Checkbox else Role.RadioButton,
                    targetScale = 0.98f,
                ) { if (enabled) onClick() }
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick / onCheckedChange 传 null：点击统一由整行处理
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 弹层内的分组小标题。 */
@Composable
fun SheetSectionLabel(
    text: String,
    /** 左缩进。bottom sheet 内容贴边，需要自己缩进；对话框已由容器给边距，传 0.dp。 */
    horizontalPadding: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = horizontalPadding,
            top = 12.dp,
            bottom = 2.dp,
        ),
    )
}

/** 弹层内的动作行（新建 / 清除 / 取消套用之类）。 */
@Composable
fun SheetActionRow(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 弹层底部的一组**纯图标**动作。
 *
 * ## 为什么不再用 [SheetActionRow]（图标 + 一行动字）
 *
 * 详情弹层上一版把「重跑 / 安装 / 定位 / 分享 / 删除」写成五行 `图标 + 文案`。
 * 五行各占 52dp，一共 260dp——弹层一半的高度在重复「这是一个动作」这件事，
 * 而动作本身只有五个图标那么大。用户原话：「图标按钮不要有文本」。
 *
 * 图标动作的价值是**一屏扫完**：五颗并排，眼睛一次就数清有几种操作；
 * 写成五行反而要逐行读，读的还都是「安装产物」这种不言自明的词。
 *
 * ## 没有可见文字，名字从哪来
 *
 * 每颗按钮的 `contentDescription` 取 [SheetIconAction.label]。
 * 这不是「可选的附加信息」——图标按钮没有可见文字，`label` 就是它在
 * 无障碍树里的全部身份。传空字符串等于让读屏用户面对一颗无名按钮。
 *
 * @param actions 从左到右的动作；建议 ≤5 个，再多横向就挤了
 */
@Composable
fun SheetIconActions(
    actions: List<SheetIconAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            if (action.primary) {
                // 主操作（重跑）给一层 tonal 底：五颗图标里只有它需要「鼓」出来，
                // 否则用户得靠位置猜哪个是常用动作。
                FilledTonalIconButton(
                    onClick = action.onClick,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(action.icon, action.label, Modifier.size(22.dp))
                }
            } else {
                IconButton(
                    onClick = action.onClick,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        action.icon,
                        action.label,
                        Modifier.size(22.dp),
                        tint = if (action.danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * [SheetIconActions] 里的一颗图标动作。
 *
 * @param icon 图标
 * @param label **无障碍名称**，同时用于调试定位；界面上不显示
 * @param onClick 点击
 * @param danger 危险动作（删除），图标用 error 色
 * @param primary 主操作，给 tonal 底
 */
data class SheetIconAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val danger: Boolean = false,
    val primary: Boolean = false,
)

package com.xixin.apkforge.ui.lifted.components.state

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 小徽标（短文本 + 圆角底色）。
 *
 * T2.1c 抽自 tools/daily_tools/AppExtractorTool.kt 原 private Badge(text, color, tint)，
 * 该文件内 3 个调用点（版本名 / 系统应用 / 系统）共用同一实现。
 *
 * ⚠️ 命名说明：本组件**不是** material3 自带的 `Badge`。material3 的 Badge 是
 * 「图标右上角的小圆点计数」（配合 BadgedBox 使用），本组件是「圆角底色 + 短文本」的行内标签，
 * 两者形态与用途都不同，故独立命名为 AppBadge 以避免 import 冲突与语义混淆。
 * 项目中 DouyinParserTool / UnifiedParserTool / UIComponentGallery 使用的
 * `Badge { Text(...) }` 均为 material3 版本，不属于本组件范围，本次未动。
 *
 * @param text 徽标文本
 * @param containerColor 底色
 * @param contentColor 文本色
 * @param modifier 修饰
 * @param shape 圆角形状
 * @param textStyle 文本排版
 * @param horizontalPadding 文本左右内边距
 * @param verticalPadding 文本上下内边距
 * @param maxLines 文本最大行数
 */
@Composable
fun AppBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
    maxLines: Int = 1
) {
    Surface(color = containerColor, shape = shape, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            style = textStyle,
            color = contentColor,
            maxLines = maxLines
        )
    }
}

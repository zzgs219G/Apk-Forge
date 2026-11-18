package com.xixin.apkforge.ui.lifted.components.result

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.apkforge.ui.lifted.color.ColorRole
import com.xixin.apkforge.ui.lifted.color.roleColor
import com.xixin.apkforge.ui.lifted.m3e.AnimatedSwap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具页「结果」的语义色调。只影响状态徽标与主数值的取色，不改变布局。
 */
enum class ToolResultTone {
    /** 中性：结果只是信息，无好坏之分（如「相差 12 天」） */
    Neutral,

    /** 正常/成功（如 BMI「正常」、查询成功） */
    Positive,

    /** 需要注意（如 BMI「偏瘦」「超重」） */
    Warning,

    /** 异常/危险（如 BMI「肥胖」、解析失败） */
    Danger
}

/** 结果卡下方的一行次级指标，形如「身高 175 cm」。 */
data class ToolResultMetric(val label: String, val value: String)

/**
 * 工具页结果卡 · 数值型。
 *
 * 解决的问题：小工具算出来的答案原先只是 `log.result()` 里的一行等宽文本，
 * 和运行日志混在一起 —— 用户要「读日志」才能拿到结果。这里把答案提成页面唯一的卡片：
 * 大字号主数值 + 状态徽标 + 次级指标 + 一键复制。
 *
 * 视觉约定（与工具箱主页同源，见 docs/UI设计规范.md §3.2）：
 * - 卡面走 [ColorRole.CardSurface]，elevation 固定 0（非 0 会被 surfaceTint 二次染色）
 * - 卡头是「32dp 圆形图标芯片 + 标题」，与页面 Section 卡头同构，只是更紧凑
 * - 主数值用 `displaySmall + primary`，其余信息一律次级色，保证一屏只有一个视觉焦点
 * - 布局只用 4dp 网格（32 / 12 / 8 / 4）
 *
 * @param label 结果名称（如「BMI 指数」）
 * @param value 主数值（如「21.3」），已格式化，本组件不做数值处理
 * @param unit 数值单位（如「kg/m²」），可空
 * @param status 状态徽标文案（如「正常」），null 不渲染
 * @param tone 状态色调，决定徽标与主数值取色
 * @param note 一句话说明/建议，null 不渲染
 * @param metrics 次级指标行，空列表不渲染
 * @param copyText 可复制内容；null 表示不显示复制按钮
 * @param icon 卡头图标，默认对勾
 * @param modifier 修饰
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolStatResult(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    status: String? = null,
    tone: ToolResultTone = ToolResultTone.Neutral,
    note: String? = null,
    metrics: List<ToolResultMetric> = emptyList(),
    copyText: String? = null,
    icon: ImageVector = Icons.Rounded.Check
) {
    // 结果卡是页面上唯一"上色"的容器：主色只花在答案上，输入卡保持中性
    val onContainer = resultOnContainer(tone)

    ResultCard(containerColor = resultContainer(tone), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResultHeaderChip(
                icon = icon,
                // 彩色卡面上用 surface 做芯片底：浅色下是白点、深色下是近黑点，两种模式都有对比
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = onContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = onContainer
            )
            if (copyText != null) {
                CopyAction(copyText, onContainer)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = onContainer
            )
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (!status.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer
            )
        }

        if (metrics.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = onContainer.copy(alpha = 0.22f))
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                metrics.forEach { metric ->
                    Column {
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainer
                        )
                        Text(
                            text = metric.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具页结果卡 · 文本型。
 *
 * 面向「输出是一段可复制的文本」的工具（Base64 / MD5 / JSON 压缩等）：
 * 文本块用等宽字体 + 次级容器色，视觉上读作「代码块」而不是第二张卡。
 *
 * ⚠️ 2026-11 用户报「结果内容太多居然是省略号，居然不支持上下滑动」：
 * 此前文本块用 `maxLines` 截断 + 省略号，等于把「完整结果」藏了起来——
 * 结果卡是这一页的主产出，用户想看完整内容不该再去点开「运行详情」。
 * 现在改为**限高 + 卡内可滚动**（[maxHeight]），文本再长也能在卡里划到底；
 * [maxLines] 只作为极端情况的兜底（默认不限，传正数才会截断）。
 *
 * @param label 结果名称
 * @param text 结果文本
 * @param modifier 修饰
 * @param maxHeight 文本块最大高度，超出后在块内纵向滚动（默认 [DefaultTextMaxHeight]）
 * @param maxLines 兜底截断行数，默认 [Int.MAX_VALUE] 即不截断
 * @param copyText 可复制内容；null 表示不显示复制按钮
 * @param icon 卡头图标
 */
@Composable
fun ToolTextResult(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = DefaultTextMaxHeight,
    maxLines: Int = Int.MAX_VALUE,
    copyText: String? = text,
    icon: ImageVector = Icons.Rounded.Check
) {
    // 文本结果仍走中性卡面：一整块彩色底 + 等宽文本会显得脏，主色只留给数值型结果
    ResultCard(containerColor = roleColor(ColorRole.CardSurface), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResultHeaderChip(
                icon = icon,
                containerColor = roleColor(ColorRole.IconChip),
                contentColor = roleColor(ColorRole.IconChipContent)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (copyText != null) {
                CopyAction(copyText)
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 长按可选中/复制：结果块的一半价值在"把内容拿走"，
            // 只给右上角复制按钮不够用（用户常常只想取其中一段）
            //
            // 滚动容器包在 SelectionContainer 外层：长按拖选到边缘时纵向滚动仍然生效
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

// ══════════════════ 内部零件 ══════════════════

/** 两种结果卡共用的卡壳：由调用方决定容器色，XL 圆角、零 elevation。 */
@Composable
private fun ResultCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

/** 卡头 32dp 圆形图标芯片：与页面 Section 卡头同构，尺寸按紧凑页取 32dp。 */
@Composable
private fun ResultHeaderChip(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 复制按钮：点击后图标在 1.2s 内切为对勾。
 *
 * 动效取官方槽位（缩放＝spatial、透明度＝effects），不手填 spring 数字；
 * 交互反馈沿用 LogPanel 的既有语义：按下缩小、成功放大。
 *
 * @param tint 常态图标色；有色卡面上需传入 onXContainer，否则在彩色底上会看不清
 */
@Composable
private fun CopyAction(copyText: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("result", copyText)))
                copied = true
                delay(1200)
                copied = false
            }
        }
    ) {
        // 手写 transitionSpec 已收进 AnimatedSwap（M3E 铁律第 3 条：
        // 禁止业务代码自己写 transitionSpec）。这里原来抄的就是它的逻辑，
        // 抄一半就会漏掉缩放、只剩淡入淡出 —— 观感是「图标闪了一下」。
        AnimatedSwap(targetState = copied, label = "result_copy") { success ->
            Icon(
                imageVector = if (success) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (success) "已复制" else "复制结果",
                modifier = Modifier.size(20.dp),
                tint = if (success) MaterialTheme.colorScheme.primary else tint
            )
        }
    }
}

/**
 * 结果卡的容器色：按语义色调选 M3 容器槽位。
 *
 * 「输入卡克制、结果卡上色」是这次改版的取舍 —— 主色只在**答案**上出现一次，
 * 既能让人一眼看到结果，也不会让页面变成大片紫（规范 §1.3.1 的教训）。
 */
@Composable
private fun resultContainer(tone: ToolResultTone): Color = when (tone) {
    ToolResultTone.Danger -> MaterialTheme.colorScheme.errorContainer
    ToolResultTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    ToolResultTone.Neutral,
    ToolResultTone.Positive -> MaterialTheme.colorScheme.primaryContainer
}

/** 与 [resultContainer] 配对的内容色，保证容器上的文字对比度。 */
@Composable
private fun resultOnContainer(tone: ToolResultTone): Color = when (tone) {
    ToolResultTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
    ToolResultTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
    ToolResultTone.Neutral,
    ToolResultTone.Positive -> MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * 文本结果块的默认最大高度（约 8~9 行等宽 12sp）。
 *
 * 为什么是「限高 + 可滚动」而不是「截断 + 省略号」：结果卡是工具页的主产出，
 * 用户要的是完整内容；超出屏幕就让它自己滚，而不是把一半藏起来。
 * 高度不按行数算，是因为同一个工具的输出可能一行很长（JSON 压缩后就是一行），
 * 按行限高会让它撑满整屏，按高度限高才和「大约看多少内容」对得上。
 */
internal val DefaultTextMaxHeight = 200.dp

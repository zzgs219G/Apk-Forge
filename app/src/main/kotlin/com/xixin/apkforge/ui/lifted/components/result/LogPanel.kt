package com.xixin.apkforge.ui.lifted.components.result

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixin.apkforge.ui.lifted.components.rememberCopyText
import com.xixin.apkforge.ui.lifted.m3e.AnimatedSwap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志面板 —— **整体搬运自 jian_box `wrapper/common/log/LogPanel.kt`**。
 *
 * 搬的时候只换了视觉令牌与平台适配，组件能力一个没删：
 *  · 容器/圆角：`RoundedCornerShape(16.dp)` → `MaterialTheme.shapes.large`（本项目禁字面量）；
 *  · 容器色：`surface` → `surfaceContainerLow`（与结果卡同一层级，不靠描边分块）；
 *  · 复制：jian_box 的 `copy()` 扩展 → 本项目的 `rememberCopyText()`；
 *  · 加载指示器：`AppLoadingIndicator` → `CircularProgressIndicator`（本项目没有前者）；
 *  · 时间戳：`formatDate()` → 本地 `SimpleDateFormat("HH:mm:ss")`；
 *  · 标题「运行日志」→「执行日志」（本项目全站叫法，避免同一样东西两个名字）。
 *
 * 留着的参数都是**故意留着**的：`isLoading` / `showClearButton` / `hideWhenEmpty`
 * 在结果页用不到，但组件是给「正在流式输出的日志」准备的——进度对话框、批量注入
 * 都可能是下一个调用方。调用方不需要的能力传 false，不要在组件里替它砍掉。
 *
 * @param logs 日志状态（见 [LogState]）
 * @param isError 失败态：容器转 errorContainer，正文转 onErrorContainer
 * @param isLoading 正在跑：标题右侧显示加载指示器，此时不显示复制/清空
 * @param showClearButton 是否给清空按钮。**结果页传 false**：一次已经结束的注入，
 *        日志就是证据；给一颗删除键既没有下一步动作，又站在最容易误触的位置
 * @param hideWhenEmpty 无日志且未运行且无状态时整体不渲染
 * @param maxHeight 正文最大高度，超出后内部滚动
 * @param emptyText 空态一行说明
 */
@Composable
fun LogPanel(
    logs: LogState,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isLoading: Boolean = false,
    showClearButton: Boolean = true,
    hideWhenEmpty: Boolean = false,
    maxHeight: Dp = 420.dp,
    emptyText: String = "这次没有留下日志",
) {
    var copyState by remember { mutableStateOf(CopyState.IDLE) }
    val scope = rememberCoroutineScope()
    val copy = rememberCopyText()
    val logContent = logs.logText

    if (hideWhenEmpty && logContent.isEmpty() && logs.statusMsg.isEmpty() && !isLoading && !isError) return

    // 按下缩到 0.85、成功后弹到 1.15：反馈只靠这颗图标，不额外弹 toast。
    val buttonScale by animateFloatAsState(
        targetValue = when (copyState) {
            CopyState.PRESSING -> 0.85f
            CopyState.SUCCESS -> 1.15f
            CopyState.IDLE -> 1f
        },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "logCopyScale",
    )

    val dim = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            // 日志从空到有、从短到长时不硬切：高度跟着内容走。
            .animateContentSize(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 标题固定为「执行日志」，**不拼接 statusMsg**。
                    //
                    // 原先写的是 `执行日志 · ${statusMsg}`，而 statusMsg 里带着
                    // 产物文件名（可以 60+ 字符）。标题行一旦被长串占满，
                    // 右侧的行数与复制按钮就被挤到看不见 —— 用户最常用的
                    // 恰恰是那颗复制键。
                    // statusMsg 改为下方独立一行、单行省略，需要时点复制拿全文。
                    Text(
                        "执行日志",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${logContent.lineSequence().count { it.isNotBlank() }} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isLoading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (logContent.isNotEmpty() && !isLoading) {
                        if (showClearButton) {
                            val clearInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable(
                                        interactionSource = clearInteraction,
                                        indication = null,
                                    ) { logs.clearLogs() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "清空",
                                    modifier = Modifier.size(19.dp),
                                    tint = dim,
                                )
                            }
                        }

                        val copyInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .scale(buttonScale)
                                .clickable(
                                    interactionSource = copyInteraction,
                                    indication = null,
                                ) {
                                    scope.launch {
                                        copyState = CopyState.PRESSING
                                        delay(100)
                                        copy(logs.copyText)
                                        copyState = CopyState.SUCCESS
                                        delay(1200)
                                        copyState = CopyState.IDLE
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedSwap(
                                targetState = copyState == CopyState.SUCCESS,
                                label = "logCopyIcon",
                            ) { done ->
                                Icon(
                                    if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = if (done) "已复制" else "复制日志",
                                    modifier = Modifier.size(19.dp),
                                    tint = when {
                                        done -> MaterialTheme.colorScheme.primary
                                        isError -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // statusMsg 独立一行：成功/失败 + 摘要。
            //
            // 放在标题下方而不是拼进标题：它的内容长度不可控（含产物文件名），
            // 拼进标题会把右侧的行数/复制键挤掉。这里单行省略，全文可复制。
            if (logs.statusMsg.isNotEmpty()) {
                Text(
                    logs.statusMsg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }

            HorizontalDivider(
                color = if (isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )

            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            logContent.ifEmpty { emptyText },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (logContent.isEmpty()) FontFamily.Default
                                else FontFamily.Monospace,
                                // ⚠️ 行高必须跟着字号走，不能写死。
                                //
                                // 原来写 `lineHeight = 18.sp`，而 bodySmall 是 12sp
                                // → 行距 1.5 倍，中文等宽字体下换行后上下空隙明显，
                                // 一屏只能塞十几行，看着「又大又散」。
                                // 1.35 倍是代码类文本的常用值（IDE 默认区间 1.2~1.4），
                                // 既有呼吸感又不浪费垂直空间。
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.sp,
                            ),
                            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 复制按钮的三态。搬自 jian_box 同名枚举。 */
enum class CopyState { IDLE, PRESSING, SUCCESS }

/**
 * 日志状态。搬自 jian_box `LogState`，语义逐条保留：
 *
 *  · [addLog] 带 `HH:mm:ss` 墙钟时间戳（`logText` 里渲染成 `[10:24:31] ...`）；
 *  · [addRaw] 原样追加，不盖时间戳——已经自带 `+1.24s` 的流水线日志走这个；
 *  · [copyText] 优先只复制 raw 段：混了墙钟戳和相对耗时两种时间轴时，
 *    贴出去的那份不该被系统时间污染。
 *
 * 结果页只用 [addRaw]（它拿到的是 `renderLog()` 生成的整段文本）。
 */
class LogState {
    var statusMsg by mutableStateOf("")
    private val _logs = mutableStateListOf<Pair<String, String>>()

    val logText by derivedStateOf {
        _logs.joinToString("\n") {
            if (it.first.isEmpty()) it.second else "[${it.first}] ${it.second}"
        }
    }

    val copyText by derivedStateOf {
        val rawLogs = _logs.filter { it.first.isEmpty() }
        if (rawLogs.isNotEmpty()) rawLogs.joinToString("\n") { it.second } else logText
    }

    val logs get() = _logs

    fun setStatus(msg: String) {
        statusMsg = msg
    }

    fun addLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.add(timestamp to msg)
    }

    fun addRaw(msg: String) {
        _logs.add("" to msg)
    }

    fun clearLogs() {
        _logs.clear()
        statusMsg = ""
    }

    fun result(msg: String, content: String) {
        setStatus("成功 · $msg")
        addRaw(content)
    }

    fun error(msg: String, detail: String) {
        setStatus("失败 · $msg")
        addLog(detail)
    }
}

/**
 * 与页面生命周期绑定的日志状态：离开页面即清空，避免日志残留占内存。
 * 搬自 jian_box `rememberScreenLogState`。
 */
@Composable
fun rememberScreenLogState(): LogState {
    val logState = remember { LogState() }
    DisposableEffect(Unit) {
        onDispose { logState.clearLogs() }
    }
    return logState
}

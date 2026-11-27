package com.xixin.apkforge.ui.screen.uncheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.core.HumanSize
import com.xixin.apkforge.core.uncheck.UncheckPipeline
import com.xixin.apkforge.feature.uncheck.UncheckUiState
import com.xixin.apkforge.feature.uncheck.UncheckViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.components.ApkIcon
import com.xixin.apkforge.ui.lifted.components.card.SectionCard
import com.xixin.apkforge.ui.lifted.components.result.LogPanel
import com.xixin.apkforge.ui.lifted.components.select.ToolSegmentedOptions
import com.xixin.apkforge.ui.lifted.page.AppTopBar
import androidx.compose.material3.Switch

/**
 * 去签名校验页。
 *
 * ## 布局：竖着三段，每段回答一个问题
 *
 * ```
 * 顶栏
 * ─────────────────────────────────────────
 * ① 这是什么包       图标 + 名字 + 包名/版本
 * ─────────────────────────────────────────
 * ② 怎么处理         拦截范围（开关 + 档位）
 *                    ——只在没跑过时出现
 * ─────────────────────────────────────────
 * ③ 跑得怎么样       进度 / 结果 / 日志
 *                    ——按状态切换，不叠加
 * ─────────────────────────────────────────
 * 底部               主按钮（贴底整条，不是 FAB）
 * ```
 *
 * ## 这一版修掉的五个问题
 *
 * **① 配置行埋在日志区里**
 * 上一版把「拦截范围」当成 LazyColumn 的一个 item，排在日志面板**之后**。
 * 用户滚到下面才看得见，而它决定的是「点开始处理会发生什么」——
 * 决定性选项不该出现在日志后面。现在把它提到独立的分区卡，
 * 位于包信息与日志之间，顺序即阅读顺序。
 *
 * **② FAB 压住日志最后一行**
 * 上一版是右下角 FAB + `contentPadding(bottom = 96.dp)`。但日志面板
 * `maxHeight = 360.dp` 且自己内部滚动，FAB 悬浮在它上面，最后一行
 * 永远被切掉一截。改成**贴底整条按钮**：它参与布局、占自己的高度，
 * 日志区自动避让，不需要任何魔法数字。
 *
 * **③ 空态说"选一个 APK"但本页没有选择器**
 * 那是遗留文案。本页的包来自控制台（见文件头 ③），空态应该直接说
 * 「回控制台选包」，而不是让用户在本页找不存在的入口。
 *
 * **④ 状态点与结果行重复**
 * 上一版头部右边一个「已完成」，下面又一行「完成 · 21.4 MB」。
 * 状态点只保留"进行中"，完成/失败由结果区自己表达。
 *
 * **⑤ 结果塞在任务头里**
 * 路径、复用省了多少、签名提示全挤在头部，日志一多就被顶走。
 * 结果单独成卡，跑完自动滚到可见位置。
 */
@Composable
fun UncheckScreen(
    onOpenDrawer: () -> Unit,
    vm: UncheckViewModel,
    /**
     * 返回控制台。
     *
     * 去校验是**控制台的模块**：控制台选包 → 选「去签名校验」，
     * 所以退出时回控制台，而不是退出到某个上传页（那个页面已经不存在了）。
     */
    onBack: () -> Unit = {},
) {
    val ui = vm.ui
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 跑完自动滚到结果卡。
    //
    // 为什么需要：结果卡排在「包信息 / 选项 / 结果 / 日志」的第 3 位，
    // 而日志面板最高 360dp 且自带内部滚动。用户点「开始处理」时视线停在
    // 底部按钮，跑完若不滚，看到的是日志区 —— 产物路径、签名提示全在
    // 屏幕外。滚到结果卡让「下一步该干什么」直接可见。
    //
    // 只在「从无结果变成有结果」时滚一次：用 prev 记住上一次，避免每次
    // 重组都滚（那样用户手动往上翻会被拽回来）。
    val prevResult = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<UncheckPipeline.Result?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(ui.result) {
        if (ui.result != null && prevResult.value == null) {
            // 索引 2 = AppCard(0) + OptionsCard(1) + ResultCard(2)
            listState.animateScrollToItem(2)
        }
        prevResult.value = ui.result
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "去签名校验",
            onMenuClick = onOpenDrawer,
            actions = {
                if (ui.inputApk != null && !ui.running) {
                    IconButton(onClick = { vm.clearInput(); onBack() }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "换一个包",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 8.dp,
                // 底部不留魔法数字：按钮在 LazyColumn 外面，不遮挡内容
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ① 包信息
            item { AppCard(ui, onBack) }

            // ② 处理方式：有包、没在跑就显示。
            //
            // ⚠️ 不要加 `ui.result == null`（上一版有，是 bug）。
            // 那会导致：跑成功后想改档位重跑，选项却消失了 —— 而
            // 「跑完发现 App 打不开、要降档重试」恰恰是最需要改配置的时刻。
            if (ui.inputApk != null && !ui.running) {
                item {
                    OptionsCard(
                        state = ui,
                        onToggleNative = vm::setNativeLayer,
                        onSelectLevel = vm::setHookLevel,
                    )
                }
            }

            // ②.5 进度：跑起来就占一个位置，让用户看到"在动"
            if (ui.running) {
                item { ProgressCard(ui) }
            }

            // ③ 结果 / 错误：互斥，只可能有一个
            ui.result?.let { r ->
                item { ResultCard(r, hasSignature = r.hadSignature) }
            }
            ui.error?.let { msg ->
                item { ErrorCard(msg, onDismiss = vm::clearError) }
            }

            // 日志：跑过才有内容，自带复制
            item {
                LogPanel(
                    logs = vm.logs,
                    isError = ui.error != null,
                    isLoading = ui.running,
                    showClearButton = true,
                    hideWhenEmpty = true,
                    maxHeight = 360.dp,
                )
            }
        }

        // ── 底部主按钮 ──
        //
        // 不用 FAB：FAB 悬浮在内容之上，而本页内容（日志面板）需要
        // 完整可读。贴底整条按钮参与布局，内容区自动避让 —— 不需要
        // 任何 `contentPadding(bottom = 96.dp)` 这种调出来就忘的数字。
        BottomAction(
            state = ui,
            onPick = onBack,
            onRun = vm::run,
        )
    }
}

// ══════════════════════ ① 包信息 ══════════════════════

/**
 * 包身份。
 *
 * 认包靠三样：图标（最快分辨）、应用名（人话）、包名（唯一）。
 * 文件名与体积退到第二行 —— 需要去文件管理器找它时才用得上。
 */
@Composable
private fun AppCard(state: UncheckUiState, onBack: () -> Unit) {
    if (state.inputApk == null) {
        // 空态：本页没有文件选择器，直接说清楚该去哪
        SectionCard(title = "尚未选择 APK", icon = Icons.Rounded.Security) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(
                    "去签名校验需要先有一个包。选包在控制台完成 —— 选完会问你做哪件事。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(10.dp)
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("去控制台选包", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        return
    }

    SectionCard(
        title = "目标 APK",
        icon = Icons.Rounded.FolderOpen,
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApkIcon(
                icon = state.meta?.icon,
                fallback = state.displayName,
                size = 44.dp,
            )
            HGap(12.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayName.ifBlank { "已选择 APK" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(2.dp)
                Text(
                    listOfNotNull(
                        state.meta?.packageName,
                        state.meta?.versionName?.let { "v$it" },
                        if (state.readingMeta) "读取中…" else HumanSize.format(state.inputSize),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ══════════════════════ ② 处理方式 ══════════════════════

/**
 * 拦截范围。
 *
 * ## 为什么这两个开关必须让用户看见
 *
 * 上游 2026-11-27 两次真机实测，都是「处理后 App 不正常」：
 *
 * ```
 * JMComic（WebView 应用，签名校验只用 PackageManager，零 native 代码）
 *   只做 Java 层      → 无盗版警告 + 网络正常
 *   Java + native     → 网络与图片加载全部失败
 * ```
 *
 * 没有这两个开关，用户只能认为工具坏了。所以它们不是"高级选项"，
 * 是正常使用中随时可能需要调的东西 —— 放在主流程里，不折叠。
 */
@Composable
private fun OptionsCard(
    state: UncheckUiState,
    onToggleNative: (Boolean) -> Unit,
    onSelectLevel: (Int) -> Unit,
) {
    val levels = listOf(
        UncheckPipeline.HookLevel.CORE to "最小",
        UncheckPipeline.HookLevel.AT to "标准",
        UncheckPipeline.HookLevel.FULL to "完整",
    )
    val idx = levels.indexOfFirst { it.first == state.hookLevel }.coerceAtLeast(0)

    SectionCard(
        title = "拦截范围",
        subtitle = if (state.nativeLayer) "覆盖 App 自己读 APK 的校验" else "只拦 PackageManager",
        icon = Icons.Rounded.Security,
    ) {
        // 开关行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("拦截 native 层", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.nativeLayer) "会重定向 App 的 APK 文件读取"
                    else "不碰文件读取，只改系统返回值",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.nativeLayer, onCheckedChange = onToggleNative)
        }

        if (state.nativeLayer) {
            Gap(4.dp)
            Text(
                "挂多少个函数",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Gap(6.dp)
            ToolSegmentedOptions(
                options = levels.map { it.second },
                selectedIndex = idx,
                onSelect = { onSelectLevel(levels[it].first) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
            Gap(8.dp)
            Hint(
                when (state.hookLevel) {
                    UncheckPipeline.HookLevel.CORE ->
                        "只拦最关键的 7 个文件操作。装好后 App 打不开、加载不出内容时用这档。"
                    UncheckPipeline.HookLevel.AT ->
                        "加上路径反查相关的拦截。介于两者之间。"
                    else ->
                        "覆盖最全。若处理后 App 网络或图片异常，降到「最小」，或直接关掉上面的开关。"
                }
            )
        } else {
            Gap(8.dp)
            Hint(
                "关掉后 App 在 native 里自己读 APK 校验签名的路径拦不住。" +
                    "但 WebView 类应用的网络与图片不会被打扰。"
            )
        }
    }
}

// ══════════════════════ ③ 进度 / 结果 ══════════════════════

@Composable
private fun ProgressCard(state: UncheckUiState) {
    SectionCard(title = "处理中", subtitle = state.stage, icon = Icons.Rounded.PlayArrow) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall),
            )
            if (state.stageDetail.isNotBlank()) {
                Gap(8.dp)
                Text(
                    state.stageDetail,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 结果。
 *
 * 三件事按重要性排：**产物在哪**（下一步要用）→ **它有什么特点**（体积、证书）
 * → **怎么签**（关键约束）。路径用等宽字体且不截断 —— 用户要把它复制出去，
 * 截断等于把答案删了（`AGENTS.md` §1.8）。
 */
@Composable
private fun ResultCard(r: UncheckPipeline.Result, hasSignature: Boolean) {
    SectionCard(
        title = "处理完成",
        subtitle = HumanSize.format(r.sourceSize) + " → " + HumanSize.format(r.outputSize),
        icon = Icons.Rounded.PlayArrow,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            // 产物路径：独占一行、等宽、可选中复制
            Text(
                r.output.absolutePath,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Gap(10.dp)

            KeyValue("新 dex", r.newDex)
            KeyValue("证书指纹", r.certSha256Short)
            r.originalApp?.let { KeyValue("原 Application", it) }
            if (r.savedByReuse > 0) {
                KeyValue("数据复用省下", HumanSize.format(r.savedByReuse))
            }

            Gap(10.dp)
            // 唯一必须说的约束：产物未签名，且签名方式有限制
            Hint(
                "未签名。签名请用 MT 管理器这类手写 ZIP 的工具 —— " +
                    "apksigner 会重写 ZIP、把复用的体积优势抵消掉。",
                emphasis = true,
            )

            // 能力边界：只在确实缺了 native 能力时说，不常驻
            if (!r.nativeCapable) {
                Gap(6.dp)
                Hint(
                    if (!r.nativeLayer) "本次只做了 Java 层：App 自己读 APK 的校验拦不住。"
                    else "原包没有 v2/v3 签名块，native 层读不到证书 —— " +
                        "App 自己读 APK 的校验可能拦不住。",
                    emphasis = true,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    SectionCard(title = "处理失败", icon = Icons.Rounded.Close) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.error,
            )
            Gap(8.dp)
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text("知道了", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ══════════════════════ 底部主按钮 ══════════════════════

/**
 * 底部主操作。
 *
 * 全页唯一入口，状态决定它做什么：
 *
 * ```
 * 没包     → 去控制台选包
 * 有包     → 开始处理
 * 处理中   → 禁用（显示"处理中"）
 * 已完成   → 再处理一次
 * ```
 *
 * 用整条按钮而不是 FAB：本页的核心内容（日志）需要完整可读，
 * FAB 悬浮必然遮住最后一行。整条按钮参与布局，内容区自动避让。
 */
@Composable
private fun BottomAction(
    state: UncheckUiState,
    onPick: () -> Unit,
    onRun: () -> Unit,
) {
    val running = state.running
    val hasApk = state.inputApk != null

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 8.dp,
                bottom = 12.dp,
            )
    ) {
        Button(
            onClick = { if (hasApk) onRun() else onPick() },
            enabled = !running,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (hasApk) Icons.Rounded.PlayArrow else Icons.Rounded.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            HGap(8.dp)
            Text(
                when {
                    running -> "处理中…"
                    !hasApk -> "去控制台选包"
                    state.result != null -> "再处理一次"
                    else -> "开始处理"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ══════════════════════ 小组件 ══════════════════════

/** 结果里的一行「键 : 值」。值用等宽，便于对照日志。 */
@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 说明性小字。`emphasis` = 影响下一步动作的约束，用正文色而不是灰。 */
@Composable
private fun Hint(text: String, emphasis: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasis) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

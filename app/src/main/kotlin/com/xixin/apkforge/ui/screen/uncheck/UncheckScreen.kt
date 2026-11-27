package com.xixin.apkforge.ui.screen.uncheck

import com.xixin.apkforge.core.HumanSize

import androidx.compose.material3.Switch
import com.xixin.apkforge.core.uncheck.UncheckPipeline
import com.xixin.apkforge.ui.lifted.components.select.ToolSegmentedOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.feature.uncheck.UncheckUiState
import com.xixin.apkforge.feature.uncheck.UncheckViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.HGap
import com.xixin.apkforge.ui.lifted.components.ApkIcon
import com.xixin.apkforge.ui.lifted.components.result.LogPanel
import com.xixin.apkforge.ui.lifted.components.state.AppEmptyState
import com.xixin.apkforge.ui.lifted.page.AppTopBar

/**
 * 去签名校验页。
 *
 * ## 与上一版的差别（这版是产品，不是示例页）
 *
 * 上一版四张卡片：载荷状态 / 输入 APK / 进度 / 结果 / 边界说明。
 * 问题是**每一张都在解释自己**，首屏最贵的位置放的是用户既不能选、也不需要知道的
 * 载荷版本号；底部还有一张「能做什么 / 做不到什么」——那是 README，不是界面。
 *
 * 这一版照控制台的结构走（同一个 App 里两个页面不该有两套语法）：
 *
 * ```
 * 顶栏
 * ├─ 任务区   空 → 一句话 + 选包入口；有包 → 图标/名字/包名/状态/结果
 * └─ 日志     跑过才有，自带复制
 * 右下角      唯一主操作：选包 → 开始处理 → 处理中
 * ```
 *
 * 三条取舍：
 *
 * ① **没有「刷新载荷」**。载荷是内部依赖，处理时自动取（缓存优先）。
 *    给按钮等于把内部管理责任推给用户，而且用户会误以为不刷新就用不了。
 *
 * ② **没有边界说明卡**。做不到的事在**失败时**用错误信息说，不在首屏常年占位。
 *    唯一保留的一处是「产物未签名」——它直接影响用户下一步动作，属于必须说。
 *
 * ③ **本页没有文件选择器**。选包只在控制台发生一次，选完再问「做哪件事」。
 *    这个页面只接收控制台传进来的包（`AppRoot` 里 `uncheck.setInput(picked)`）。
 *    上一版它自己带一个上传入口，与控制台是两个同义的页面——那才是重复的来源。
 */
@Composable
fun UncheckScreen(
    onOpenDrawer: () -> Unit,
    vm: UncheckViewModel,
    /**
     * 返回控制台。
     *
     * 去校验是**控制台的模块**，不是独立入口：控制台选包 → 选「去签名校验」，
     * 所以退出时回控制台，而不是退出到某个「上传页」（那个页面已经不存在了）。
     */
    onBack: () -> Unit = {},
) {
    val ui = vm.ui

    fun primary() {
        when (ui.primary) {
            UncheckUiState.Primary.PICK -> onBack()   // 没包时主按钮回控制台去选
            UncheckUiState.Primary.RUN -> vm.run()
            UncheckUiState.Primary.RUNNING -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "去签名校验",
                onMenuClick = onOpenDrawer,
                actions = {
                    if (ui.inputApk != null && !ui.running) {
                        // 「换一个包」= 回控制台重新选。
                        // 不能只调 clearInput()：那样会停在一个空白页，
                        // 而本页已经没有文件选择器了（选包只在控制台）。
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

            // 任务区在 LazyColumn 外面：它是「现在」，不该跟着日志一起滚走
            // （与控制台 TaskHeader 同一条理由）。
            if (ui.inputApk != null) {
                TaskHeader(
                    state = ui,
                    modifier = Modifier.padding(
                        horizontal = AppConstants.SCREEN_EDGE_PADDING,
                        vertical = 4.dp,
                    ),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = AppConstants.SCREEN_EDGE_PADDING,
                    end = AppConstants.SCREEN_EDGE_PADDING,
                    top = 4.dp,
                    // 给悬浮按钮留空间，否则最后一行被压住
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.inputApk == null) {
                    item {
                        AppEmptyState(
                            title = "让签名校验放过重签的包",
                            description = "选一个 APK，把原签名伪造进它的运行环境。" +
                                "产物存到 Download/ApkForge，未签名。",
                            action = {
                                // 这里不提供文件选择器：选包只有控制台一处（见文件头 ③）。
                                // 兜底给一个回控制台的入口，避免用户卡在空页。
                                TextButton(onClick = onBack) {
                                    Text("去控制台选包", style = MaterialTheme.typography.labelLarge)
                                }
                            },
                        )
                    }
                }

                // 错误：跑挂了才出现，不常驻
                ui.error?.let { msg ->
                    item { ErrorLine(msg, onDismiss = vm::clearError) }
                }

                // native 层开关 + 档位：只在选了包、还没跑的时候出现。
                //
                // 两个正交维度，必须分开讲：
                //   · 要不要 native 层  → 打不开/加载不出内容时关掉
                //   · native 挂多少个   → 只在开着时才有意义
                if (ui.inputApk != null && !ui.running && ui.result == null) {
                    item {
                        NativeLayerRow(
                            state = ui,
                            onToggle = vm::setNativeLayer,
                            onSelectLevel = vm::setHookLevel,
                        )
                    }
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
        }

        // ── 右下角唯一主操作 ──
        // ⚠️ 必须显式 align(BottomEnd)：Box 的子节点默认落 TopStart，
        //    不写会叠到顶栏上（踩坑记录 坑 27）。
        ExtendedFloatingActionButton(
            onClick = ::primary,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(
                    if (ui.primary == UncheckUiState.Primary.PICK) Icons.Rounded.FolderOpen
                    else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                )
            },
            text = {
                Text(
                    when (ui.primary) {
                        UncheckUiState.Primary.PICK -> "去选包"
                        UncheckUiState.Primary.RUNNING -> "处理中"
                        UncheckUiState.Primary.RUN -> if (ui.result != null) "再处理一次" else "开始处理"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = AppConstants.SCREEN_EDGE_PADDING, bottom = 20.dp),
        )
    }
}

// ══════════════════════ 任务区 ══════════════════════

/**
 * 当前任务：包身份 + 状态 + 结果。
 *
 * 刻意**不做卡片**（与控制台 TaskHeader 一致）：一屏之内最重的块不该只装
 * 「哪个包、跑完了没」。用图标 + 两行文字 + 一个状态点，信息密度更高、
 * 视觉噪音更低。
 */
@Composable
private fun TaskHeader(state: UncheckUiState, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApkIcon(
                icon = state.meta?.icon,
                fallback = state.displayName,
                size = 40.dp,
            )
            HGap(11.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    state.displayName.ifBlank { "已选择 APK" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(1.dp)
                Text(
                    // 包名 + 版本：认包靠这两个，文件名人眼扫不出来
                    listOfNotNull(
                        state.meta?.packageName,
                        state.meta?.versionName?.let { "v$it" },
                        if (state.readingMeta) "读取中…" else null,
                    ).joinToString(" · ").ifBlank {
                        HumanSize.format(state.inputSize)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HGap(8.dp)
            StatusDot(state)
        }

        // 进度：只在跑的时候出现，一条就够，不做卡片
        if (state.running) {
            Gap(10.dp)
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall),
            )
            Gap(6.dp)
            Text(
                if (state.stageDetail.isBlank()) state.stage else "${state.stage} · ${state.stageDetail}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 结果：一行状态 + 产物路径。点整行看日志（日志就在下面，这里只是提示可滚）
        state.result?.let { r ->
            Gap(10.dp)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                HGap(8.dp)
                Text(
                    "完成 · ${HumanSize.format(r.outputSize)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (r.savedByReuse > 0) {
                    Text(
                        "复用省 ${HumanSize.format(r.savedByReuse)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Gap(6.dp)
            Text(
                r.output.absolutePath,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            // 唯一保留的"提示"：产物未签名，且必须用会保持 ZIP 重叠的工具签。
            // 这不是解释产品能力，是直接影响用户下一步动作的一句话。
            Gap(4.dp)
            Text(
                "未签名。签名请用 MT 管理器 —— apksigner 会重写 ZIP 让体积翻倍。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(state: UncheckUiState) {
    val (color, label) = when {
        state.running -> MaterialTheme.colorScheme.tertiary to "处理中"
        state.error != null -> MaterialTheme.colorScheme.error to "失败"
        state.result != null -> MaterialTheme.colorScheme.primary to "已完成"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "待处理"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        HGap(6.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * 错误：一行 + 展开看详情。
 *
 * 不做成卡片：错误是**瞬时状态**，不是常驻区块。展开的详情在日志面板里
 * （已经带复制按钮），这里只给结论。
 */
@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) message else message.lineSequence().first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

/**
 * native 层开关 + hook 档位。
 *
 * ## 为什么要让用户碰这个
 *
 * 上游 2026-11-27 两次实测，都是「处理后 App 不正常」：
 *
 * ```
 * JMComic（WebView 应用，签名校验只用 PackageManager，零 native 代码）
 *   只做 Java 层      → 无盗版警告 + 网络正常
 *   Java + native     → 网络与图片加载全部失败
 * ```
 *
 * 根因是 libusign 无条件全局 hook 了 18 个 libc 接口，把 WebView 的
 * Chromium IO 栈一起影响了。而这类 App 根本不需要 libc 重定向。
 *
 * 没有这两个开关，用户遇到「装上后 App 打不开 / 加载不出内容」只能认为工具坏了。
 *
 * ## 两个维度是正交的，不能合成一个
 *
 * · **要不要 native 层**：整层关掉，产物少两个 `.so`
 * · **native 挂多少个函数**：只在开着时才有意义
 *
 * 所以先问「开不开」，开着才显示档位。档位藏在关掉的状态里没有意义。
 */
@Composable
private fun NativeLayerRow(
    state: UncheckUiState,
    onToggle: (Boolean) -> Unit,
    onSelectLevel: (Int) -> Unit,
) {
    val levels = listOf(
        UncheckPipeline.HookLevel.CORE to "最小",
        UncheckPipeline.HookLevel.AT to "标准",
        UncheckPipeline.HookLevel.FULL to "完整",
    )
    val idx = levels.indexOfFirst { it.first == state.hookLevel }.coerceAtLeast(0)

    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "拦截 native 层",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (state.nativeLayer) "覆盖 App 自己读 APK 的校验"
                    else "只拦 PackageManager，不碰文件读取",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.nativeLayer, onCheckedChange = onToggle)
        }

        if (state.nativeLayer) {
            Gap(6.dp)
            Text(
                "拦截范围",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(6.dp)
            ToolSegmentedOptions(
                options = levels.map { it.second },
                selectedIndex = idx,
                onSelect = { onSelectLevel(levels[it].first) },
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(4.dp)
            Text(
                when (state.hookLevel) {
                    UncheckPipeline.HookLevel.CORE ->
                        "只拦最关键的 7 个文件操作。装好 App 打不开时用这个。"
                    UncheckPipeline.HookLevel.AT ->
                        "加上路径反查相关的拦截。"
                    else ->
                        "覆盖最全。若处理后 App 网络或图片异常，降到「最小」或直接关掉。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Gap(4.dp)
            Text(
                "关掉后：App 若在 native 里自己读 APK 校验签名，那条路拦不住。" +
                    "但 WebView 类应用的网络与图片不会被打扰。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


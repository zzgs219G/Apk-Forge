package com.xixin.apkforge.ui.screen.run

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.ui.anim.InjectArtAnimation
import com.xixin.apkforge.ui.lifted.components.dialog.AppDialog

/**
 * 注入进行中的模态对话框。
 *
 * ## 内容只有四样
 *
 * ```
 *        ⬡ 六边形 SVG 描边生长（按真实进度推进）
 *                34 %
 *            dex 解析与重写
 *    正在写入 classes2.dex  ·  1.2s
 * ```
 *
 * 进度读数、阶段名、**单行**实时日志。没有环形进度条、没有波浪、
 * 没有可滚动历史——见下面两条「删掉的东西」。
 *
 * ## 为什么删掉了环形波浪进度条
 *
 * 上一版塞了 `M3ExpressiveDownloadProgress`（132dp 圆环 + 沿圆周游动的波浪）。
 * 那是**为了用上那个闲置组件而找的用途**，不是这个场景需要的形态：
 *
 *  · **圆环表达「还差多少圈」**，适合等一个总量未知/匀速推进的下载。
 *    注入是**分阶段的短任务**（解析 → 定位 → 插桩 → 重打包 → 落盘），
 *    每阶段耗时差异极大（6ms 到十几秒）。圆环匀速转，会让用户以为
 *    「还有 40% 就完了」，而实际下一阶段可能占掉全部时间——**进度本身在撒谎**。
 *  · **波浪是下载器的语汇**。注入不是下载。
 *
 * 这个场景唯一诚实的载体是**阶段名 + 六边形描边**：六边形的六条边正好对上
 * 流水线的六个阶段，描到第几条边就是走到第几步。它同时是本工程的品牌图形
 * （`InjectArt.HEX`），不是通用 loading 图形。
 *
 * ## 为什么日志回到单行
 *
 * 上一版在对话框里放了 24 行可滚动日志 + 复制按钮。那些内容的归宿是
 * **运行结束后结果卡的「执行日志」**（那里有展开、有全文复制）。
 * 进行中把历史也铺开，等于让用户一边等一边读——而他此刻只需要知道
 * 「现在在干什么」。所以这里只有一行，且允许换行不截断
 * （日志的信息量全在尾部：方法名、签名、产物路径，截断等于把答案删了）。
 *
 * ## 为什么关不掉
 *
 * 注入正在写 dex，此刻放用户退出只会让他以为失败了。
 * `AppDialog` 的 `dismissOnClickOutside` / `dismissOnBackPress` 都是 false，
 * 从组件层面就拿不到「拖走」这个动作。
 *
 * @param state 注入中的实时状态
 */
@Composable
fun InjectProgressDialog(
    state: ConsoleUiState,
) {
    AppDialog(onDismissRequest = {}) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 唯一的主视觉：六边形 SVG 按真实进度描边 ──
            // phaseProgress 传 state.progress（0..1），不是 -1（无限循环）：
            // 无限循环会让「描边位置」与真实进度脱钩，那就成了装饰品。
            InjectArtAnimation(
                phaseLabel = "",
                size = 96.dp,
                phaseProgress = state.progress,
                showLabel = false,
            )
            Spacer(Modifier.size(14.dp))

            // ── 进度读数：整数百分比 ──
            // 不套环形，纯数字 + 等宽字体：它是读数，不是装饰。
            Text(
                "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(6.dp))

            // ── 阶段名：回答「走到第几步」 ──
            Text(
                state.stage?.label ?: "准备中",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(10.dp))

            // ── 单行实时日志：回答「现在正在做什么」 ──
            // 优先 liveLine（高频、逐字更新），回落到最后一条 step。
            // 不做 maxLines=1 + Ellipsis：尾部是唯一标识，换行也不能截。
            val live = state.liveLine.takeIf { it.isNotBlank() }
                ?: state.steps.lastOrNull()?.let {
                    it.label + if (it.detail.isNotBlank()) "  ${it.detail}" else ""
                }
            Text(
                live ?: "…",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package com.xixin.apkforge.ui.lifted.components.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页面内容的基本单位：**一张分区卡 = 一组同类的行**。
 *
 * 逐条对齐 `克隆仓库x/jian_box` 的 `docs/UI设计规范.md` §3.2「少卡多行」：
 *
 * > **一条铁律**：一个功能区块 = 一张卡；卡内用「行」组织同级项，
 * > **禁止「一个功能一张卡」**。
 *
 * 这正是本工程上一版最大的毛病：四张卡各装一个控件（一张卡里只有一个下拉框），
 * 卡片从「容器」降级成了「给一行字造的房间」。卡的层级只有一个，行永远不套卡。
 *
 * 卡头也按规范 §3.2 实现：**一行「图标芯片 + 标题 + 副标题」，不是一张独立卡片**。
 *  · 图标芯片：`shapes.small` + `primaryContainer` 底 + `primary` 图标
 *  · 密集页（配置/表单）用紧凑档 32dp 芯片 / 18dp 图标
 *  · 标题 `titleSmall` + Bold + 单行省略；副标题 `bodySmall` + `onSurfaceVariant`
 *
 * @param title 卡头标题
 * @param subtitle 卡头副标题（一句话说明这组在决定什么）
 * @param icon 卡头图标芯片
 * @param contentPadding 内容区内边距。默认左右 12、上下 4（卡头已占去上方的空间）
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        // §1.1：卡形状一律 shapes.extraLarge（32dp），禁止 RoundedCornerShape(xx.dp) 字面量
        shape = MaterialTheme.shapes.extraLarge,
        // §1.3：常规卡片容器 = surfaceContainer
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        // elevation 固定 0：非 0 会被 M3 的 surfaceTint 二次染色，
        // 同一张卡在滚动中会变色，卡片层级反而读不出来。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── 卡头：一行，不是一张卡 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(contentPadding)
            ) {
                content()
            }
        }
    }
}

/**
 * 卡内行分隔线。左缩进与 [SectionCard] 的内容内边距一致（12dp）。
 *
 * 此前它是 `SetupScreen` 里的私有函数，日志页要用时只能再抄一份——
 * 一抄就会出现「这条线在配置页缩进 12dp、在日志页缩进 16dp」的漂移。
 */
@Composable
fun CardRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

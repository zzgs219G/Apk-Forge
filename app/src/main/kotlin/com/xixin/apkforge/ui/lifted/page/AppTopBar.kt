package com.xixin.apkforge.ui.lifted.page
import com.xixin.apkforge.ui.lifted.AppConstants

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 顶栏。
 *
 * @param leadingIcon 左侧图标；默认汉堡菜单。子页（如从控制台推入的配置页）
 *   传返回箭头，让「点这里能回去」这件事不需要用户猜返回键在哪。
 * @param leadingLabel 左侧图标的无障碍描述
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit,
    leadingIcon: ImageVector = Icons.Default.Menu,
    leadingLabel: String = "打开侧边栏",
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        // 2026-11-04：去掉顶栏底部分割线 —— 与紧跟其后的 AppTabRow 内置 divider
        // 形成「两条线」，视觉上很脏。分隔只保留 tab 栏那一条。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = AppConstants.SCREEN_EDGE_PADDING, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(imageVector = leadingIcon, contentDescription = leadingLabel)
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                // ⚠️ 不再 `headlineLarge + ExtraBold`。此前 AppTypography 未定义
                // headlineLarge，取到的是 M3 默认 32sp/400，再叠 ExtraBold 就成了
                // 全 App 最重的一处，却用在每页最显眼的位置——与「中文标题不过重、
                // 避免整屏发黑」的设计取向直接矛盾。现在 AppTypography 已定义
                // headlineLarge = 22sp/Medium，这里也不再覆盖字重。
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions()
            }
        }
    }
}

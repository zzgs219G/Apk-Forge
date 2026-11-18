package com.xixin.apkforge.ui.lifted.components.row

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.lifted.m3e.pressBounce
import com.xixin.apkforge.ui.lifted.color.ColorRole
import com.xixin.apkforge.ui.lifted.color.roleColor

/**
 * 操作行（图标 + 文案，整行可点）。
 *
 * T2.1d 由两处实现合并而来，二者**结构级不同**（不是参数差异）：
 * · tools/daily_tools/AppExtractorTool.kt —— material3 ListItem + clip(16dp) + clickable，单行标题
 * · tools/devtools/CrashTestTool.kt       —— Row + pressBounce + 32dp 圆底图标槽，标题/说明/状态三行
 *
 * 这两套结构无法用一组参数调和（容器控件不同、图标有无底色、文本一行还是三行、
 * 圆角由 clip 还是 shape 提供）。因此本组件用 [variant] 显式选择分支，
 * 各分支内部严格照搬原实现，组件不做「猜测式统一」，避免改动任一处的视觉。
 *
 * 两分支的共同点（图标 + 文案 + 整行可点）已用统一参数名表达，使两处调用点写法一致。
 *
 * @param variant 渲染分支；必须显式指定，不设默认值 —— 两个分支的视觉差异过大，
 *                由默认值替调用方决定等于改动 UI
 * @param icon 行首图标
 * @param title 主文案
 * @param onClick 整行点击回调
 * @param description 说明文案；仅 [AppActionRowVariant.PressBounceRow] 使用
 * @param statusText 状态文案（如「需先启用 xCrash」）；仅 [AppActionRowVariant.PressBounceRow] 使用
 * @param enabled 是否可用；[AppActionRowVariant.ListItemRow] 忽略（原实现无禁用态），
 *                [AppActionRowVariant.PressBounceRow] 用配色表达
 * @param destructive 是否为危险操作（标题与图标用 error 色）；仅 [AppActionRowVariant.ListItemRow] 使用
 * @param modifier 修饰
 */
@Composable
fun AppActionRow(
    variant: AppActionRowVariant,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    statusText: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (variant) {
        // M3 新版把 headlineContent 挪成了尾随的 content lambda（旧重载已废弃）。
        // 这里跟随新签名，避免每次编译都刷一条 warning。
        AppActionRowVariant.ListItemRow -> ListItem(
            overlineContent = null,
            supportingContent = null,
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            modifier = modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
        ) {
            Text(
                text = title,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        AppActionRowVariant.PressBounceRow -> Row(
            modifier = modifier
                .fillMaxWidth()
                .pressBounce(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (enabled) MaterialTheme.colorScheme.errorContainer
                else roleColor(ColorRole.SurfaceChip),
                modifier = Modifier.size(32.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (statusText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * [AppActionRow] 的渲染分支。
 *
 * 用带类型的分支而非 enum，是为了让「该分支专属、另一分支不适用」的参数留在各自层：
 * 目前两套形态的专属参数已全部提到函数参数层（destructive / description / statusText），
 * 分支本身不携带额外数据。
 */
enum class AppActionRowVariant {
    /** material3 ListItem 单行形态（原 AppExtractorTool.ActionRow） */
    ListItemRow,

    /** 按压回弹 + 32dp 图标底槽 + 多行文本形态（原 CrashTestTool.ActionRow） */
    PressBounceRow
}

package com.xixin.apkforge.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.page.AppTopBar

/**
 * 尚未实现的入口占位页。
 *
 * 【为什么要有它】侧边栏出现「签名」却点不进去，用户会以为是 bug；
 * 点进去看到「在做什么、卡在哪」，才是诚实的做法。
 */
@Composable
fun ComingSoonScreen(
    title: String,
    body: String,
    onOpenDrawer: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = title, onMenuClick = onOpenDrawer)
        Column(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Icon(
                    Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(20.dp)
                        .size(32.dp),
                )
            }
            Gap(16.dp)
            Text(
                "$title 还没做",
                style = MaterialTheme.typography.titleMedium,
            )
            Gap(8.dp)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

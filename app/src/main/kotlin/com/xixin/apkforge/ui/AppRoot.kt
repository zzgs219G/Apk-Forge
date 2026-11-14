package com.xixin.apkforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

/**
 * 应用主框架：侧边栏 + 内容区。
 *
 * 【为什么用官方 ModalNavigationDrawer】jian_box 自己实现了一套拖拽抽屉，
 * 原因是它的 M3 版本里 `anchoredDraggable` 会抢触摸序列导致蒙层点击失效。
 * 本工程 Compose 1.12 / M3 1.5 已修掉该问题，没必要抄那份 400 行的自定义手势代码——
 * 官方实现自带无障碍语义、返回键处理、边缘手势，维护成本低得多。
 *
 * 借鉴 jian_box 的是**信息结构**：抽屉顶部放应用标识，中间是功能分组，
 * 底部放版本/说明——不是抄它的实现代码。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val icon: String,
    val desc: String,
) {
    INJECT("inject", "注入", "⚡", "选 APK 注入调用"),
    CONFIG("config", "配置", "❐", "管理调用 + dex"),
    ABOUT("about", "关于", "◈", "说明与限制"),
}

@Composable
fun AppRoot() {
    val nav: NavHostController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStack by nav.currentBackStackEntryAsState()
    val current = AppDest.entries.firstOrNull {
        it.route == backStack?.destination?.route
    } ?: AppDest.INJECT

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerSheet(
                current = current,
                onSelect = { dest ->
                    scope.launch { drawerState.close() }
                    if (dest != current) {
                        nav.navigate(dest.route) {
                            popUpTo(AppDest.INJECT.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) {
        NavHost(navController = nav, startDestination = AppDest.INJECT.route) {
            composable(AppDest.INJECT.route) {
                InjectScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
            }
            composable(AppDest.CONFIG.route) {
                ConfigScreen(onBack = { nav.popBackStack() })
            }
            composable(AppDest.ABOUT.route) {
                AboutScreen(
                    onBack = { nav.popBackStack() },
                    onOpenConfig = { nav.navigate(AppDest.CONFIG.route) },
                )
            }
        }
    }
}

@Composable
private fun AppDrawerSheet(current: AppDest, onSelect: (AppDest) -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(268.dp),
    ) {
        // 顶部品牌区
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text(
                "ApkForge",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "本地 dex 注入工具",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(10.dp))

        AppDest.entries.forEach { dest ->
            DrawerItem(
                dest = dest,
                active = dest == current,
                onClick = { onSelect(dest) },
            )
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        Column(Modifier.padding(20.dp)) {
            Text(
                "不重签名 · 仅本地处理",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "dex 注入基于 smali-dexlib2",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DrawerItem(dest: AppDest, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dest.icon,
                fontSize = 15.sp,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dest.label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    dest.desc,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

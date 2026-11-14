package com.xixin.apkforge.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xixin.apkforge.ui.lifted.page.AppModalNavigationDrawer
import com.xixin.apkforge.ui.lifted.page.DrawerFooter
import com.xixin.apkforge.ui.lifted.page.DrawerHeader
import com.xixin.apkforge.ui.lifted.page.DrawerMenuItem
import com.xixin.apkforge.ui.screen.about.AboutScreen
import com.xixin.apkforge.ui.screen.config.ConfigScreen
import com.xixin.apkforge.ui.screen.inject.InjectScreen
import kotlinx.coroutines.launch

/** 侧边栏入口。route 直接用作抽屉的 onMenuClick 参数。 */
enum class AppDest(val route: String, val label: String, val icon: ImageVector) {
    INJECT("inject", "注入", Icons.Filled.Build),
    CONFIG("config", "配置", Icons.Filled.Settings),
    ABOUT("about", "关于", Icons.Filled.Info),
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

    AppModalNavigationDrawer(
        state = drawerState,
        drawerContent = { AppDrawerContent(current) { route ->
            scope.launch { drawerState.close() }
            val dest = AppDest.entries.firstOrNull { it.route == route } ?: return@AppDrawerContent
            if (dest != current) {
                nav.navigate(dest.route) {
                    popUpTo(AppDest.INJECT.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        } },
    ) {
        NavHost(
            navController = nav,
            startDestination = AppDest.INJECT.route,
            enterTransition = {
                slideInHorizontally(tween(300)) { it / 8 } + fadeIn(tween(300))
            },
            exitTransition = {
                slideOutHorizontally(tween(250)) { -it / 16 } + fadeOut(tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(tween(250)) { it / 16 } + fadeOut(tween(250))
            },
        ) {
            composable(AppDest.INJECT.route) {
                InjectScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenConfig = { nav.navigate(AppDest.CONFIG.route) },
                )
            }
            composable(AppDest.CONFIG.route) {
                ConfigScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
            }
            composable(AppDest.ABOUT.route) {
                AboutScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenConfig = { nav.navigate(AppDest.CONFIG.route) },
                )
            }
        }
    }
}

/**
 * 抽屉内容。
 *
 * 标题与菜单项用搬运来的 [DrawerHeader] / [DrawerMenuItem]，
 * 底部信息区是本工程自己的内容（jian_box 那里是「任务中心/下载管理/设置」）。
 */
@Composable
fun AppDrawerContent(current: AppDest, onMenuClick: (String) -> Unit) {
    Box(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            DrawerHeader("ApkForge", "本地 dex 注入")

            AppDest.entries.forEach { dest ->
                DrawerMenuItem(
                    icon = dest.icon,
                    label = dest.label,
                    onClick = { onMenuClick(dest.route) },
                    selected = dest == current,
                )
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                DrawerFooter(
                    listOf(
                        "dex 引擎 smali-dexlib2",
                        "不重签名 · 本机处理",
                    )
                )
            }
        }
    }
}

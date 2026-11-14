package com.xixin.apkforge.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xixin.apkforge.ui.design.Forge
import com.xixin.apkforge.ui.design.HairLine
import com.xixin.apkforge.ui.design.KVRow
import kotlinx.coroutines.launch
import com.xixin.apkforge.ui.screen.inject.InjectScreen
import com.xixin.apkforge.ui.screen.config.ConfigScreen
import com.xixin.apkforge.ui.screen.about.AboutScreen

/**
 * 应用主框架。
 *
 * 【转场】不用 NavHost 默认的 fade——「淡入淡出」在深色工具类 App 里观感很廉价，
 * 因为它没有方向感。改成**共享轴滑动**（左出右进），符合「钻进下一层」的空间隐喻。
 * 300ms，与 M3e 的 defaultSpatialSpec 同量级。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val tag: String,
    val desc: String,
) {
    INJECT("inject", "注入", "INJECT", "选 APK，插入调用"),
    CONFIG("config", "配置", "CONFIG", "管理调用与 dex"),
    ABOUT("about", "关于", "ABOUT", "原理与限制"),
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
        scrimColor = Forge.Colors.bg.copy(alpha = 0.82f),
        drawerContent = {
            ForgeDrawerSheet(
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
        NavHost(
            navController = nav,
            startDestination = AppDest.INJECT.route,
            enterTransition = {
                slideInHorizontally(tween(300)) { it / 6 } + fadeIn(tween(300))
            },
            exitTransition = {
                slideOutHorizontally(tween(300)) { -it / 12 } + fadeOut(tween(220))
            },
            popEnterTransition = {
                slideInHorizontally(tween(300)) { -it / 6 } + fadeIn(tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(tween(300)) { it / 12 } + fadeOut(tween(220))
            },
        ) {
            composable(AppDest.INJECT.route) {
                InjectScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenConfig = { nav.navigate(AppDest.CONFIG.route) },
                )
            }
            composable(AppDest.CONFIG.route) {
                ConfigScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
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

@Composable
private fun ForgeDrawerSheet(current: AppDest, onSelect: (AppDest) -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = Forge.Colors.surface,
        modifier = Modifier.width(292.dp),
    ) {
        // 品牌区：大字号 + 一行等宽副标题
        Column(Modifier.padding(horizontal = 24.dp, vertical = 34.dp)) {
            Text(
                "APKFORGE",
                style = Forge.Type.display,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Forge.Colors.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "local dex injector",
                style = Forge.Type.microMono,
                color = Forge.Colors.accent.copy(alpha = 0.8f),
            )
        }

        HairLine()

        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            AppDest.entries.forEach { dest ->
                DrawerItem(
                    dest = dest,
                    active = dest == current,
                    onClick = { onSelect(dest) },
                )
            }
        }

        Spacer(Modifier.weight(1f))
        HairLine()

        Column(Modifier.padding(24.dp)) {
            KVRow("Dex 引擎", "dexlib2 3.0.10")
            KVRow("签名", "不处理")
            Spacer(Modifier.height(10.dp))
            Text(
                "所有处理在本机完成",
                style = Forge.Type.caption,
                color = Forge.Colors.textFaint,
            )
        }
    }
}

@Composable
private fun DrawerItem(dest: AppDest, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(Forge.Shape.card)
            .background(if (active) Forge.Colors.surfaceHigh else Forge.Colors.surface)
            .border(
                1.dp,
                if (active) Forge.Colors.accent.copy(alpha = 0.3f) else Forge.Colors.bg,
                Forge.Shape.card,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标签用全大写大字号，不用图标 —— 图标在这个密度下只是噪音
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dest.label,
                    style = Forge.Type.title,
                    color = if (active) Forge.Colors.accent else Forge.Colors.text,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    dest.tag,
                    style = Forge.Type.label,
                    color = Forge.Colors.textFaint,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                dest.desc,
                style = Forge.Type.caption,
                color = Forge.Colors.textFaint,
            )
        }
        if (active) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(Forge.Colors.accent, Forge.Shape.pill)
            )
        }
    }
}

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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.xixin.apkforge.ui.lifted.page.AppModalNavigationDrawer
import com.xixin.apkforge.ui.lifted.page.DrawerFooter
import com.xixin.apkforge.ui.lifted.page.DrawerHeader
import com.xixin.apkforge.ui.lifted.page.DrawerMenuItem
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.ui.screen.ComingSoonScreen
import com.xixin.apkforge.ui.screen.about.AboutScreen
import com.xixin.apkforge.ui.screen.config.ConfigScreen
import com.xixin.apkforge.feature.batch.BatchViewModel
import com.xixin.apkforge.feature.plugin.PluginViewModel
import com.xixin.apkforge.ui.screen.batch.BatchScreen
import com.xixin.apkforge.ui.screen.plugin.PluginScreen
import com.xixin.apkforge.ui.screen.console.ConsoleScreen
import com.xixin.apkforge.ui.screen.run.RunScreen
import com.xixin.apkforge.ui.screen.setup.SetupScreen
import kotlinx.coroutines.launch

/**
 * 侧边栏入口。
 *
 * 「控制台」是本 App 的主场——所有注入动作都在它的两条子路由里发生
 * （`setup` 配置确认、`run` 执行）。侧边栏只放**平级的四类事**：
 * 干活（控制台）、攒素材（配置）、产物签名（待做）、看说明（关于）。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 侧边栏是否展示。setup / run 是从控制台进去的子页，不在菜单里重复出现。 */
    val inMenu: Boolean = true,
) {
    CONSOLE("console", "控制台", Icons.Rounded.Terminal),
    CONFIG("config", "配置", Icons.Rounded.Tune),
    BATCH("batch", "批量", Icons.Rounded.Layers),
    PLUGIN("plugin", "插件包", Icons.Rounded.Extension),
    SIGN("sign", "签名", Icons.Rounded.VerifiedUser),
    ABOUT("about", "关于", Icons.Rounded.Info),

    SETUP("setup", "注入配置", Icons.Rounded.Tune, inMenu = false),
    RUN("run", "运行", Icons.Rounded.Terminal, inMenu = false),
    ;

    companion object {
        val menu: List<AppDest> get() = entries.filter { it.inMenu }
        fun of(route: String?): AppDest = entries.firstOrNull { it.route == route } ?: CONSOLE
    }
}

/**
 * 根导航。
 *
 * 【旧版侧边栏点的 bug 根因】`popUpTo(INJECT.route) { saveState = true }` 把
 * 「注入页」当成回退栈底，但从配置页点「去注入」又是直接 `navigate`，
 * 两条路径互相插队，回退栈里会出现 `config → inject → config` 这样的环。
 * 于是从配置页返回时退到了注入页，再点侧边栏「配置」又叠一层，
 * 用户观感就是「建完配置回不到注入页」。
 *
 * 现在的规则简单得多：
 *  · 侧边栏的四个入口是**平级**的，切换时用 `popUpTo(start) { inclusive = false; saveState }`，
 *    栈底永远是控制台，不会堆叠；
 *  · `setup` / `run` 是控制台的子页，只用 `navigate` 进、`popBackStack` 出，
 *    天然带正确的返回语义。
 */
@Composable
fun AppRoot(
    /**
     * 控制台的 ViewModel。
     *
     * ⚠️ 必须在这一层取、往下传，**不能**在每个页面里各自 `viewModel()`：
     * `viewModel()` 默认以当前的 `NavBackStackEntry` 作为 ViewModelStoreOwner，
     * 每个 destination 一份独立实例。控制台、注入配置、运行三个页面是
     * 「一个任务的三个阶段」，各拿一份 VM 就变成三个互不相通的世界——
     * 选好的包、选好的配置、跑出来的日志都传不过去。
     * 在 NavHost 之上取，owner 就是 Activity，天然共享且能扛住旋转。
     */
    console: ConsoleViewModel = viewModel(),
    /** 批量注入的 VM。与控制台分开：两者是不同生命周期的事，状态不该互相污染。 */
    batch: BatchViewModel = viewModel(),
    /** 插件包 VM：造载荷，与注入流程互不依赖。 */
    plugin: PluginViewModel = viewModel(),
) {
    val nav: NavHostController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStack by nav.currentBackStackEntryAsState()
    val current = AppDest.of(backStack?.destination?.route)

    fun go(dest: AppDest) {
        if (dest.route == backStack?.destination?.route) return
        nav.navigate(dest.route) {
            popUpTo(AppDest.CONSOLE.route) { inclusive = false; saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openSetup() = nav.navigate(AppDest.SETUP.route) { launchSingleTop = true }
    fun openRun() = nav.navigate(AppDest.RUN.route) { launchSingleTop = true }
    fun openBatch() = nav.navigate(AppDest.BATCH.route) { launchSingleTop = true }

    /**
     * 推入配置页（不入栈 top 替换）。
     *
     * 从控制台进 → 返回回控制台；从「注入配置」进 → 返回回注入配置页。
     * 侧边栏切换仍走 [go]（回退到控制台再换），两条路径互不打扰。
     */
    fun pushConfig() = nav.navigate(AppDest.CONFIG.route) { launchSingleTop = true }
    /** 回控制台首页（显式目标，不看回退栈里剩下什么）。 */
    fun back() {
        nav.popBackStack(AppDest.CONSOLE.route, inclusive = false)
    }

    /** 回上一页（运行页 → 注入配置页）。 */
    fun up() {
        if (!nav.popBackStack()) back()
    }

    AppModalNavigationDrawer(
        state = drawerState,
        drawerContent = {
            AppDrawerContent(current) { route ->
                scope.launch { drawerState.close() }
                val dest = AppDest.of(route)
                backStack?.destination?.route?.let { from ->
                    if (from == AppDest.SETUP.route || from == AppDest.RUN.route ||
                        from == AppDest.BATCH.route || from == AppDest.PLUGIN.route
                    ) {
                        // 从子页去别的平级页：先回控制台，避免栈底被顶掉
                        nav.popBackStack(AppDest.CONSOLE.route, inclusive = false)
                    }
                }
                go(dest)
            }
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = AppDest.CONSOLE.route,
            enterTransition = {
                slideInHorizontally(tween(280)) { it / 10 } + fadeIn(tween(280))
            },
            exitTransition = {
                slideOutHorizontally(tween(220)) { -it / 20 } + fadeOut(tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(tween(280)) { -it / 10 } + fadeIn(tween(280))
            },
            popExitTransition = {
                slideOutHorizontally(tween(220)) { it / 20 } + fadeOut(tween(200))
            },
        ) {
            composable(AppDest.CONSOLE.route) {
                ConsoleScreen(
                    vm = console,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSetup = ::openSetup,
                    onOpenRun = ::openRun,
                    onOpenConfig = ::pushConfig,
                    onOpenBatch = ::openBatch,
                )
            }
            composable(AppDest.PLUGIN.route) {
                PluginScreen(vm = plugin, onBack = ::back)
            }
            composable(AppDest.BATCH.route) {
                BatchScreen(
                    vm = batch,
                    // 批量是从控制台推入的子页：返回回控制台，而不是回退栈里剩下的东西
                    onBack = ::back,
                    onOpenConfig = ::pushConfig,
                )
            }
            composable(AppDest.SETUP.route) {
                SetupScreen(
                    vm = console,
                    onBack = ::back,
                    onRun = ::openRun,
                    onOpenConfig = ::pushConfig,
                )
            }
            composable(AppDest.RUN.route) {
                // 返回走「上一页」：跑完想改配置再跑一次，是最常见的下一步
                RunScreen(vm = console, onBack = ::up, onExit = ::back)
            }
            composable(AppDest.CONFIG.route) {
                ConfigScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onBack = {
                        // 从子页进来就直接退回去；侧边栏直接切到配置页时没有可退的，回控制台
                        if (nav.previousBackStackEntry?.destination?.route != AppDest.CONSOLE.route) {
                            nav.popBackStack()
                        } else {
                            go(AppDest.CONSOLE)
                        }
                    },
                    onBackToConsole = { go(AppDest.CONSOLE) },
                    onSaved = console::refreshConfigs,
                )
            }
            composable(AppDest.SIGN.route) {
                ComingSoonScreen(
                    title = "签名",
                    body = "产物目前保持原签名，只能覆盖安装到已装同签名包的设备。\n" +
                        "下一步会接 apksig 做重签——难点是它的 java.util.stream 依赖在 Android 上不存在，\n" +
                        "要换实现或调系统 apksigner。",
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
            composable(AppDest.ABOUT.route) {
                AboutScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenConfig = ::pushConfig,
                )
            }
        }
    }
}

/**
 * 抽屉内容。
 *
 * 选中态跟随当前路由：`setup` / `run` 都算「控制台」在用，
 * 否则进子页时菜单会整排变灰，像迷路。
 */
@Composable
fun AppDrawerContent(current: AppDest, onMenuClick: (String) -> Unit) {
    // 子页归到父入口，菜单高亮才不会掉
    val highlighted = when (current) {
        AppDest.SETUP, AppDest.RUN, AppDest.BATCH -> AppDest.CONSOLE
        else -> current
    }
    Box(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            DrawerHeader("ApkForge", "本地 dex 注入")

            AppDest.menu.forEach { dest ->
                DrawerMenuItem(
                    icon = dest.icon,
                    label = dest.label,
                    onClick = { onMenuClick(dest.route) },
                    selected = dest == highlighted,
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

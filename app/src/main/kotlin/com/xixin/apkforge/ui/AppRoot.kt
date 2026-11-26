package com.xixin.apkforge.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
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
import com.xixin.apkforge.feature.uncheck.UncheckViewModel
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
import com.xixin.apkforge.ui.screen.uncheck.UncheckScreen
import kotlinx.coroutines.launch

/**
 * 侧边栏入口。
 *
 * 「控制台」是本 App 的主场——**选包只在这里发生**。选完包之后由用户挑做哪件事：
 *
 * ```
 * 选包 → 弹模块选择 ┬─ dex 注入   → 注入配置 → 运行
 *                  └─ 去签名校验 → 直接处理
 * ```
 *
 * 「去签名校验」曾经是侧边栏里的一个独立上传页，那是错的：它和控制台做的是
 * 同一件事（选 APK），却各自维护一套选择逻辑与空态。两个页面上传同一份文件，
 * 用户还得记住"我上次是在哪选的"。现在它降级成控制台里的一个模块，
 * 不再有独立的上传入口。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 侧边栏是否展示。setup / run / uncheck 都是控制台的子页，不在菜单里重复出现。 */
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
    /** 去签名校验：控制台的子页，继承控制台选好的包。 */
    UNCHECK("uncheck", "去签名校验", Icons.Rounded.Security, inMenu = false),
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
    /**
     * 去签名校验的 VM。
     *
     * 与控制台同在 NavHost 之上取：它的输入来自控制台的「模块选择」，
     * 若在子页里各自 `viewModel()`，owner 是 NavBackStackEntry，
     * 控制台 setInput 的那份和页面读到的那份会是两个实例（坑 19）。
     */
    uncheck: UncheckViewModel = viewModel(),
) {
    val nav: NavHostController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStack by nav.currentBackStackEntryAsState()
    val current = AppDest.of(backStack?.destination?.route)

    /**
     * 打开抽屉。
     *
     * ⚠️ **必须防重入**。原先每个页面都是 `onOpenDrawer = { scope.launch { drawerState.open() } }`，
     * 连点三下就并发起三个协程抢同一个动画。`animate()` 各自以当时的 `offsetX`
     * 为初值，多个动画交替写同一个状态，轻则抖动，重则把中间值写坏。
     * 这里用 `isOpen` / `isAnimationRunning` 先判一次：已经在开/已开就直接返回，
     * 不新建协程。所有页面共用这一个函数（原先 6 处各写一份）。
     */
    fun openDrawer() {
        if (drawerState.isOpen || drawerState.isAnimationRunning) return
        scope.launch { drawerState.open() }
    }

    // NavHost 的四个 transition lambda 是非 @Composable 的，读不了 MaterialTheme，
    // 必须先在这里把 motionScheme 的规格取好（见 NavHost 处注释）。
    // ⚠️ 必须写明泛型：motionScheme 的 spec 函数是 <T> 泛型，直接 val 赋值没有
    //    期望类型可推，会报「Cannot infer type for type parameter T」。
    // ⚠️ 位移的泛型是 IntOffset（slideInHorizontally 的规格类型），不是 Float——
    //    写成 FiniteAnimationSpec<Float> 会报 "actual type is FiniteAnimationSpec<Float>,
    //    but FiniteAnimationSpec<IntOffset> was expected"。
    val motionScheme = MaterialTheme.motionScheme
    val spatialEnter: FiniteAnimationSpec<IntOffset> = motionScheme.defaultSpatialSpec()
    val spatialExit: FiniteAnimationSpec<IntOffset> = motionScheme.fastSpatialSpec()
    val effectsEnter: FiniteAnimationSpec<Float> = motionScheme.defaultEffectsSpec()
    val effectsExit: FiniteAnimationSpec<Float> = motionScheme.fastEffectsSpec()

    /**
     * 切到**平级**入口。
     *
     * 只给侧边栏用：`popUpTo(CONSOLE)` 的前提是目标与栈底同级。
     * 子页（setup / run / uncheck）一律走各自的 `openXxx()`，
     * 否则会把它当成平级页压到栈底之上，返回语义就乱了。
     */
    fun go(dest: AppDest) {
        require(dest.inMenu) { "go() 只处理平级入口，${dest.route} 是子页，请用 openXxx()" }
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
    fun openUncheck() = nav.navigate(AppDest.UNCHECK.route) { launchSingleTop = true }

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
                    // 从任一子页切到平级入口时，先回控制台再走 go()，否则
                    // `popUpTo(CONSOLE)` 找不到栈底，回退栈会被顶掉（坑 20）。
                    //
                    // ⚠️ 这里必须列**全部**子页。原先只列了 SETUP/RUN/BATCH/PLUGIN，
                    // 漏了 CONFIG 与 UNCHECK —— 从这两页点侧边栏，栈底就没了，
                    // 表现为「点了菜单之后再返回，退不回控制台」。
                    // 用 inMenu 反推而不是手写清单：新增子页时忘加就又会漏。
                    if (!AppDest.of(from).inMenu) {
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
            // ⚠️ 转场必须走 motionScheme 语义槽，不能手填 tween(280)：
            // 这四个 lambda 是**非 @Composable** 的，读不了 MaterialTheme，
            // 所以 spec 先在下面 hoist 出来。换 MotionScheme 时这里会自动跟着变，
            // 手填数字则永远不会——那正是「页面切换像 2016」的来源。
            //
            // 位移幅度取 1/10 与 1/20：整屏滑动的距离，1/10 是「下钻」（新页面从右侧
            // 推入一小段，暗示层级更深），1/20 是「退出」（旧页面向左让开一点点，
            // 不抢新页面的注意力）。这是 M3 官方 container transform 的比例。
            enterTransition = {
                slideInHorizontally(spatialEnter) { it / 10 } + fadeIn(effectsEnter)
            },
            exitTransition = {
                slideOutHorizontally(spatialExit) { -it / 20 } + fadeOut(effectsExit)
            },
            popEnterTransition = {
                slideInHorizontally(spatialEnter) { -it / 10 } + fadeIn(effectsEnter)
            },
            popExitTransition = {
                slideOutHorizontally(spatialExit) { it / 20 } + fadeOut(effectsExit)
            },
        ) {
            composable(AppDest.CONSOLE.route) {
                ConsoleScreen(
                    vm = console,
                    onOpenDrawer = ::openDrawer,
                    onOpenSetup = ::openSetup,
                    onOpenRun = ::openRun,
                    onOpenConfig = ::pushConfig,
                    onOpenBatch = ::openBatch,
                    // 去校验继承控制台刚选的包：先落进它的 VM，再跳过去。
                    // 这样它不再需要自己的选择器（那会是第二个上传入口）。
                    onOpenUncheck = { picked ->
                        uncheck.setInput(picked)
                        openUncheck()
                    },
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
                RunScreen(
                    vm = console,
                    // 离开运行页必须清掉「回看」状态：否则下次从控制台正常进来，
                    // 顶部还挂着上一条历史的包名与时间。
                    onBack = { console.clearViewing(); up() },
                    onExit = { console.clearViewing(); back() },
                )
            }
            composable(AppDest.CONFIG.route) {
                ConfigScreen(
                    onOpenDrawer = ::openDrawer,
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
            composable(AppDest.UNCHECK.route) {
                UncheckScreen(
                    vm = uncheck,
                    onOpenDrawer = ::openDrawer,
                    // 返回控制台：去校验是控制台的模块，不是独立的一层
                    onBack = { uncheck.clearInput(); back() },
                )
            }
            composable(AppDest.SIGN.route) {
                ComingSoonScreen(
                    title = "签名",
                    body = "产物目前保持原签名，只能覆盖安装到已装同签名包的设备。\n" +
                        "下一步会接 apksig 做重签——难点是它的 java.util.stream 依赖在 Android 上不存在，\n" +
                        "要换实现或调系统 apksigner。",
                    onOpenDrawer = ::openDrawer,
                )
            }
            composable(AppDest.ABOUT.route) {
                AboutScreen(
                    onOpenDrawer = ::openDrawer,
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
        AppDest.SETUP, AppDest.RUN, AppDest.BATCH, AppDest.UNCHECK -> AppDest.CONSOLE
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

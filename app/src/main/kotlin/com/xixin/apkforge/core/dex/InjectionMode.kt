package com.xixin.apkforge.core.dex

/**
 * 注入模式：**调用在宿主里被执行几次、何时执行**。
 *
 * 【为什么需要这个】上一版只有一种行为：在自动解析出的唯一生命周期点上插一次。
 * 但真实需求至少有四类，靠一份代码硬编码「单次」会把另外三类需求逼到
 * 「手改注入点 / 手改调用行」这种不可靠路径上：
 *
 * | 模式 | 载体 | 典型用途 |
 * |---|---|---|
 * | [ONCE] | 启动切入点（通常是 onCreate） | 弹一次公告、初始化钩子 |
 * | [EVERY_ENTER] | onStart + onResume | 每次回到页面都要检查（更新提示、校验） |
 * | [MULTI_POINT] | 所有可插的生命周期点 | 调试打点，要看完整时序 |
 * | [REPLACE_BODY] | 单个点，丢弃原方法体 | 打桩/劫持，风险最高 |
 *
 * 判定全部落在 [InjectionPlanner] 这个纯 JVM 对象里，可以脱离真机被单测钉住：
 * 模式一旦写错，表现是「注入成功但行为不对」——最难查的一类失败。
 */
enum class InjectionMode(
    val label: String,
    /** 一句话讲清楚「这个模式在宿主里的表现」，直接显示在选项下方 */
    val hint: String,
) {
    ONCE(
        "单次注入",
        "只在启动切入点插一次，宿主每次冷启动执行一次",
    ),
    EVERY_ENTER(
        "每次进入",
        "onStart 与 onResume 各插一份，每次回到页面都会执行",
    ),
    MULTI_POINT(
        "多点全覆盖",
        "所有可插的生命周期点都插一份，用于观察完整时序",
    ),
    REPLACE_BODY(
        "替换方法体",
        "清空目标方法原有指令只留注入调用，原逻辑不再执行",
    ),
}

/**
 * 把「用户选的模式」翻译成「具体要改哪几个方法」。
 *
 * 三条硬约束（`InjectionModeTest` 钉住）：
 * 1. 候选点只能来自**同一个类**的清单（`points` 由父类决定），
 *    绝不能出现「Application 被推荐 onPostCreate」这种错配；
 * 2. native 方法一律排除——它没有字节码，插进去只会产出非法 dex；
 * 3. [InjectionMode.ONCE] 必须尊重 [LifecyclePoints.resolve] 的降级结论，
 *    不能因为它换了点就退回模式里再算一遍，否则 native 降级会失效。
 */
object InjectionPlanner {

    /** 一个待注入的落点。 */
    data class Target(
        val point: LifecyclePoints.Point,
        val status: LifecyclePoints.Status,
        /** 是否清空该方法的原有指令（仅 [InjectionMode.REPLACE_BODY] 为 true） */
        val replaceBody: Boolean = false,
    )

    data class Plan(
        val targets: List<Target>,
        /** 模式本身是否需要自动补点（用来在 UI 上解释「为什么多了两行」） */
        val autoExpanded: Boolean,
        /** 面向用户的一句话说明，null 表示无需解释 */
        val note: String? = null,
    )

    /** [InjectionMode.MULTI_POINT] 最多铺几个点：再多就变成「到处打点」，日志也读不动。 */
    private const val MAX_MULTI = 6

    /**
     * @param points   当前类可用的生命周期点清单
     * @param methods  类里真实存在的方法（决定「已有 / 将创建 / native」）
     * @param wanted   用户在配置里指定的点；[AUTO] 表示交给系统挑
     * @param auto     传 true 时忽略 [wanted] 的显式选择，只按模式挑点
     */
    fun plan(
        mode: InjectionMode,
        points: List<LifecyclePoints.Point>,
        methods: List<ExistingMethod>,
        wanted: LifecyclePoints.Point,
        auto: Boolean = false,
    ): Plan {
        val base = LifecyclePoints.resolve(points, methods, wanted).target
        val usable = points.filter { LifecyclePoints.statusOf(it, methods) != LifecyclePoints.Status.NATIVE }

        fun targetOf(p: LifecyclePoints.Point, replace: Boolean = false) = Target(
            point = p,
            status = LifecyclePoints.statusOf(p, methods),
            replaceBody = replace,
        )

        return when (mode) {
            InjectionMode.ONCE -> Plan(
                targets = listOf(targetOf(base)),
                autoExpanded = false,
            )

            InjectionMode.EVERY_ENTER -> {
                // 「每次进入」的语义在 Android 上正好对应 onStart + onResume：
                // onStart 是「即将可见」，onResume 是「可交互」，两者都在每次重新进入时触发。
                // 若用户/自动选中的点本身已在这两个里，就把另一个补上，而不是重复插同一个方法。
                val wantedNames = listOf("onStart", "onResume")
                val picked = buildList {
                    add(base)
                    wantedNames.forEach { n ->
                        if (n != base.name) usable.firstOrNull { it.name == n }?.let { add(it) }
                    }
                }.distinctBy { it.name }
                Plan(
                    targets = picked.map { targetOf(it) },
                    autoExpanded = true,
                    note = if (picked.size > 1) {
                        "每次进入模式：已铺到 ${picked.joinToString(" + ") { it.name }}"
                    } else {
                        "这个类只有 ${base.name} 一个可插的点，无法拆分「进入」语义"
                    },
                )
            }

            InjectionMode.MULTI_POINT -> {
                val picked = usable.take(MAX_MULTI)
                Plan(
                    targets = picked.map { targetOf(it) },
                    autoExpanded = true,
                    note = "多点覆盖：将写入 ${picked.size} 个方法（${picked.joinToString { it.name }}）",
                )
            }

            InjectionMode.REPLACE_BODY -> Plan(
                targets = listOf(targetOf(base, replace = true)),
                autoExpanded = false,
                note = "替换方法体：${base.name} 原有指令会被丢弃，" +
                    "只保留注入调用与父类调用，原逻辑不再执行",
            )
        }
    }
}

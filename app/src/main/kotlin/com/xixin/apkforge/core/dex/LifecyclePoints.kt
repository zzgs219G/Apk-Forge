package com.xixin.apkforge.core.dex

/**
 * 生命周期注入点清单 + 「该注哪个方法」的判定。
 *
 * 【为什么要有这个】让用户手输 `onCreate` / `(Landroid/os/Bundle;)V` 是不合理的：
 * 生命周期方法名和签名是固定枚举，用户记不住也没必要记。下拉框列出来，
 * 并且标出「已有 / 将创建 / native 不可插」，用户一眼就知道后果。
 *
 * 【为什么要有 fallback】加固壳与 `NativeActivity` 的 `onCreate` 常常是 native 方法：
 * 它**没有字节码**，往里插指令是不可能的（钉在 `DexCallInjector` 里）。
 * 这时不该报错了事——按「与 onCreate 语义最接近」的顺序退到下一个可插的点，
 * 并把原因说清楚。选择顺序的依据见 [CREATE_FALLBACKS]。
 */
object LifecyclePoints {

    data class Point(
        val name: String,
        val signature: String,
        /** 面向用户的说明：这个点什么时候跑 */
        val hint: String,
    ) {
        val label: String get() = name
        val detail: String get() = "$name$signature"
    }

    /**
     * Activity 常见注入点，按「跑得早」排序——注入越早，抢在业务逻辑前生效的机会越大。
     *
     * `onPostCreate` 排在 `onCreate` 之后不是因为更晚生效有价值，而是因为它是
     * **onCreate 返回后的第一站**（`ActivityThread.callActivityOnPostCreate`），
     * 也就是 native onCreate 不可插时的最佳替代。
     */
    val ACTIVITY: List<Point> = listOf(
        Point("attachBaseContext", "(Landroid/content/Context;)V", "最早，Context 刚挂上"),
        Point("onCreate", "(Landroid/os/Bundle;)V", "最常用，界面创建"),
        Point("onPostCreate", "(Landroid/os/Bundle;)V", "onCreate 返回后立即执行，几乎同一时刻"),
        Point("onStart", "()V", "界面即将可见"),
        Point("onResume", "()V", "可交互"),
        Point("onNewIntent", "(Landroid/content/Intent;)V", "复用实例"),
        Point("onPause", "()V", "失焦"),
        Point("onStop", "()V", "不可见"),
        Point("onDestroy", "()V", "销毁"),
    )

    /** Application 注入点。 */
    val APPLICATION: List<Point> = listOf(
        Point("attachBaseContext", "(Landroid/content/Context;)V", "最早，Application 刚挂上"),
        Point("onCreate", "()V", "App 进程启动"),
    )

    /**
     * `onCreate` 不可插时的替代顺序，按「时机接近度」排：
     *
     * 1. `onPostCreate(Bundle)`：`ActivityThread` 在 `onCreate` 返回后**立刻**调用，
     *    拿到的是同一份 `savedInstanceState`。业务代码此时已完成初始化，
     *    但界面尚未绘制——想「跟着 Activity 启动就跑」的载荷，这里是等价位置。
     * 2. `onStart()`：首帧可见前调用，与 `onCreate` 同一帧内连续发生。
     * 3. `onResume()`：可交互时机，通常紧随 `onStart`。
     * 4. `attachBaseContext(Context)`：比 `onCreate` 更早，只有 Context、
     *    还没有 Activity 窗口；载荷若需要 `Activity` 参数放最后。
     */
    val CREATE_FALLBACKS: List<String> = listOf(
        "onPostCreate", "onStart", "onResume", "attachBaseContext",
    )

    /** 按父类判断是 Activity 还是 Application 系列，给出合适的清单。 */
    fun forSuperClass(superType: String?): List<Point> = when {
        superType == null -> ACTIVITY
        superType == "Landroid/app/Application;" -> APPLICATION
        superType.contains("Application") -> APPLICATION
        else -> ACTIVITY
    }

    /**
     * 两套清单合并（去重）。用于判断「某个名字是不是标准生命周期方法」——
     * 用户自定义的方法名不在里面，不该被系统擅自改掉。
     */
    val ALL_STANDARD: List<Point> =
        (ACTIVITY + APPLICATION).distinctBy { it.name + it.signature }

    /** 某个注入点在目标类里的实际状况。 */
    enum class Status(val label: String) {
        /** 类里已有这个方法，注入就是「覆写加固」 */
        EXISTS("已有"),

        /** 类里没有，注入时会新建（并补 super 调用） */
        MISSING("将创建"),

        /** 方法存在但是 native，没有字节码，不能插 */
        NATIVE("native 不可插"),
    }

    /** 下拉框里的一项。 */
    data class Candidate(
        val point: Point,
        val status: Status,
        val recommended: Boolean,
        /** 一句话后果说明，直接显示在选项第二行 */
        val note: String,
    )

    /**
     * 候选清单 + 最终选中的注入点。
     *
     * [target] 通常等于入参的 [Point]，只有「入参点不可插且存在替代」时才会被换掉，
     * 此时 [fallback] 为 true、[reason] 说明原因。
     */
    data class Resolution(
        val target: Point,
        val fallback: Boolean,
        val reason: String?,
        val candidates: List<Candidate>,
    )

    fun statusOf(point: Point, methods: List<ExistingMethod>): Status {
        val found = methods.firstOrNull {
            it.name == point.name && it.signature == point.signature
        } ?: return Status.MISSING
        return if (found.isNative) Status.NATIVE else Status.EXISTS
    }

    /**
     * 决定最终注入点。
     *
     * 判定完全由「类里真实存在的方法」驱动，不用方法名白名单：
     * 白名单漏项时不会报错，只会静默失效——那是最坏的一类失败。
     */
    fun resolve(
        points: List<Point>,
        methods: List<ExistingMethod>,
        wanted: Point,
    ): Resolution {
        val targetStatus = statusOf(wanted, methods)
        val usable = points.filter { statusOf(it, methods) != Status.NATIVE }

        val chosen: Point
        var fallback = false
        var reason: String? = null

        // 先检查「体系错配」：wanted 是标准生命周期方法，但不属于这个类的清单。
        // 典型场景：在 Activity 上选了 onPostCreate，又把类名改成 Application 子类。
        // 这时新建出来的方法框架永远不会调用 —— 注入「成功」但零效果，最坏的一种失败。
        val belongsHere = points.any { it.name == wanted.name && it.signature == wanted.signature }
        val isStandardElsewhere = ALL_STANDARD.any { it.name == wanted.name }
        if (!belongsHere && isStandardElsewhere) {
            val preferred = points.firstOrNull { it.name == "onCreate" } ?: points.first()
            return Resolution(
                target = preferred,
                fallback = true,
                reason = "${wanted.name} 不属于这个类的生命周期清单（当前父类 " +
                    "${if (points == APPLICATION) "Application" else "Activity"} 系），" +
                    "注入进去框架也不会调用。已改为 ${preferred.name}。",
                candidates = candidatesOf(points, methods, preferred.name, true),
            )
        }

        if (targetStatus != Status.NATIVE) {
            chosen = wanted
        } else {
            val alt = CREATE_FALLBACKS
                .mapNotNull { name -> usable.firstOrNull { it.name == name } }
                .firstOrNull()
            if (alt != null) {
                chosen = alt
                fallback = true
                reason = buildString {
                    append("${wanted.name} 是 native 方法，没有字节码可插（常见于加固壳或 NativeActivity）。")
                    append("已改用 ${alt.name} —— ${alt.hint}。")
                }
            } else {
                chosen = wanted
                reason = "${wanted.name} 是 native 方法，且这个类没有其它可插的生命周期方法，" +
                    "换个 Activity 或手动指定方法名再试。"
            }
        }

        return Resolution(
            chosen, fallback, reason,
            candidatesOf(points, methods, chosen.name, fallback),
        )
    }

    private fun candidatesOf(
        points: List<Point>,
        methods: List<ExistingMethod>,
        chosenName: String,
        fallback: Boolean,
    ): List<Candidate> = points.map { p ->
        val st = statusOf(p, methods)
        val note = when (st) {
            Status.EXISTS -> "覆写已有方法，在最前面插入调用"
            Status.MISSING -> "类里没有，将自动创建并补父类调用"
            Status.NATIVE -> "native 方法没有字节码，无法插入"
        }
        Candidate(
            point = p,
            status = st,
            recommended = p.name == chosenName,
            note = if (p.name == chosenName && fallback) "$note · 推荐替代" else note,
        )
    }
}

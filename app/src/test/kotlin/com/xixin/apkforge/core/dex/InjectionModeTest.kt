package com.xixin.apkforge.core.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入模式的落点规划。
 *
 * 防的是「注入成功但行为不对」——这类失败日志全绿、产物正常、效果为零，最难查：
 *  1. 模式铺点铺到了别的类体系的点（`Application` 被塞 `onPostCreate`）；
 *  2. native 点被当成可插点，产出非法 dex；
 *  3. 「每次进入」只铺了一个点，用户以为每次进入都执行，实际只有首次。
 */
class InjectionModeTest {

    private fun method(name: String, sig: String, native: Boolean = false) =
        ExistingMethod(name, sig, native)

    private val activityMethods = listOf(
        method("onCreate", "(Landroid/os/Bundle;)V"),
        method("onStart", "()V"),
        method("onResume", "()V"),
    )

    @Test
    fun `单次注入只落一个点`() {
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val plan = InjectionPlanner.plan(
            InjectionMode.ONCE, LifecyclePoints.ACTIVITY, activityMethods, wanted,
        )
        assertEquals("单次注入不该扩点", 1, plan.targets.size)
        assertEquals("onCreate", plan.targets.single().point.name)
        assertFalse("单次注入不该替换方法体", plan.targets.single().replaceBody)
        assertFalse(plan.autoExpanded)
    }

    @Test
    fun `每次进入会补齐 onStart 与 onResume`() {
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val plan = InjectionPlanner.plan(
            InjectionMode.EVERY_ENTER, LifecyclePoints.ACTIVITY, activityMethods, wanted,
        )
        val names = plan.targets.map { it.point.name }
        assertTrue("必须包含 onStart：$names", names.contains("onStart"))
        assertTrue("必须包含 onResume：$names", names.contains("onResume"))
        assertEquals("不该重复插同一个方法", names.size, names.distinct().size)
        assertNotNull("扩点必须解释给用户", plan.note)
    }

    @Test
    fun `每次进入不会重复插已经在清单里的那个点`() {
        // 用户手动把注入点选成了 onStart，此时不该出现两份 onStart
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onStart" }
        val plan = InjectionPlanner.plan(
            InjectionMode.EVERY_ENTER, LifecyclePoints.ACTIVITY, activityMethods, wanted,
        )
        assertEquals(
            "onStart 只该出现一次：${plan.targets.map { it.point.name }}",
            1, plan.targets.count { it.point.name == "onStart" },
        )
        assertTrue(plan.targets.any { it.point.name == "onResume" })
    }

    @Test
    fun `多点覆盖排除 native 方法`() {
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onStart", "()V"),
            method("onResume", "()V"),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val plan = InjectionPlanner.plan(
            InjectionMode.MULTI_POINT, LifecyclePoints.ACTIVITY, methods, wanted,
        )
        assertTrue("多点覆盖应铺出多个点", plan.targets.size > 1)
        assertTrue(
            "native 的 onCreate 绝不能出现在落点里：${plan.targets.map { it.point.name }}",
            plan.targets.none { it.point.name == "onCreate" },
        )
        plan.targets.forEach {
            assertTrue(
                "落点 ${it.point.name} 的判定不该是 native",
                it.status != LifecyclePoints.Status.NATIVE,
            )
        }
    }

    @Test
    fun `Application 体系不会拿到 Activity 的点`() {
        val appMethods = listOf(method("onCreate", "()V"))
        val wanted = LifecyclePoints.APPLICATION.first { it.name == "onCreate" }
        InjectionMode.entries.forEach { mode ->
            val plan = InjectionPlanner.plan(
                mode, LifecyclePoints.APPLICATION, appMethods, wanted,
            )
            val allowed = LifecyclePoints.APPLICATION.map { it.name }.toSet()
            plan.targets.forEach {
                assertTrue(
                    "$mode 给 Application 铺了不属于它的点 ${it.point.name}",
                    allowed.contains(it.point.name),
                )
            }
        }
    }

    @Test
    fun `替换方法体标记唯一且只影响该点`() {
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val plan = InjectionPlanner.plan(
            InjectionMode.REPLACE_BODY, LifecyclePoints.ACTIVITY, activityMethods, wanted,
        )
        assertEquals(1, plan.targets.size)
        assertTrue("替换方法体必须打上标记", plan.targets.single().replaceBody)
        assertNotNull("替换已有逻辑必须警告用户", plan.note)
    }

    @Test
    fun `native 降级后的点仍被单次注入沿用`() {
        // onCreate native → resolve 会降级到 onPostCreate，单次模式必须沿用这个结论
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onPostCreate", "(Landroid/os/Bundle;)V"),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val plan = InjectionPlanner.plan(
            InjectionMode.ONCE, LifecyclePoints.ACTIVITY, methods, wanted,
        )
        assertEquals("应沿用降级结论", "onPostCreate", plan.targets.single().point.name)
    }
}

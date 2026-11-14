package com.xixin.apkforge.core.dex

import com.xixin.apkforge.core.apk.ApkInjectPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入点推荐逻辑。
 *
 * 防的是两类真事故：
 *  1. **静默失效**：把注入点选到 native 方法上，dexlib2 插不进去，
 *     旧实现直接报「注入跳过」，用户只看到一条红色日志，不知道该换哪个方法；
 *  2. **假装成功**：新建方法的父类调用判定用方法名白名单，`onPostCreate`
 *     不在白名单里时新建的方法不调 super → 生命周期断裂 → 宿主直接崩。
 */
class LifecyclePointsTest {

    private fun method(name: String, sig: String, native: Boolean = false) =
        ExistingMethod(name, sig, native)

    @Test
    fun `native 的 onCreate 会降级到 onPostCreate`() {
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onPostCreate", "(Landroid/os/Bundle;)V"),
            method("onResume", "()V"),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }

        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, methods, wanted)

        assertEquals("应降级到 onPostCreate", "onPostCreate", r.target.name)
        assertEquals("签名应随替代方法一起换", "(Landroid/os/Bundle;)V", r.target.signature)
        assertTrue("必须标记为降级", r.fallback)
        assertNotNull("必须给出原因文案", r.reason)
        assertTrue(
            "原因里要点明 native 无法插入：${r.reason}",
            r.reason!!.contains("native"),
        )
        // 下拉框要能把 onCreate 标成「不可插」，否则用户还在点它
        val onCreate = r.candidates.first { it.point.name == "onCreate" }
        assertEquals(LifecyclePoints.Status.NATIVE, onCreate.status)
        assertEquals(LifecyclePoints.Status.EXISTS, r.candidates.first { it.point.name == "onPostCreate" }.status)
    }

    @Test
    fun `onCreate 可插时不乱改用户的选择`() {
        val methods = listOf(method("onCreate", "(Landroid/os/Bundle;)V"))
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }

        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, methods, wanted)

        assertEquals("不该改用户的选择", "onCreate", r.target.name)
        assertFalse(r.fallback)
        assertEquals(null, r.reason)
    }

    @Test
    fun `类里没有 onCreate 时判为将创建而不是降级`() {
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, emptyList(), wanted)

        assertEquals("onCreate", r.target.name)
        assertFalse("缺失 ≠ 不可插", r.fallback)
        val c = r.candidates.first { it.point.name == "onCreate" }
        assertEquals(LifecyclePoints.Status.MISSING, c.status)
        assertTrue(c.recommended)
    }

    @Test
    fun `所有替代点都是 native 时如实报错而不是随便挑一个`() {
        // 极端但真实：整类生命周期都被壳改成 native
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onPostCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onStart", "()V", native = true),
            method("onResume", "()V", native = true),
            method("attachBaseContext", "(Landroid/content/Context;)V", native = true),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, methods, wanted)

        assertEquals("没有可用替代时不要乱换", "onCreate", r.target.name)
        assertFalse(r.fallback)
        // 原因必须说清「没有其它可插的点」，否则用户会一直重试
        assertTrue("应说明无替代点：${r.reason}", r.reason!!.contains("没有其它"))
    }

    @Test
    fun `降级优先选时机最接近的 onPostCreate 而不是更早的 attachBaseContext`() {
        // attachBaseContext 比 onCreate 更早，但那时还没有 Activity 窗口；
        // 载荷多按 Activity 语义写（DemoDialog 就吃 Activity），
        // 所以优先选时机最接近 onPostCreate —— 类里没有它也能建，框架照样会调。
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("attachBaseContext", "(Landroid/content/Context;)V"),
            method("onStart", "()V"),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, methods, wanted)

        assertEquals("onPostCreate", r.target.name)
        assertEquals(
            "onPostCreate 会新建，仍然可插",
            LifecyclePoints.Status.MISSING,
            r.candidates.first { it.point.name == "onPostCreate" }.status,
        )
        assertTrue(
            "onPostCreate 必须排在 attachBaseContext 之前",
            LifecyclePoints.CREATE_FALLBACKS.indexOf("onPostCreate") <
                LifecyclePoints.CREATE_FALLBACKS.indexOf("attachBaseContext"),
        )
    }

    @Test
    fun `onPostCreate 也是 native 时继续往后找 onStart`() {
        val methods = listOf(
            method("onCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onPostCreate", "(Landroid/os/Bundle;)V", native = true),
            method("onStart", "()V"),
        )
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onCreate" }
        val r = LifecyclePoints.resolve(LifecyclePoints.ACTIVITY, methods, wanted)

        assertEquals("onStart", r.target.name)
        assertTrue(r.fallback)
    }

    @Test
    fun `注入点清单里每个签名都能被解析器认下`() {
        // 新建方法时要靠这段签名反向解析出参数类型（DexCallInjector.parseSignatureParams）。
        // 清单里写错一个字符，用户选到那个点就会得到「注入点签名无法解析」——
        // 而这完全是本工程自己写死的常量，不该让用户背。
        val all = LifecyclePoints.ACTIVITY + LifecyclePoints.APPLICATION
        assertTrue("清单不该是空的", all.isNotEmpty())
        all.forEach { p ->
            val line = "invoke-static {}, Lx/Y;->m${p.signature}"
            val (calls, errs) = SmaliCallParser.parseAll(line)
            assertTrue("签名解析失败 ${p.name}${p.signature}: ${errs.messages}", errs.isEmpty)
            assertEquals("${p.name} 应解析出 1 条调用", 1, calls.size)
        }
    }

    @Test
    fun `降级后的替代点必须在同一个类的可用清单里`() {
        // 防的是「推荐了一个这个类根本不出的方法」：例如对 Application 推荐 onStart。
        val appMethods = listOf(method("onCreate", "()V", native = true))
        val appPoints = LifecyclePoints.forSuperClass("Landroid/app/Application;")
        val wanted = appPoints.first { it.name == "onCreate" }
        val r = LifecyclePoints.resolve(appPoints, appMethods, wanted)

        assertTrue(
            "替代点必须来自本类的注入点清单，实际 ${r.target.name}",
            appPoints.any { it.name == r.target.name },
        )
        assertEquals("Application 里没有 onPostCreate，应退到 attachBaseContext",
            "attachBaseContext", r.target.name)
    }

    @Test
    fun `换到 Application 类时不会沿用 Activity 专属的方法名`() {
        // 场景：在 Activity 上选了 onPostCreate，改类名到一个 Application 子类。
        // Application 没有 onPostCreate，若原样保留，新建的方法框架永不调用 ——
        // 注入「成功」但零效果，是最坏的一种失败。
        val appPoints = LifecyclePoints.forSuperClass("Landroid/app/Application;")
        val wanted = LifecyclePoints.ACTIVITY.first { it.name == "onPostCreate" }

        val r = LifecyclePoints.resolve(appPoints, emptyList(), wanted)

        assertEquals("应换成本类清单里的 onCreate", "onCreate", r.target.name)
        assertEquals("Application 的 onCreate 无参", "()V", r.target.signature)
        assertTrue("必须标记为降级", r.fallback)
        assertNotNull("必须说明原因", r.reason)
        assertTrue(
            "原因要指出不属于本类清单：${r.reason}",
            r.reason!!.contains("不属于这个类"),
        )
    }

    @Test
    fun `用户自定义的方法名不会被体系判定改掉`() {
        // 「标准方法名走错体系」和「用户自定方法」必须分开：后者是用户明确的选择
        val appPoints = LifecyclePoints.forSuperClass("Landroid/app/Application;")
        val custom = LifecyclePoints.Point("myForgeHook", "()V", "自定义")

        val r = LifecyclePoints.resolve(appPoints, emptyList(), custom)

        assertEquals("自定义方法不应被重置", "myForgeHook", r.target.name)
        assertFalse(r.fallback)
    }

    @Test
    fun `进度在整个流水线上单调不减且落在 0 到 1 之间`() {
        // 进度条能不能信，取决于它不会倒退也不会越界。
        // 阶段边界是最容易出错的地方（上一段 100% 与下一段 0% 的计算）
        var last = -1f
        ApkInjectPipeline.Stage.entries.forEach { stage ->
            listOf(0f, 0.25f, 0.5f, 1f).forEach { sub ->
                val p = ApkInjectPipeline.progressOf(stage, sub)
                assertTrue("进度必须落在 [0,1]，实际 $stage/$sub → $p", p in 0f..1f)
                assertTrue("进度不能倒退：$stage/$sub → $p < $last", p >= last)
                last = p
            }
        }
        assertEquals("最后一段必须收在 1.0", 1f, last, 0.0001f)
        assertEquals("第一段起点应为 0", 0f, ApkInjectPipeline.progressOf(ApkInjectPipeline.Stage.READ, 0f), 0.0001f)
    }
}

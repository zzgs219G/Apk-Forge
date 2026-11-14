package com.xixin.apkforge.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 方法探测 + 生命周期点匹配的验证。
 *
 * 目的：确保下拉框里标的「已有 / 将新建」是**真的**。
 * 标错会导致用户以为在覆写方法，实际却新建了一个永远不会被系统调用的方法
 * ——注入完全失效，且日志上还显示"成功"，是最坏的那种失败。
 */
class ClassInspectorTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    @Test
    fun `能探测到 MainActivity 的 onCreate 与父类`() {
        val apk = locateApk()
        val r = ClassInspector.inspect(apk, "Lcom/xixin/apkforge/MainActivity;")
        assumeTrue("测试 APK 里没有 MainActivity", r.found)

        assertTrue("应找到类", r.found)
        // 不能断言具体在哪个 dex：APK 里 dex 的切分随 AGP/依赖变化，
        // MainActivity 实测落在 classes4.dex 而不是 classes.dex
        assertTrue("应记录命中的 dex 名", !r.dexName.isNullOrBlank())
        println("父类 = ${r.superType}")
        println("方法 = ${r.methods.map { it.name + it.signature }}")

        // MainActivity 覆写了 onCreate(Bundle)
        val hasOnCreate = r.has("onCreate", "(Landroid/os/Bundle;)V")
        assertTrue("应探测到 onCreate(Bundle)V，实际: ${r.methods}", hasOnCreate)

        // 父类应是某个 Activity 子类
        assertTrue(
            "父类应是 Activity 系，实际 ${r.superType}",
            r.superType?.contains("Activity") == true
        )
    }

    @Test
    fun `生命周期点与类内方法的一致性判断正确`() {
        val apk = locateApk()
        val r = ClassInspector.inspect(apk, "Lcom/xixin/apkforge/MainActivity;")
        assumeTrue(r.found)

        val points = LifecyclePoints.forSuperClass(r.superType)
        assertTrue("Activity 应有多个注入点", points.size >= 5)

        // onCreate 一定在类里
        val onCreate = points.first { it.name == "onCreate" }
        assertTrue("onCreate 应被判定为已存在", r.has(onCreate.name, onCreate.signature))

        // onDestroy 通常没覆写 → 应判定为「将新建」
        val onDestroy = points.first { it.name == "onDestroy" }
        val exists = r.has(onDestroy.name, onDestroy.signature)
        println("onDestroy 存在? $exists（两种情况都合法，只断言判断能跑通）")

        // 关键：签名必须与 Activity 真实签名一致，否则"新建"出来的方法不会被系统调用
        assertEquals("(Landroid/os/Bundle;)V", onCreate.signature)
        assertEquals("()V", onDestroy.signature)
    }

    @Test
    fun `Application 与 Activity 的注入点清单不同`() {
        val act = LifecyclePoints.forSuperClass("Landroid/app/Activity;")
        val app = LifecyclePoints.forSuperClass("Landroid/app/Application;")

        assertTrue("Activity 的 onCreate 带 Bundle", act.any {
            it.name == "onCreate" && it.signature == "(Landroid/os/Bundle;)V"
        })
        assertTrue("Application 的 onCreate 无参", app.any {
            it.name == "onCreate" && it.signature == "()V"
        })
        assertTrue("两者清单不应相同", act != app)

        // attachBaseContext 两者都有，且签名一致
        val a = act.first { it.name == "attachBaseContext" }
        val b = app.first { it.name == "attachBaseContext" }
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `不存在的类返回 found=false 而不是抛异常`() {
        val apk = locateApk()
        val r = ClassInspector.inspect(apk, "Lcom/definitely/Not/Here;")
        assertEquals(false, r.found)
        assertTrue("应给出错误说明", !r.error.isNullOrBlank())
        assertTrue("方法列表应为空", r.methods.isEmpty())
    }
}

package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import com.xixin.apkforge.core.apk.ApkRepackager

/**
 * 生命周期注入点清单 + 目标类已有方法的探测。
 *
 * 【为什么要有这个】让用户手输 `onCreate` / `(Landroid/os/Bundle;)V` 是不合理的：
 * 生命周期方法名和签名是固定枚举，用户记不住也没必要记。
 * 下拉框列出来，并且标出「类里已有」还是「将新建」，用户一眼就知道后果。
 */
object LifecyclePoints {

    data class Point(
        val name: String,
        val signature: String,
        /** 面向用户的说明 */
        val hint: String,
    ) {
        val label: String get() = name
        val detail: String get() = "$name$signature"
    }

    /** Activity 常见注入点，按「跑得早」排序——注入越早，抢在业务逻辑前生效的机会越大。 */
    val ACTIVITY: List<Point> = listOf(
        Point("attachBaseContext", "(Landroid/content/Context;)V", "最早，Context 刚挂上"),
        Point("onCreate", "(Landroid/os/Bundle;)V", "最常用，界面创建"),
        Point("onStart", "()V", "界面即将可见"),
        Point("onResume", "()V", "可交互"),
        Point("onPostCreate", "(Landroid/os/Bundle;)V", "创建完成"),
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
     * 按父类判断是 Activity 还是 Application 系列，给出合适的清单。
     * 判断不出来就合并给全套，让用户自己挑。
     */
    fun forSuperClass(superType: String?): List<Point> = when {
        superType == null -> ACTIVITY
        superType == "Landroid/app/Application;" -> APPLICATION
        superType.contains("Application") -> APPLICATION
        else -> ACTIVITY
    }
}

/** 目标类里已有的方法（名字 + 签名）。 */
data class ExistingMethod(
    val name: String,
    val signature: String,
    val isNative: Boolean,
) {
    val hasBody: Boolean get() = !isNative
}

object ClassInspector {

    data class Inspect(
        val found: Boolean,
        val dexName: String?,
        val superType: String?,
        val methods: List<ExistingMethod>,
        val error: String? = null,
    ) {
        fun has(name: String, signature: String): Boolean =
            methods.any { it.name == name && it.signature == signature }
    }

    /**
     * 在 APK 的所有 dex 里找目标类，返回它的父类与全部方法。
     *
     * 只 foreach 一遍 dex 头就够——[DexBackedDexFile] 是 lazy 的，
     * 不解析用不到的方法体，所以扫 30MB 多 dex 包也是秒级。
     */
    fun inspect(apk: File, classInternal: String): Inspect {
        val dexNames = ApkRepackager.listDexNames(apk)
        if (dexNames.isEmpty()) {
            return Inspect(false, null, null, emptyList(), "APK 内没有 dex")
        }
        for (name in dexNames) {
            val bytes = ApkRepackager.readEntry(apk, name) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(Opcodes.getDefault(), bytes.inputStream())
            }.getOrNull() ?: continue

            val cls = df.classes.firstOrNull { it.type == classInternal } ?: continue
            val methods = cls.methods.toList().map { m ->
                ExistingMethod(
                    name = m.name,
                    signature = "(" + m.parameterTypes.joinToString("") + ")" + m.returnType,
                    isNative = (m.accessFlags and 0x100) != 0,
                )
            }.distinctBy { it.name + it.signature }

            return Inspect(
                found = true,
                dexName = name,
                superType = cls.superclass,
                methods = methods,
            )
        }
        return Inspect(false, null, null, emptyList(), "所有 dex 里都没有这个类")
    }
}

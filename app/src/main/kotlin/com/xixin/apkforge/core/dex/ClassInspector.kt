package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import com.xixin.apkforge.core.apk.ApkRepackager

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

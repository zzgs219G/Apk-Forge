package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.core.dex.SmaliCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 对比注入前后**所有类的 `@kotlin.Metadata` 注解内容**。
 *
 * 【为什么需要这条】注入后 app 一装就闪退，MT 打开 dex 直接报错，jadx 报
 * `KotlinMetadataDecompile` 失败。之前只核对了 `MainActivity`，它恰好是对的，
 * 但 `androidx.collection.*` 等一大批类的 Metadata 已经损坏。
 *
 * 判据：用 dexlib2 读出每个类的注解，比较**字符串内容**而不是下标 ——
 * 下标会因为池重排而合法地变化，内容不该变。
 */
class MetadataIntegrityTest {

    private fun loadDex(apk: File, entry: String): ByteArray =
        ZipFile(apk).use { z -> z.getInputStream(z.getEntry(entry)).use { it.readBytes() } }

    /** 收集「类描述符 → kotlin.Metadata 注解的 (元素名 → 值文本)」 */
    private fun collectMetadata(dex: ByteArray): Map<String, Map<String, String>> {
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(dex), dex.inputStream())
        val result = HashMap<String, Map<String, String>>()
        for (cls in df.classes) {
            val meta = cls.annotations.firstOrNull {
                it.type == "Lkotlin/Metadata;"
            } ?: continue
            val elems = LinkedHashMap<String, String>()
            for (e in meta.elements) {
                elems[e.name] = valueToText(e.value)
            }
            result[cls.type] = elems
        }
        return result
    }

    /** 把 encoded_value 转成可比较的文本（递归处理数组与嵌套注解）。 */
    private fun valueToText(v: com.android.tools.smali.dexlib2.iface.value.EncodedValue?): String {
        if (v == null) return "null"
        return when (v) {
            is com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue ->
                "[" + v.value.joinToString(",") { valueToText(it) } + "]"
            is com.android.tools.smali.dexlib2.iface.value.StringEncodedValue ->
                "\"" + v.value + "\""
            is com.android.tools.smali.dexlib2.iface.value.IntEncodedValue ->
                "i:" + v.value
            is com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue ->
                "b:" + v.value
            is com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue ->
                "ann:" + v.type + "{" +
                    v.elements.joinToString(",") { it.name + "=" + valueToText(it.value) } + "}"
            is com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue ->
                "t:" + v.value
            is com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue ->
                "e:" + v.value
            else -> v.valueType.toString() + ":" + v.toString()
        }
    }

    @Test
    fun `注入前后所有类的 kotlin Metadata 内容一致`() {
        val orig = File(System.getProperty("user.home"), "o.apk")
        val inj = File(System.getProperty("user.home"), "i.apk")
        org.junit.Assume.assumeTrue("缺少对比样本 o.apk / i.apk", orig.isFile && inj.isFile)

        val before = collectMetadata(loadDex(orig, "classes.dex"))
        val after = collectMetadata(loadDex(inj, "classes.dex"))

        println("META 原版 ${before.size} 个类有 Metadata，注入后 ${after.size} 个")
        assertTrueMsg("Metadata 类数量变了", before.size == after.size)

        val diffs = mutableListOf<String>()
        before.forEach { (clsType, elems) ->
            val other = after[clsType]
            if (other == null) {
                if (diffs.size < 10) diffs += "$clsType 的 Metadata 消失了"
                return@forEach
            }
            elems.forEach { (k, v) ->
                if (other[k] != v) {
                    if (diffs.size < 10) {
                        diffs += "$clsType 的 $k 变了：\n  原=$v\n  新=${other[k]}"
                    }
                }
            }
        }
        assertEquals(
            "有 ${diffs.size} 个类的 Metadata 内容变了（前 10 条）：\n" +
                diffs.joinToString("\n\n"),
            0,
            diffs.size,
        )
        println("META 全部 ${before.size} 个类的 Metadata 内容一致")
    }

    private fun assertTrueMsg(msg: String, cond: Boolean) = org.junit.Assert.assertTrue(msg, cond)
}

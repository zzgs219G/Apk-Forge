package com.xixin.apkforge.core.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `AxmlManifest` 的**纯 JVM** 测试：不依赖任何外部 APK，样本由下面的 builder 现场拼字节。
 *
 * 为什么必须能自己造样本：本轮修的三个 bug（alias 未换算、enabled=false 未跳过、
 * MAIN/LAUNCHER 跨 filter 配对）都只在「换图标」这类清单形态下出现，
 * 而仓库里能入库的真实包都是普通形态（LAUNCHER 直接挂在 activity 上）。
 * 拿真实包测等于没测到出问题的那条路径。
 *
 * 样本形态对齐 MT 管理器 2.26.11 的真实清单（实测）：
 * ```
 * <activity       android:name="bin.mt.plus.Main" />              ← 真类，无 filter
 * <activity-alias android:name="...MainNoBgIcon" enabled="false"
 *                 targetActivity="bin.mt.plus.Main">   <filter>MAIN+LAUNCHER</filter>
 * <activity-alias android:name="...MainDarkIcon" enabled="false"
 *                 targetActivity="bin.mt.plus.Main">   <filter>MAIN+LAUNCHER</filter>
 * <activity-alias android:name="...MainLightIcon" enabled="true"
 *                 targetActivity="bin.mt.plus.Main">   <filter>MAIN+LAUNCHER</filter>
 * ```
 */
class AxmlManifestTest {

    // ────────────────────────── 样本构造 ──────────────────────────

    private class El(
        val name: String,
        val attrs: List<Pair<String, Any>> = emptyList(),
        val children: List<El> = emptyList(),
    )

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** 把元素树编码成最小合法 AXML。属性值支持 String（TYPE_STRING）与 Boolean（TYPE_INT_BOOLEAN）。 */
    private fun axml(root: El): ByteArray {
        val strings = ArrayList<String>()
        fun intern(s: String): Int {
            val i = strings.indexOf(s)
            if (i >= 0) return i
            strings.add(s)
            return strings.size - 1
        }

        intern("android")
        intern(androidNs)
        fun walk(e: El) {
            intern(e.name)
            e.attrs.forEach { (k, v) ->
                intern(k)
                if (v is String) intern(v)
            }
            e.children.forEach { walk(it) }
        }
        walk(root)

        // ---- string pool ----
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            val utf8 = s.toByteArray(Charsets.UTF_8)
            fun len(v: Int) {
                if (v < 0x80) data.write(v)
                else {
                    data.write(0x80 or (v shr 8))
                    data.write(v and 0xFF)
                }
            }
            len(s.length)
            len(utf8.size)
            data.write(utf8)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolData = data.toByteArray()

        val poolHeaderLen = 28
        val stringsStart = poolHeaderLen + strings.size * 4
        val poolSize = stringsStart + poolData.size

        // ---- 事件流 ----
        val events = ByteArrayOutputStream()
        fun chunk(type: Int, headerLen: Int, body: ByteArray) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort())
            b.putShort(headerLen.toShort())
            b.putInt(8 + body.size)
            events.write(b.array())
            events.write(body)
        }
        fun startEl(e: El) {
            // node 头(8) + lineNumber(4) + comment(4) + ns(4) + name(4)
            //   + attributeStart(2) + attributeSize(2) + attributeCount(2)
            //   + idIndex(2) + classIndex(2) + styleIndex(2) = 28
            val b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1)
            b.putInt(-1)
            b.putInt(-1)
            b.putInt(strings.indexOf(e.name))
            b.putShort(20)
            b.putShort(20)
            b.putShort(e.attrs.size.toShort())
            b.putShort(0)
            b.putShort(0)
            b.putShort(0)
            val body = ByteArrayOutputStream()
            body.write(b.array())
            e.attrs.forEach { (k, v) ->
                val ab = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                ab.putInt(if (k == "package") -1 else strings.indexOf(androidNs))
                ab.putInt(strings.indexOf(k))
                if (v is String) {
                    ab.putInt(strings.indexOf(v))
                    ab.putShort(8)
                    ab.put(0)
                    ab.put(0x03)                 // TYPE_STRING
                    ab.putInt(strings.indexOf(v))
                } else {
                    ab.putInt(-1)
                    ab.putShort(8)
                    ab.put(0)
                    ab.put(0x12)                 // TYPE_INT_BOOLEAN
                    ab.putInt(if (v as Boolean) -1 else 0)
                }
                body.write(ab.array())
            }
            chunk(0x0102, 16, body.toByteArray())
        }
        fun endEl(name: String) {
            val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1)
            b.putInt(-1)
            b.putInt(-1)
            b.putInt(strings.indexOf(name))
            chunk(0x0103, 16, b.array())
        }
        fun ns(start: Boolean) {
            val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1)
            b.putInt(-1)
            b.putInt(strings.indexOf(androidNs))
            b.putInt(strings.indexOf("android"))
            chunk(if (start) 0x0100 else 0x0101, 16, b.array())
        }

        ns(true)
        fun emit(e: El) {
            startEl(e)
            e.children.forEach { emit(it) }
            endEl(e.name)
        }
        emit(root)
        ns(false)

        // ---- 拼文件 ----
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(0x0001.toShort())
        pool.putShort(poolHeaderLen.toShort())
        pool.putInt(poolSize)
        pool.putInt(strings.size)
        pool.putInt(0)
        pool.putInt(0x100)                       // flags: UTF-8
        pool.putInt(stringsStart)
        pool.putInt(0)
        offsets.forEach { pool.putInt(it) }
        pool.put(poolData)

        val ev = events.toByteArray()
        val total = 8 + poolSize + ev.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003.toShort())
        out.putShort(8.toShort())
        out.putInt(total)
        out.put(pool.array())
        out.put(ev)
        return out.array()
    }

    private fun manifest(pkg: String, children: List<El>) =
        El("manifest", listOf("package" to pkg), children)

    private fun androidApp(vararg children: El) =
        El("application", children = children.toList())

    private fun launcherFilter() = El(
        "intent-filter", children = listOf(
            El("action", listOf("name" to "android.intent.action.MAIN")),
            El("category", listOf("name" to "android.intent.category.LAUNCHER")),
        )
    )

    // ────────────────────────── 断言 ──────────────────────────

    /**
     * 防本轮 bug 1：`activity-alias` 的 `android:name` 不是类名，dex 里没有这个类。
     * 直接返回它会让注入报「包里找不到这个类」。必须换算成 `targetActivity`。
     */
    @Test
    fun `alias换算出真实主类而不是别名`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "bin.mt.plus", listOf(
                        androidApp(
                            El("activity", listOf("name" to "bin.mt.plus.Main")),
                            El(
                                "activity-alias", listOf(
                                    "name" to "bin.mt.plus.MainLightIcon",
                                    "targetActivity" to "bin.mt.plus.Main",
                                ), listOf(launcherFilter())
                            ),
                        )
                    )
                )
            )
        )

        assertEquals(listOf("bin.mt.plus.Main"), r.launcherActivities)
        assertEquals("bin.mt.plus.Main", r.primaryLauncher)
        assertEquals(mapOf("bin.mt.plus.MainLightIcon" to "bin.mt.plus.Main"), r.aliasMap)
    }

    /**
     * 防本轮 bug 2：多图标切换里并排声明多个 alias，只有一个是 enabled 的。
     * 未启用的不会出现在启动器里。旧实现不看 enabled，`first()` 稳定拿到禁用项。
     */
    @Test
    fun `enabled为false的别名被跳过`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "bin.mt.plus", listOf(
                        androidApp(
                            El(
                                "activity-alias", listOf(
                                    "name" to "bin.mt.plus.MainNoBgIcon",
                                    "enabled" to false,
                                    "targetActivity" to "bin.mt.plus.Main",
                                ), listOf(launcherFilter())
                            ),
                            El(
                                "activity-alias", listOf(
                                    "name" to "bin.mt.plus.MainDarkIcon",
                                    "enabled" to false,
                                    "targetActivity" to "bin.mt.plus.Main",
                                ), listOf(launcherFilter())
                            ),
                            El(
                                "activity-alias", listOf(
                                    "name" to "bin.mt.plus.MainLightIcon",
                                    "enabled" to true,
                                    "targetActivity" to "bin.mt.plus.Main",
                                ), listOf(launcherFilter())
                            ),
                        )
                    )
                )
            )
        )

        // 三个 alias 指向同一个类，去重后只剩一个；关键在于没拿到禁用的那两个
        assertEquals(listOf("bin.mt.plus.Main"), r.launcherActivities)
        assertTrue(
            "禁用项不该出现在 aliasMap 里",
            r.aliasMap.keys.none { it.contains("NoBgIcon") || it.contains("DarkIcon") },
        )
    }

    /**
     * 防本轮 bug 3：Android 判定启动入口要求 MAIN 与 LAUNCHER 在**同一个** filter 内。
     * 旧实现跨 filter 累积布尔值，会把这种组件误判成入口——而它在启动器里不出现。
     */
    @Test
    fun `MAIN与LAUNCHER分处不同filter时不算入口`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "com.foo", listOf(
                        androidApp(
                            El(
                                "activity", listOf("name" to "com.foo.NotLauncher"),
                                listOf(
                                    El(
                                        "intent-filter", children = listOf(
                                            El(
                                                "action",
                                                listOf("name" to "android.intent.action.MAIN")
                                            ),
                                        )
                                    ),
                                    El(
                                        "intent-filter", children = listOf(
                                            El(
                                                "category",
                                                listOf("name" to "android.intent.category.LAUNCHER")
                                            ),
                                        )
                                    ),
                                )
                            ),
                        )
                    )
                )
            )
        )

        assertEquals(emptyList<String>(), r.launcherActivities)
    }

    /** 回归：普通 App（LAUNCHER 直接挂在 activity 上）行为不变。 */
    @Test
    fun `普通activity照常识别`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "com.foo", listOf(
                        androidApp(
                            El("activity", listOf("name" to ".MainActivity"), listOf(launcherFilter())),
                        )
                    )
                )
            )
        )

        assertEquals(listOf("com.foo.MainActivity"), r.launcherActivities)
        assertTrue("普通包不该有 alias 换算记录", r.aliasMap.isEmpty())
    }

    /** 同一 filter 内多个 category（LAUNCHER + LEANBACK_LAUNCHER）仍算入口。 */
    @Test
    fun `同一filter内的多个category不影响判定`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "com.foo", listOf(
                        androidApp(
                            El(
                                "activity", listOf("name" to "com.foo.Tv"),
                                listOf(
                                    El(
                                        "intent-filter", children = listOf(
                                            El(
                                                "action",
                                                listOf("name" to "android.intent.action.MAIN")
                                            ),
                                            El(
                                                "category",
                                                listOf("name" to "android.intent.category.LEANBACK_LAUNCHER")
                                            ),
                                            El(
                                                "category",
                                                listOf("name" to "android.intent.category.LAUNCHER")
                                            ),
                                        )
                                    ),
                                )
                            ),
                        )
                    )
                )
            )
        )

        assertEquals(listOf("com.foo.Tv"), r.launcherActivities)
    }

    /**
     * alias 的 targetActivity 写成相对名（`.Main`）时要补全成完整类名——
     * 否则注入时会拿 `.Main` 去找类，必然找不到。
     */
    @Test
    fun `alias的相对targetActivity被补全`() {
        val r = AxmlManifest.parseBytes(
            axml(
                manifest(
                    "com.foo", listOf(
                        androidApp(
                            El(
                                "activity-alias", listOf(
                                    "name" to ".IconAlias",
                                    "targetActivity" to ".Main",
                                ), listOf(launcherFilter())
                            ),
                        )
                    )
                )
            )
        )

        assertEquals(listOf("com.foo.Main"), r.launcherActivities)
    }
}

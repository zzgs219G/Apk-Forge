package com.xixin.apkforge.core.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `extractNativeLibs` 改写（[AxmlBoolean] + [AxmlEditor.setBooleanAttrOnApplication]）。
 *
 * ## 防的是什么
 *
 * 目标包 `extractNativeLibs="false"` 时，系统**不会**把 `.so` 解压到
 * `nativeLibraryDir`，`System.load()` 必然失败：
 *
 * ```
 * SigFix: 找不到 native 库: /data/app/.../lib/arm64/libusign.so（该 ABI 未投放？）
 *         → 跳过 native 重定向
 * ```
 *
 * 于是只剩 Java 层 hook —— App 自己 `ZipFile(base.apk)` 读签名块、
 * native so 解析 Signing Block 这两条路径全部绕过，去校验等于没用。
 *
 * 而这一切**没有任何报错**：产物能装、App 能启动、Java 层也生效，
 * 只有 native 层静默缺席。所以必须靠测试钉住。
 *
 * ## 三种形态都要覆盖
 *
 * | 目标包 | 期望 |
 * |---|---|
 * | `extractNativeLibs="false"` | 原位翻成 true（长度不变）|
 * | `extractNativeLibs="true"`  | 不动（幂等）|
 * | 没有该属性                | 新增，且**按 resourceId 升序插入** |
 */
class AxmlBooleanTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    // 属性名 → framework 资源 ID（取真实值，顺序会影响插入位置）
    private val attrIds = mapOf(
        "name" to 0x01010003,
        "extractNativeLibs" to 0x010104EA,
        "usesCleartextTraffic" to 0x010104EC,
        "theme" to 0x01010000,
    )

    private class El(
        val name: String,
        val attrs: List<Pair<String, Any?>> = emptyList(),
        val children: List<El> = emptyList(),
    )

    /**
     * 把元素树编码成 AXML，**带 resource map**。
     *
     * resource map 是必需的：没有它 [AxmlEditor.attrInsertIndex] 无法判断
     * 新属性该插在哪，只能追加末尾 —— 而 `PackageParser` 遇乱序会提前停止
     * 扫描，属性静默丢失。真实 AXML 只要含 `android:` 属性就必有这个 chunk。
     */
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

        // ---- 字符串池 ----
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

        // ---- resource map：每个池索引对应一个资源 ID（没有则 0）----
        val resCount = strings.size
        val resMap = ByteBuffer.allocate(8 + resCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        resMap.putShort(0x0180.toShort())
        resMap.putShort(8.toShort())
        resMap.putInt(8 + resCount * 4)
        strings.forEachIndexed { i, s ->
            resMap.putInt(attrIds[s] ?: 0)
        }

        // ---- 事件流 ----
        val events = ByteArrayOutputStream()
        fun chunk(type: Int, body: ByteArray) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort())
            b.putShort(16.toShort())
            b.putInt(8 + body.size)
            events.write(b.array())
            events.write(body)
        }
        fun startEl(e: El) {
            val b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1); b.putInt(-1); b.putInt(-1)
            b.putInt(strings.indexOf(e.name))
            b.putShort(20); b.putShort(20)
            b.putShort(e.attrs.size.toShort())
            b.putShort(0); b.putShort(0); b.putShort(0)
            val body = ByteArrayOutputStream()
            body.write(b.array())
            e.attrs.forEach { (k, v) ->
                val ab = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                ab.putInt(if (k == "package") -1 else strings.indexOf(androidNs))
                ab.putInt(strings.indexOf(k))
                when (v) {
                    is String -> {
                        ab.putInt(strings.indexOf(v))   // rawValue = 池索引
                        ab.putShort(8); ab.put(0); ab.put(0x03)
                        ab.putInt(strings.indexOf(v))
                    }
                    is Boolean -> {
                        ab.putInt(-1)                    // rawValue = NO_INDEX
                        ab.putShort(8); ab.put(0); ab.put(0x12)
                        ab.putInt(if (v) -1 else 0)
                    }
                    else -> {                            // 属性不存在（占位，不写）
                        ab.putInt(-1); ab.putShort(8); ab.put(0); ab.put(0); ab.putInt(0)
                    }
                }
                body.write(ab.array())
            }
            chunk(0x0102, body.toByteArray())
        }
        fun endEl(name: String) {
            val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1); b.putInt(-1); b.putInt(-1); b.putInt(strings.indexOf(name))
            chunk(0x0103, b.array())
        }
        fun ns(start: Boolean) {
            val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1); b.putInt(-1)
            b.putInt(strings.indexOf(androidNs)); b.putInt(strings.indexOf("android"))
            chunk(if (start) 0x0100 else 0x0101, b.array())
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
        pool.putInt(0x100)                       // UTF-8
        pool.putInt(stringsStart)
        pool.putInt(0)
        offsets.forEach { pool.putInt(it) }
        pool.put(poolData)

        val rm = resMap.array()
        val ev = events.toByteArray()
        val total = 8 + poolSize + rm.size + ev.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003.toShort())
        out.putShort(8.toShort())
        out.putInt(total)
        out.put(pool.array())
        out.put(rm)
        out.put(ev)
        return out.array()
    }

    private fun manifest(children: List<El>) =
        El("manifest", listOf("package" to "com.foo"), children)

    private fun app(vararg attrs: Pair<String, Any?>) =
        El("application", attrs.toList())

    // ── 断言 ──────────────────────────────────────────────────

    /** 形态 1：`extractNativeLibs="false"` → 原位翻成 true。 */
    @Test
    fun `false 被改成 true`() {
        val src = axml(
            manifest(
                listOf(
                    app(
                        "name" to "com.foo.App",
                        "extractNativeLibs" to false,
                    )
                )
            )
        )
        assertEquals("前置：夹具应是 false", false, AxmlBoolean.getExtractNativeLibs(src))

        val (out, outcome) = AxmlBoolean.setExtractNativeLibsTrue(src)
        assertEquals("应是原位翻转", AxmlBoolean.Outcome.FLIPPED, outcome)
        assertEquals("改后必须是 true", true, AxmlBoolean.getExtractNativeLibs(out))

        // 原位改写不该改变长度（这是「不重建字符串池」的判据）
        assertEquals(
            "原位改写不该改变 Manifest 长度（改长度说明走了重建路径）",
            src.size,
            out.size,
        )
    }

    /** 形态 2：已经是 true → 不动（幂等）。 */
    @Test
    fun `已经是 true 时不改动`() {
        val src = axml(
            manifest(listOf(app("name" to "com.foo.App", "extractNativeLibs" to true)))
        )
        val (out, outcome) = AxmlBoolean.setExtractNativeLibsTrue(src)
        assertEquals(AxmlBoolean.Outcome.ALREADY_ON, outcome)
        assertTrue("内容不该变", src.contentEquals(out))
    }

    /**
     * 形态 3：属性**不存在** → 新增。
     *
     * 这是 ApkForge 相对云端脚本的关键能力：云端
     * `patch-extractnativelibs.py` 遇到属性不存在直接报错退出，
     * 而很多包（尤其 targetSdk 较低的）根本不写这个属性。
     */
    @Test
    fun `属性不存在时新增并生效`() {
        val src = axml(
            manifest(listOf(app("name" to "com.foo.App")))
        )
        assertNull("前置：夹具应没有该属性", AxmlBoolean.getExtractNativeLibs(src))

        val (out, outcome) = AxmlBoolean.setExtractNativeLibsTrue(src)
        assertEquals("应是新增", AxmlBoolean.Outcome.ADDED, outcome)
        assertEquals("新增后必须读出 true", true, AxmlBoolean.getExtractNativeLibs(out))
        assertTrue("长度应增加（扩了字符串池）", out.size > src.size)

        // 原有的 android:name 不能丢
        assertEquals(
            "新增属性不能破坏已有的 android:name",
            "com.foo.App",
            AxmlEditor.getAppName(out),
        )
    }

    /**
     * 防红线：新增的属性必须按 resourceId 升序插入。
     *
     * `extractNativeLibs` 的 ID (0x010104EA) 大于 `android:name` (0x01010003)，
     * 所以必须排在 name 之后。若插到 name 前面，`PackageParser` 遇乱序会
     * 提前停止扫描 → 两个属性都可能读不到。
     */
    @Test
    fun `新增属性按 resourceId 升序插入`() {
        val src = axml(
            manifest(
                listOf(
                    app(
                        "name" to "com.foo.App",
                        "usesCleartextTraffic" to true,   // 0x010104EC，比 extractNativeLibs 大
                    )
                )
            )
        )
        val (out, _) = AxmlBoolean.setExtractNativeLibsTrue(src)

        val order = attrOrder(out)
        val iName = order.indexOf("name")
        val iExtract = order.indexOf("extractNativeLibs")
        val iCleartext = order.indexOf("usesCleartextTraffic")

        assertTrue("三个属性都该在: $order", iName >= 0 && iExtract >= 0 && iCleartext >= 0)
        assertTrue(
            "extractNativeLibs(0x010104EA) 必须排在 name(0x01010003) 之后，实际顺序 $order",
            iExtract > iName,
        )
        assertTrue(
            "extractNativeLibs(0x010104EA) 必须排在 usesCleartextTraffic(0x010104EC) 之前，" +
                "实际顺序 $order",
            iExtract < iCleartext,
        )
    }

    /** 布尔属性的 rawValue 必须是 NO_INDEX（不是字符串池索引）。 */
    @Test
    fun `布尔属性的 rawValue 是 NO_INDEX`() {
        val src = axml(
            manifest(listOf(app("name" to "com.foo.App", "extractNativeLibs" to false)))
        )
        val (out, _) = AxmlBoolean.setExtractNativeLibsTrue(src)

        val (raw, type) = boolAttrRawAndType(out, "extractNativeLibs")
        assertEquals("布尔属性的 rawValue 必须是 NO_INDEX(0xFFFFFFFF)", -1, raw)
        assertEquals("dataType 必须是 TYPE_INT_BOOLEAN(0x12)", 0x12, type)
    }

    /** 和 `android:name` 改写组合使用时互不破坏。 */
    @Test
    fun `与 setAppName 组合使用互不破坏`() {
        val src = axml(
            manifest(
                listOf(
                    app(
                        "name" to "com.foo.App",
                        "extractNativeLibs" to false,
                    )
                )
            )
        )
        val step1 = AxmlEditor.setAppName(src, "com.nc.uncheck.SigFixApp")
        val (step2, _) = AxmlBoolean.setExtractNativeLibsTrue(step1)

        assertEquals(
            "setAppName 的结果被破坏了",
            "com.nc.uncheck.SigFixApp",
            AxmlEditor.getAppName(step2),
        )
        assertEquals(
            "extractNativeLibs 没生效",
            true,
            AxmlBoolean.getExtractNativeLibs(step2),
        )
    }

    /** 属性类型不是布尔时明确拒绝，不做静默猜测。 */
    @Test
    fun `属性是字符串类型时拒绝改写`() {
        val src = axml(
            manifest(listOf(app("name" to "com.foo.App", "extractNativeLibs" to "weird")))
        )
        val err = runCatching { AxmlBoolean.setExtractNativeLibsTrue(src) }.exceptionOrNull()
        assertTrue(
            "类型不对时应抛异常而不是乱改，实际: $err",
            err is AxmlBoolean.AxmlBooleanException || err is AxmlEditor.AxmlException,
        )
    }

    // ── 辅助：从产物里反读属性 ────────────────────────────────

    /** 读出 `<application>` 上的属性名顺序。 */
    private fun attrOrder(data: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val strings = readPool(buf, data)
        var pos = 8
        while (pos + 8 <= data.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val hsize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            if (type == 0x0102) {
                val nameIdx = buf.getInt(pos + hsize + 4)
                if (strings.getOrNull(nameIdx) == "application") {
                    val attrStart = buf.getShort(pos + hsize + 8).toInt() and 0xFFFF
                    val attrCount = buf.getShort(pos + hsize + 12).toInt() and 0xFFFF
                    val base = pos + hsize + attrStart
                    return (0 until attrCount).map { i ->
                        strings.getOrNull(buf.getInt(base + i * 20 + 4)) ?: ""
                    }
                }
            }
            pos += size
        }
        return emptyList()
    }

    /** 读某个布尔属性的 (rawValue, dataType)。 */
    private fun boolAttrRawAndType(data: ByteArray, name: String): Pair<Int, Int> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val strings = readPool(buf, data)
        var pos = 8
        while (pos + 8 <= data.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val hsize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            if (type == 0x0102) {
                val elName = strings.getOrNull(buf.getInt(pos + hsize + 4))
                if (elName == "application") {
                    val attrStart = buf.getShort(pos + hsize + 8).toInt() and 0xFFFF
                    val attrCount = buf.getShort(pos + hsize + 12).toInt() and 0xFFFF
                    val base = pos + hsize + attrStart
                    for (i in 0 until attrCount) {
                        val p = base + i * 20
                        val nm = strings.getOrNull(buf.getInt(p + 4))
                        if (nm == name) {
                            return buf.getInt(p + 8) to (buf.get(p + 15).toInt() and 0xFF)
                        }
                    }
                }
            }
            pos += size
        }
        throw AssertionError("产物里找不到属性 $name")
    }

    private fun readPool(buf: ByteBuffer, data: ByteArray): List<String> {
        var pos = 8
        while (pos + 8 <= data.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) return emptyList()
            if (type == 0x0001) {
                val count = buf.getInt(pos + 8)
                val flags = buf.getInt(pos + 16)
                val stringsStart = buf.getInt(pos + 20)
                val utf8 = (flags and 0x100) != 0
                val base = pos + stringsStart
                return (0 until count).map { i ->
                    val p = base + buf.getInt(pos + 28 + i * 4)
                    if (utf8) {
                        var q = p
                        val b0 = data[q].toInt() and 0xFF
                        q += if (b0 and 0x80 != 0) 2 else 1
                        val b1 = data[q].toInt() and 0xFF
                        val len: Int
                        if (b1 and 0x80 != 0) {
                            len = ((b1 and 0x7F) shl 8) or (data[q + 1].toInt() and 0xFF)
                            q += 2
                        } else {
                            len = b1; q += 1
                        }
                        String(data, q, len, Charsets.UTF_8)
                    } else {
                        var q = p
                        val n = ((data[q].toInt() and 0xFF) or ((data[q + 1].toInt() and 0xFF) shl 8))
                        q += 2
                        String(data, q, n * 2, Charsets.UTF_16LE)
                    }
                }
            }
            pos += size
        }
        return emptyList()
    }
}

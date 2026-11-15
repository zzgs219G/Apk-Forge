package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.dex.DexOpcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 池注入器的验证。
 *
 * 目标场景：注入一个**外部** payload 类的方法调用——这是本工具的核心用法，
 * 而增量写入（[DexPatcher]）对它无能为力（`method_idx` 必须来自宿主池）。
 *
 * 验证分四层：
 * 1. 产物能被 dexlib2 解析（结构合法）
 * 2. 新增的 method_idx 指向的正是我们要调用的方法
 * 3. 所有既有条目仍在（类集合不变、字符串仍有序）
 * 4. 速度显著快于 DexPool
 */
class PoolInjectorTest {

    private fun locateReleaseApk(): File? {
        val p = System.getProperty("apkforge.releaseApk") ?: System.getenv("APKFORGE_RELEASE_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return File(System.getProperty("user.home"), "sample-release.apk").takeIf { it.isFile }
    }

    private fun dexBytes(apk: File, name: String): ByteArray =
        ZipFile(apk).use { z -> z.getInputStream(z.getEntry(name)).use { it.readBytes() } }

    /** 把产物写盘并用 dexdump 风格的检查做最基础的结构验证。 */
    private fun assertParseable(bytes: ByteArray) {
        val df = runCatching {
            DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
        }.getOrNull()
        assertNotNull("产物 dex 结构非法（dexlib2 读不出来）", df)
    }

    /**
     * 核心：往真实 release dex 里加入一个外部方法引用。
     *
     * 用一个**确定不存在**的类名，模拟 payload 类不在宿主池里的情况。
     */
    @Test
    fun `加入外部方法引用后产物可解析`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val dex = dexBytes(apk!!, "classes.dex")

        val before = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(dex), dex.inputStream()
        )
        val beforeClasses = before.classes.map { it.type }.toSet()

        val r = PoolInjector.addMethodRef(
            dex,
            PoolInjector.MethodRefRequest(
                owner = "Lcom/apkforge/demo/DemoDialog;",
                name = "inject",
                paramTypes = listOf("Landroid/app/Activity;"),
                returnType = "V",
            )
        )
        assertTrue("池注入应成功，实际：$r", r is PoolInjector.Result.Ok)
        val ok = r as PoolInjector.Result.Ok

        assertParseable(ok.dex)

        val after = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(ok.dex), ok.dex.inputStream()
        )
        assertEquals("类集合不应变化（只加引用，不加类定义）", beforeClasses, after.classes.map { it.type }.toSet())

        // 新增的 method_idx 必须指向我们想要的方法
        val ref = after.methodSection[ok.methodIdx]
        assertEquals("持有类不对", "Lcom/apkforge/demo/DemoDialog;", ref.definingClass)
        assertEquals("方法名不对", "inject", ref.name)
        assertEquals("返回类型不对", "V", ref.returnType)
        assertEquals(
            "参数类型不对",
            listOf("Landroid/app/Activity;"),
            ref.parameterTypes.map { it.toString() },
        )

        println("POOL ${ok.note}")
        println("POOL methodIdx=${ok.methodIdx} 总方法数=${after.methodSection.size}")
    }

    /**
     * 关键不变量：`string_ids` 必须严格有序，否则 ART 报
     * `Out-of-order string_ids` 直接拒收。
     *
     * 用 Kotlin 的 `String.compareTo`（按 UTF-16 码元）复刻 dex 的排序规则。
     */
    @Test
    fun `产物字符串池仍严格有序`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val dex = dexBytes(apk!!, "classes.dex")

        val r = PoolInjector.addMethodRef(
            dex,
            PoolInjector.MethodRefRequest(
                owner = "Lzzz/apkforge/Probe;",
                name = "zzzProbe",
                paramTypes = emptyList(),
                returnType = "V",
            )
        )
        assumeTrue("池注入失败：${(r as? PoolInjector.Result.Failed)?.reason}", r is PoolInjector.Result.Ok)
        val out = (r as PoolInjector.Result.Ok).dex

        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(out), out.inputStream())
        val strings = df.stringSection.map { it.toString() }

        var bad = 0
        var firstBad: String? = null
        for (i in 1 until strings.size) {
            if (strings[i] < strings[i - 1]) {
                bad++
                if (firstBad == null) firstBad = "[$i] '${strings[i - 1]}' > '${strings[i]}'"
            }
        }
        assertEquals("字符串池乱序 $bad 处，首个：$firstBad", 0, bad)
        println("POOL 字符串 ${strings.size} 个，严格有序")
    }

    /**
     * 方法池有序性。
     *
     * 【注意排序键】dex 要求 `method_ids` 按 `(class_idx, name_idx, proto_idx)` 升序，
     * **不是按签名字典序**。所以不能拿「类名+方法名+签名」拼串比较——
     * 实测那样会得到 4725 处「乱序」，但产物其实是合法的。
     *
     * 正确做法：直接读原始字节里的三个下标字段自己比。
     */
    @Test
    fun `产物方法池仍严格有序`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val dex = dexBytes(apk!!, "classes.dex")

        val r = PoolInjector.addMethodRef(
            dex,
            PoolInjector.MethodRefRequest(
                owner = "Lzzz/apkforge/Probe;",
                name = "zzzProbe",
                paramTypes = emptyList(),
                returnType = "V",
            )
        )
        assumeTrue("池注入失败", r is PoolInjector.Result.Ok)
        val out = (r as PoolInjector.Result.Ok).dex

        // 直接从字节读 method_ids，按 (class_idx, name_idx, proto_idx) 比较
        val buf = DexBuffer(out)
        val size = buf.u32AsInt(DexFormat.H_METHOD_IDS_SIZE, "methods")
        val off = buf.u32AsInt(DexFormat.H_METHOD_IDS_OFF, "methods_off")
        var bad = 0
        var firstBad: String? = null
        for (i in 1 until size) {
            val a = off + (i - 1) * 8
            val b = off + i * 8
            val ca = buf.u16(a); val na = buf.u32AsInt(a + 4, "n"); val pa = buf.u16(a + 2)
            val cb = buf.u16(b); val nb = buf.u32AsInt(b + 4, "n"); val pb = buf.u16(b + 2)
            // 按 (class_idx, name_idx, proto_idx) 字典序比较
            val cmp = when {
                cb != ca -> cb - ca
                nb != na -> nb - na
                else -> pb - pa
            }
            if (cmp < 0) {
                bad++
                if (firstBad == null) {
                    firstBad = "[$i] (c=$ca,n=$na,p=$pa) > (c=$cb,n=$nb,p=$pb)"
                }
            }
        }
        assertEquals("方法池乱序 $bad 处，首个：$firstBad", 0, bad)
        println("POOL 方法 $size 个，按 (class,name,proto) 严格有序")
    }

    /**
     * `map_list` 必须与实际段一致。
     *
     * 【为什么这条重要】`map_list` 是 ART 用来定位各段的索引。池注入把 6 个索引段
     * 搬到了文件末尾、还新增了条目，漏改任何一项都会让 ART 拒收——
     * 而**构建期完全不报错**，只在装机时炸。所以必须对着字节逐项核对。
     */
    @Test
    fun `产物 map_list 与实际段一致`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val dex = dexBytes(apk!!, "classes.dex")

        val r = PoolInjector.addMethodRef(
            dex,
            PoolInjector.MethodRefRequest(
                owner = "Lcom/apkforge/demo/DemoDialog;",
                name = "inject",
                paramTypes = listOf("Landroid/app/Activity;"),
                returnType = "V",
            )
        )
        assumeTrue("池注入失败：${(r as? PoolInjector.Result.Failed)?.reason}", r is PoolInjector.Result.Ok)
        val out = (r as PoolInjector.Result.Ok).dex

        val buf = DexBuffer(out)
        val mapOff = buf.u32AsInt(DexFormat.H_MAP_OFF, "map_off")
        val mapCount = buf.u32AsInt(mapOff, "map count")

        val expected = mapOf(
            DexFormat.TYPE_HEADER_ITEM to 1,
            DexFormat.TYPE_STRING_ID_ITEM to buf.u32AsInt(DexFormat.H_STRING_IDS_SIZE, "s"),
            DexFormat.TYPE_TYPE_ID_ITEM to buf.u32AsInt(DexFormat.H_TYPE_IDS_SIZE, "t"),
            DexFormat.TYPE_PROTO_ID_ITEM to buf.u32AsInt(DexFormat.H_PROTO_IDS_SIZE, "p"),
            DexFormat.TYPE_FIELD_ID_ITEM to buf.u32AsInt(DexFormat.H_FIELD_IDS_SIZE, "f"),
            DexFormat.TYPE_METHOD_ID_ITEM to buf.u32AsInt(DexFormat.H_METHOD_IDS_SIZE, "m"),
            DexFormat.TYPE_CLASS_DEF_ITEM to buf.u32AsInt(DexFormat.H_CLASS_DEFS_SIZE, "c"),
        )
        val expectedOffsets = mapOf(
            DexFormat.TYPE_STRING_ID_ITEM to buf.u32AsInt(DexFormat.H_STRING_IDS_OFF, "s"),
            DexFormat.TYPE_TYPE_ID_ITEM to buf.u32AsInt(DexFormat.H_TYPE_IDS_OFF, "t"),
            DexFormat.TYPE_PROTO_ID_ITEM to buf.u32AsInt(DexFormat.H_PROTO_IDS_OFF, "p"),
            DexFormat.TYPE_FIELD_ID_ITEM to buf.u32AsInt(DexFormat.H_FIELD_IDS_OFF, "f"),
            DexFormat.TYPE_METHOD_ID_ITEM to buf.u32AsInt(DexFormat.H_METHOD_IDS_OFF, "m"),
            DexFormat.TYPE_CLASS_DEF_ITEM to buf.u32AsInt(DexFormat.H_CLASS_DEFS_OFF, "c"),
        )

        var prevType = -1
        val seen = mutableSetOf<Int>()
        for (i in 0 until mapCount) {
            val o = mapOff + 4 + i * DexFormat.MAP_ITEM_WIDTH
            val type = buf.u16(o)
            val size = buf.u32AsInt(o + 4, "size")
            val off = buf.u32AsInt(o + 8, "off")
            assertTrue("map 必须按 type 升序，第 $i 项 type=$type < $prevType", type > prevType)
            prevType = type
            seen += type

            expected[type]?.let { exp ->
                assertEquals("type=0x${type.toString(16)} 的 size 不符", exp, size)
            }
            expectedOffsets[type]?.let { exp ->
                assertEquals("type=0x${type.toString(16)} 的 offset 不符", exp, off)
            }
            assertTrue("type=0x${type.toString(16)} 的 offset 越界", off in 0 until out.size)
        }
        // map_list 自己必须在表里
        assertTrue("map 缺少 TYPE_MAP_LIST", DexFormat.TYPE_MAP_LIST in seen)
        expected.keys.forEach { t ->
            assertTrue("map 缺少 type=0x${t.toString(16)}", t in seen)
        }
        println("POOL map_list $mapCount 项，全部与实际段一致")
    }

    /** 速度对比：池注入 vs DexPool 全量。 */
    @Test
    fun `池注入应显著快于 DexPool 全量重写`() {
        val apk = locateReleaseApk()
        assumeTrue("没有 release 样本", apk != null)
        val dex = dexBytes(apk!!, "classes.dex")
        assumeTrue("dex 太小", dex.size > 4 * 1024 * 1024)

        val t0 = System.nanoTime()
        val r = PoolInjector.addMethodRef(
            dex,
            PoolInjector.MethodRefRequest(
                owner = "Lcom/apkforge/demo/DemoDialog;",
                name = "inject",
                paramTypes = listOf("Landroid/app/Activity;"),
                returnType = "V",
            )
        )
        val poolMs = (System.nanoTime() - t0) / 1_000_000
        assumeTrue("池注入失败", r is PoolInjector.Result.Ok)

        // 对照：DexPool 全量（只是读写一遍，不改任何东西）
        val t1 = System.nanoTime()
        val df = DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(dex), dex.inputStream())
        val store = com.android.tools.smali.dexlib2.writer.io.MemoryDataStore(dex.size * 5 / 4)
        com.android.tools.smali.dexlib2.writer.pool.DexPool.writeTo(store, df)
        val poolFullMs = (System.nanoTime() - t1) / 1_000_000

        println("POOL 池注入=${poolMs}ms  DexPool全量=${poolFullMs}ms  dex=${dex.size / 1048576}MB")
        assertTrue(
            "池注入应显著更快：池注入=${poolMs}ms 全量=${poolFullMs}ms",
            poolMs * 5 < poolFullMs,
        )
    }
}

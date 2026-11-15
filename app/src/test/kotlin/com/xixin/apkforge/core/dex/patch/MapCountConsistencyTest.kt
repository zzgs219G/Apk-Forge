package com.xixin.apkforge.core.dex.patch

import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SmaliCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 用真实的 apk 跑一遍注入，产出可被 jadx / ART 接受的 dex。
 *
 * 【为什么需要这条】之前的结构自检全绿，但产物在真机上闪退。
 * 原因是 `map_list` 里 `code_item` / `class_data_item` 的计数比实际多 1
 * （`DexPatcher` 把「替换引用」误当成「新增条目」而 +1）。
 *
 * 这类错误 dexlib2 读不出来、自己的结构断言也发现不了，只有 ART 的校验器
 * 或 jadx 这类严格解析器才会报。所以这里加一条**直接从字节反推计数**的断言。
 */
class MapCountConsistencyTest {

    private fun locateApk(): File = listOf(
        File("build/outputs/apk/debug/app-debug.apk"),
        File("../app/build/outputs/apk/debug/app-debug.apk"),
    ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")

    /** 从字节反推：所有被 class_data 引用的唯一 code_off 与 class_data_off。 */
    private fun actualCounts(d: ByteArray): Pair<Int, Int> {
        val buf = DexBuffer(d)
        val cdOff = buf.u32AsInt(DexFormat.H_CLASS_DEFS_OFF, "o")
        val cdSize = buf.u32AsInt(DexFormat.H_CLASS_DEFS_SIZE, "s")
        val codeOffs = HashSet<Int>()
        val classDataOffs = HashSet<Int>()
        repeat(cdSize) { i ->
            val item = cdOff + i * 32
            val cd = buf.u32AsInt(item + DexFormat.CD_CLASS_DATA_OFF, "cdo")
            if (cd == 0) return@repeat
            classDataOffs += cd
            var p = cd
            fun rd(): Int {
                val (v, n) = Leb128.readUleb128(d, p)
                p += n
                return v.toInt()
            }
            val sf = rd(); val inf = rd(); val dm = rd(); val vm = rd()
            repeat(sf + inf) { rd(); rd() }
            repeat(dm + vm) {
                rd(); rd()
                val co = rd()
                if (co != 0) codeOffs += co
            }
        }
        return codeOffs.size to classDataOffs.size
    }

    private fun mapCount(d: ByteArray, type: Int): Int {
        val buf = DexBuffer(d)
        val mapOff = buf.u32AsInt(DexFormat.H_MAP_OFF, "map")
        val cnt = buf.u32AsInt(mapOff, "cnt")
        repeat(cnt) { i ->
            val o = mapOff + 4 + i * DexFormat.MAP_ITEM_WIDTH
            if (buf.u16(o) == type) return buf.u32AsInt(o + 4, "size")
        }
        return -1
    }

    /**
     * 注入后 map 的 code_item / class_data_item 计数必须与实际一致。
     *
     * 这是 ART 的硬性校验：`map_list` 声称的数量与实际不符就拒收整个 dex，
     * 表现是装上后点开就闪退。
     */
    @Test
    fun `注入后 map 计数与实际条目一致`() {
        val apk = locateApk()
        val manifest = com.xixin.apkforge.core.apk.ApkRepackager
            .readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) }
        val launcher = manifest?.primaryLauncher ?: run {
            org.junit.Assume.assumeTrue("没有 LAUNCHER", false); return
        }
        val launcherType = "L${launcher.replace('.', '/')};"

        // 找一个包含 LAUNCHER 的 dex
        var dex: ByteArray? = null
        for (n in com.xixin.apkforge.core.apk.ApkRepackager.listDexNames(apk)) {
            val b = com.xixin.apkforge.support.TestIo.readEntry(apk, n) ?: continue
            if (DexCallInjector.containsClass(b, launcherType)) { dex = b; break }
        }
        org.junit.Assume.assumeTrue("找不到含 LAUNCHER 的 dex", dex != null)

        val spec = DexCallInjector.Spec(
            launcherType, "onCreate", "(Landroid/os/Bundle;)V",
            SmaliCallParser.parseAll(
                "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
            ).first,
        )
        val r = FastInjector.tryInject(dex!!, spec)
        assertTrue("注入应成功，实际 $r", r is FastInjector.Result.Injected)
        val out = (r as FastInjector.Result.Injected).dex

        val (codeCount, classDataCount) = actualCounts(out)
        val mapCode = mapCount(out, DexFormat.TYPE_CODE_ITEM)
        val mapClassData = mapCount(out, DexFormat.TYPE_CLASS_DATA_ITEM)

        assertEquals("map 的 code_item 计数与实际不符（ART 会拒收）", codeCount, mapCode)
        assertEquals("map 的 class_data 计数与实际不符（ART 会拒收）", classDataCount, mapClassData)
        println("MAPOK code_item=$mapCode class_data=$mapClassData")
    }
}

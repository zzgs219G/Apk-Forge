package com.xixin.apkforge.core.dex.patch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 钉住 `DebugInfoRemapper` 的往返正确性。
 *
 * ## 防的是什么
 *
 * `debug_info` 是 uleb128 状态机，任何一处操作数宽度读错（尤其 `DBG_ADVANCE_LINE`
 * 是 **sleb128**）都会让下一个 item 的起点偏掉。表现不是「解码报错」，
 * 而是后续某个 `debug_info` 的 `parameters_size` 读成垃圾值，ART 报：
 *
 * ```
 * Bad index for debug_info_item parameter_name: 3486 >= 735
 * ```
 *
 * 那个 `3486` 和 `735` 都跟真实数据无关，极难反推，所以必须在这里拦住。
 */
class DebugInfoRemapperTest {

    private fun targetDex(): ByteArray? {
        val home = System.getProperty("user.home") ?: return null
        val apk = File("$home/fixture-in.apk")
        if (!apk.isFile) return null
        return ZipFile(apk).use { z ->
            z.entries().toList()
                .map { it.name }
                .filter { it.startsWith("classes") && it.endsWith(".dex") }
                .sorted()
                .mapNotNull { n ->
                    val b = z.getInputStream(z.getEntry(n)).use { it.readBytes() }
                    if (containsDebugInfoWithParams(b)) b else null
                }
                .firstOrNull()
        }
    }

    /** 挑一个确实带 parameter_names 的 dex 作为靶子。 */
    private fun containsDebugInfoWithParams(d: ByteArray): Boolean = runCatching {
        val src = DexBuffer(d)
        val mapOff = src.u32AsInt(DexFormat.H_MAP_OFF, "map")
        val count = src.u32AsInt(mapOff, "count")
        for (k in 0 until count) {
            val o = mapOff + 4 + k * DexFormat.MAP_ITEM_WIDTH
            if (src.u16(o) == DexFormat.TYPE_DEBUG_INFO_ITEM) {
                val start = src.u32AsInt(o + 8, "off")
                val (line, n1) = Leb128.readUleb128(d, start)
                check(line >= 0)
                val (psz, _) = Leb128.readUleb128(d, start + n1)
                return psz > 0
            }
        }
        false
    }.getOrDefault(false)

    /** 把一段字节按状态机走一遍，返回是否恰好停在 END_SEQUENCE。 */
    private fun walkToEnd(d: ByteArray, start: Int): Boolean {
        var p = start
        val (_, n1) = Leb128.readUleb128(d, p); p += n1
        val (psz, n2) = Leb128.readUleb128(d, p); p += n2
        repeat(psz.toInt()) {
            val (_, nn) = Leb128.readUleb128(d, p); p += nn
        }
        var guard = 0
        while (p < d.size && guard++ < 1_000_000) {
            val op = d[p].toInt() and 0xFF
            p++
            when (op) {
                0x00 -> return true                    // END_SEQUENCE
                0x01 -> { val (_, n) = Leb128.readUleb128(d, p); p += n }
                0x02 -> { val (_, n) = Leb128.readSleb128(d, p); p += n }
                0x03 -> {
                    val (_, a) = Leb128.readUleb128(d, p); p += a
                    val (_, b) = Leb128.readUleb128(d, p); p += b
                    val (_, c) = Leb128.readUleb128(d, p); p += c
                }
                0x04 -> {
                    val (_, a) = Leb128.readUleb128(d, p); p += a
                    val (_, b) = Leb128.readUleb128(d, p); p += b
                    val (_, c) = Leb128.readUleb128(d, p); p += c
                    val (_, e) = Leb128.readUleb128(d, p); p += e
                }
                0x05, 0x06 -> { val (_, n) = Leb128.readUleb128(d, p); p += n }
                0x09 -> { val (_, n) = Leb128.readUleb128(d, p); p += n }
                in 0x0A..0xFF -> Unit                  // 行号增量，无负载
                else -> return false
            }
        }
        return false
    }

    @Test
    fun `remapped debug_info must be parseable to END_SEQUENCE`() {
        val dex = targetDex() ?: return

        val src = DexBuffer(dex)
        val mapOff = src.u32AsInt(DexFormat.H_MAP_OFF, "map")
        val count = src.u32AsInt(mapOff, "count")
        var dbgOff = -1
        var dbgCount = 0
        for (k in 0 until count) {
            val o = mapOff + 4 + k * DexFormat.MAP_ITEM_WIDTH
            if (src.u16(o) == DexFormat.TYPE_DEBUG_INFO_ITEM) {
                dbgOff = src.u32AsInt(o + 8, "off")
                dbgCount = src.u32AsInt(o + 4, "n")
                break
            }
        }
        if (dbgOff <= 0) return

        // 恒等映射：只为验证「读写往返不破坏结构」，与池重排无关
        val maps = EncodedValueRemapper.Maps(
            stringMap = IntArray(src.u32AsInt(DexFormat.H_STRING_IDS_SIZE, "s")) { it },
            typeMap = IntArray(src.u32AsInt(DexFormat.H_TYPE_IDS_SIZE, "t")) { it },
            fieldMap = IntArray(src.u32AsInt(DexFormat.H_FIELD_IDS_SIZE, "f")) { it },
            methodMap = IntArray(src.u32AsInt(DexFormat.H_METHOD_IDS_SIZE, "m")) { it },
            protoMap = IntArray(src.u32AsInt(DexFormat.H_PROTO_IDS_SIZE, "p")) { it },
        )

        // 逐个 debug_info 重写，重写结果必须仍能走到 END_SEQUENCE
        var p = dbgOff
        var checked = 0
        repeat(dbgCount) { i ->
            val rewritten = DebugInfoRemapper.remap(dex, p, maps)
            assertTrue(
                "第 $i 个 debug_info(@$p) 重写后无法走到 END_SEQUENCE，" +
                    "说明状态机某个操作数宽度读错了（DBG_ADVANCE_LINE 是 sleb128）",
                walkToEnd(rewritten, 0),
            )
            // 推进到原文件里的下一个 debug_info
            p += consumedLength(dex, p)
            checked++
        }
        assertTrue("应该至少校验了一个 debug_info", checked > 0)
    }

    /** 算一个 debug_info 在原文件里占多少字节。 */
    private fun consumedLength(d: ByteArray, start: Int): Int {
        var p = start
        var (_, n) = Leb128.readUleb128(d, p); p += n
        val (psz, n2) = Leb128.readUleb128(d, p); p += n2
        repeat(psz.toInt()) { val (_, x) = Leb128.readUleb128(d, p); p += x }
        var guard = 0
        while (p < d.size && guard++ < 1_000_000) {
            val op = d[p].toInt() and 0xFF
            p++
            when (op) {
                0x00 -> return p - start
                0x01 -> { val (_, x) = Leb128.readUleb128(d, p); p += x }
                0x02 -> { val (_, x) = Leb128.readSleb128(d, p); p += x }
                0x03 -> repeat(3) { val (_, x) = Leb128.readUleb128(d, p); p += x }
                0x04 -> repeat(4) { val (_, x) = Leb128.readUleb128(d, p); p += x }
                0x05, 0x06, 0x09 -> { val (_, x) = Leb128.readUleb128(d, p); p += x }
                in 0x0A..0xFF -> Unit
                else -> return p - start
            }
        }
        return p - start
    }
}

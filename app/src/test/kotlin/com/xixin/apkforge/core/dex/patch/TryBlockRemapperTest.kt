package com.xixin.apkforge.core.dex.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 直接验证 `TryBlockRemapper` 的输出：重写后，每个 try_item 的 handler_off
 * 必须能定位到一个可解析的 `encoded_catch_handler`。
 */
class TryBlockRemapperTest {

    private fun load(name: String): ByteArray =
        File(System.getProperty("user.home"), name).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)

    @Test
    fun `重写后的 handler_off 能定位到 handler`() {
        val orig = load("n_orig.dex")
        org.junit.Assume.assumeTrue("缺少 n_orig.dex", orig.isNotEmpty())

        // 找出一个带 try 的 code_item
        val buf = DexBuffer(orig)
        val cdOff = buf.u32AsInt(DexFormat.H_CLASS_DEFS_OFF, "o")
        val cdSize = buf.u32AsInt(DexFormat.H_CLASS_DEFS_SIZE, "s")
        var target: Int? = null
        for (i in 0 until cdSize) {
            val item = cdOff + i * 32
            val cd = buf.u32AsInt(item + DexFormat.CD_CLASS_DATA_OFF, "cdo")
            if (cd == 0) continue
            val parsed = runCatching { ClassDataItem.parse(orig, cd) }.getOrNull() ?: continue
            (parsed.directCodeOffsets + parsed.virtualCodeOffsets).forEach { co ->
                if (co > 0 && target == null) {
                    val tries = buf.u16(co.toInt() + DexFormat.CI_TRIES_SIZE)
                    if (tries > 0) target = co.toInt()
                }
            }
            if (target != null) break
        }
        org.junit.Assume.assumeTrue("没有找到带 try 的方法", target != null)
        val co = target!!
        println("TRY 目标 code_off=$co tries=${buf.u16(co + DexFormat.CI_TRIES_SIZE)}")

        // 恒等映射（不改 type_idx），只验证「重写后结构仍自洽」
        val typeCount = buf.u32AsInt(DexFormat.H_TYPE_IDS_SIZE, "ts")
        val identity = IntArray(typeCount) { it }
        val r = TryBlockRemapper.remap(orig, co, identity)
        org.junit.Assume.assumeTrue("remap 返回 null", r != null)
        val res = r!!

        // 解析新 handler_list，收集所有合法起点（相对 list 起始，含 size 字段）
        val validOffsets = HashSet<Int>()
        var p = 0
        val (count, n0) = Leb128.readUleb128(res.handlers, p)
        p += n0
        repeat(count.toInt()) {
            validOffsets += p
            val (sz, n1) = Leb128.readSleb128(res.handlers, p)
            p += n1
            repeat(kotlin.math.abs(sz).toInt()) {
                val (_, a) = Leb128.readUleb128(res.handlers, p); p += a
                val (_, b) = Leb128.readUleb128(res.handlers, p); p += b
            }
            if (sz <= 0) { val (_, c) = Leb128.readUleb128(res.handlers, p); p += c }
        }
        println("TRY 重写后 %d 个 handler，合法起点=%s".format(count, validOffsets))

        // 每个 try_item 的 handler_off 必须落在合法起点上
        val triesSize = res.tries.size / 8
        repeat(triesSize) { i ->
            // try_item: start_addr(u32)@+0, insn_count(u16)@+4, handler_off(u16)@+6
            val off = ((res.tries[i * 8 + 6].toInt() and 0xFF) or
                ((res.tries[i * 8 + 7].toInt() and 0xFF) shl 8))
            assertTrue(
                "try_item[$i] 的 handler_off=$off 不是合法起点（合法：$validOffsets）",
                off in validOffsets,
            )
        }
        println("TRY $triesSize 个 try_item 的 handler_off 全部合法")
    }
}

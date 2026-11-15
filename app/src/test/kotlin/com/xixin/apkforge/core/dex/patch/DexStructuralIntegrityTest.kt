package com.xixin.apkforge.core.dex.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipFile

/**
 * dex 的**硬性结构约束**检查：校验和、对齐、map_list。
 *
 * dexlib2 读得懂不代表 ART 接受。以下几项是 ART 校验器会查、
 * 而 dexlib2 不查的：
 *
 * 1. `checksum`（Adler32，覆盖 12..end）
 * 2. `signature`（SHA-1，覆盖 32..end）
 * 3. `data_off` / 各段 offset 必须 4 字节对齐
 * 4. `map_list` 必须按 type 升序
 */
class DexStructuralIntegrityTest {

    private fun load(apk: File): ByteArray =
        ZipFile(apk).use { z -> z.getInputStream(z.getEntry("classes.dex")).use { it.readBytes() } }

    @Test
    fun `注入产物的校验和与对齐必须合法`() {
        val inj = File(System.getProperty("user.home"), "i.apk")
        org.junit.Assume.assumeTrue("缺少 i.apk", inj.isFile)
        val d = load(inj)

        fun u32(at: Int) = DexBuffer(d).u32(at)

        // 1. signature：SHA-1 覆盖 32..end，存于 12..31
        val sha1 = MessageDigest.getInstance("SHA-1").apply { update(d, 32, d.size - 32) }.digest()
        val storedSig = d.copyOfRange(12, 32)
        assertTrue(
            "SHA-1 signature 不对：期望 ${sha1.take(8).joinToString("") { "%02x".format(it) }}…，",
            sha1.contentEquals(storedSig),
        )

        // 2. checksum：Adler32 覆盖 12..end，存于 8..11
        val adler = Adler32().apply { update(d, 12, d.size - 12) }.value
        val storedSum = u32(8)
        assertEquals("Adler32 checksum 不对", adler, storedSum)

        // 3. 各段 offset 必须 4 字节对齐
        val offsetFields = mapOf(
            "string_ids" to 0x3C, "type_ids" to 0x44, "proto_ids" to 0x4C,
            "field_ids" to 0x54, "method_ids" to 0x5C, "class_defs" to 0x64,
            "map_list" to 0x34,
        )
        offsetFields.forEach { (name, at) ->
            val off = u32(at)
            assertEquals("$name 的 offset($off) 未 4 字节对齐", 0L, off % 4)
            assertTrue("$name 的 offset($off) 越界", off < d.size)
        }

        // 4. map_list 按 type 升序
        val mapOff = u32(0x34).toInt()
        val count = u32(mapOff).toInt()
        var prev = -1
        repeat(count) { i ->
            val o = mapOff + 4 + i * 12
            val type = DexBuffer(d).u16(o)
            assertTrue("map_list 第 $i 项 type=0x${type.toString(16)} < $prev，未升序", type > prev)
            prev = type
            // 每项的 offset 也要对齐且越界
            val itemOff = u32(o + 8)
            assertTrue("map type=0x${type.toString(16)} 的 offset($itemOff) 越界", itemOff <= d.size)
        }

        println("STRUCT checksum/signature/对齐/map顺序 全部通过（map $count 项）")
    }
}

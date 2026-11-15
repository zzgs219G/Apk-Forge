package com.xixin.apkforge.core.apk

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 重打包产物的**二进制契约**：版本头、zip 对齐、签名状态。
 *
 * 这三个都是「不报错但产物不可用」的类型——没有这条测试，改坏了的表现是
 * 「用户装上闪退 / 装不上」，而不是构建失败或测试变红。
 */
class RepackArtifactTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "apkforge-$tag").apply { mkdirs() }

    /** 找一个带 onCreate(Bundle) 且方法体存在的类，返回 (dex 名, 类描述符)。 */
    private fun pickActivity(apk: File): Pair<String, String>? {
        for (name in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, name) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            }.getOrNull() ?: continue
            for (cls in df.classes) {
                val m = cls.methods.firstOrNull {
                    it.name == "onCreate" &&
                        it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;") &&
                        it.implementation != null
                } ?: continue
                return name to cls.type
            }
        }
        return null
    }

    /** 只改 dex、不追加 payload 的最小注入。 */
    private fun injectOnce(apk: File, tag: String): Triple<File, String, ByteArray> {
        val (dexName, ownerType) = pickActivity(apk) ?: error("APK 里没有可注入的类")
        val (calls, _) = SmaliCallParser.parseAll(
            "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        )
        val outcome = DexCallInjector.inject(
            TestIo.readEntry(apk, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        )
        assertTrue("注入应成功，实际 $outcome", outcome is DexCallInjector.Outcome.Injected)

        val out = File(workDir(tag), "out.apk").apply { delete() }
        ApkRepackager.repack(apk, out, dexName, (outcome as DexCallInjector.Outcome.Injected).dex, null)
        return Triple(out, dexName, TestIo.readEntry(apk, dexName)!!)
    }

    /**
     * 防「dex 版本头被写回 035」。
     *
     * 同一个坑项目文档里已经点名批评过 apktool 路线
     * （`docs/项目介绍.md`：「dex 版本头被写回 035，导致 Compose 包 ICCE 闪退」）。
     * 根因是 `Opcodes.getDefault()` 的 api=20 ↔ dex 035：用它解析再写回，
     * 版本头就跟着降级。判据只能钉在**字节**上——dexlib2 能读回一个 035 文件，
     * 结构校验全绿，只有版本位能说明问题。
     */
    @Test
    fun `注入不改写 dex 版本头`() {
        val apk = locateApk()
        assumeTrue("APK 内没有可注入的类", pickActivity(apk) != null)
        val (out, dexName, before) = injectOnce(apk, "ver")

        val after = TestIo.readEntry(out, dexName)!!
        assertEquals(
            "dex 版本头被改写了（035/039 错配会让 039-only 指令变成非法 dex）",
            DexOpcodes.dexVersion(before),
            DexOpcodes.dexVersion(after),
        )
        assertNotNull("源 dex 版本应可识别", DexOpcodes.dexVersion(before))
    }

    /**
     * 防「重打包丢掉 extra field 导致条目失去对齐」。
     *
     * `ZipEntry(src.name)` 只搬名字/时间/压缩方式，**extra 会被丢掉**，
     * 而 AGP 把对齐填充正好放在 extra 里。丢掉后所有 STORED 条目前移，
     * `resources.arsc` 失去 4 字节对齐（targetSdk≥30 时资源读不出）、
     * `lib/` 下的 `.so` 失去页对齐（16KB 页设备直接不让装）。
     *
     * 判据是「产物里每个原本对齐的 STORED 条目，现在仍然对齐」——
     * 用源包的对齐要求当基准，不用产物自己的偏移反推（那会自证成功）。
     */
    @Test
    fun `重打包保持 STORED 条目的原有对齐`() {
        val apk = locateApk()
        assumeTrue("APK 内没有可注入的类", pickActivity(apk) != null)
        val (out, _, _) = injectOnce(apk, "align")

        val expected = requiredAlignments(apk)
        assumeTrue("源包里没有需要对齐的 STORED 条目，跳过", expected.any { it.value > 1 })

        val actual = dataOffsets(out)
        val broken = expected.filter { (name, align) ->
            align > 1 && (actual[name]?.rem(align) ?: 0L) != 0L
        }
        assertTrue(
            "重打包后 ${broken.size} 个 STORED 条目失去对齐（前几个：${
                broken.entries.take(5).joinToString { "${it.key} align=${it.value}" }
            }）",
            broken.isEmpty(),
        )
    }

    /** 写完后 Stats 自己就该知道有没有对齐问题，不能等用户拿 zipalign 才发现。 */
    @Test
    fun `Stats 如实报告对齐与外层签名状态`() {
        val apk = locateApk()
        assumeTrue("APK 内没有可注入的类", pickActivity(apk) != null)
        val (dexName, _) = pickActivity(apk)!!

        val (calls, _) = SmaliCallParser.parseAll(
            "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        )
        val (_, ownerType) = pickActivity(apk)!!
        val inj = DexCallInjector.inject(
            TestIo.readEntry(apk, dexName)!!,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        ) as DexCallInjector.Outcome.Injected

        val out = File(workDir("stats"), "out.apk").apply { delete() }
        val stats = ApkRepackager.repack(apk, out, dexName, inj.dex, null)

        assertEquals("修好之后不该再有未对齐条目", 0, stats.misalignedEntries)
        assertEquals(
            "源包带 v2/v3 签名时 Stats 必须如实报告（产物是未签名包）",
            ApkRepackager.hasApkSignatureBlock(apk),
            stats.sourceHadSignature,
        )
    }

    // ────────────────────── 辅助 ──────────────────────

    /**
     * 源包里每个 STORED 条目「要求对齐到几字节」。
     *
     * 复刻 [ApkRepackager] 的判据（偏移命中 16KB / 4KB / 4 里最大的那个），
     * 但这里**直接解中央目录**，与被测代码不共享实现——
     * 共用一份解析就等于「自己给自己判卷」。
     *
     * 必须走中央目录而不是顺 local header 爬：`ZipOutputStream` 写 DEFLATED 条目时
     * 用 data descriptor（local header 的 csize 写 0），顺链走会立刻算错位置。
     */
    private fun requiredAlignments(apk: File): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        zipEntries(apk).filter { it.method == 0 }.forEach { e ->
            out[e.name] = when {
                e.dataOffset % 16384L == 0L -> 16384
                e.dataOffset % 4096L == 0L -> 4096
                e.dataOffset % 4L == 0L -> 4
                else -> 1
            }
        }
        return out
    }

    private fun dataOffsets(apk: File): Map<String, Long> =
        zipEntries(apk).associate { it.name to it.dataOffset }

    private class Entry(val name: String, val method: Int, val dataOffset: Long)

    /** 走 EOCD → 中央目录 → 每个条目的 local header，拿真实数据偏移。 */
    private fun zipEntries(apk: File): List<Entry> {
        val result = mutableListOf<Entry>()
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            val window = minOf(len, 22L + 0xFFFF).toInt()
            val tail = ByteArray(window)
            raf.seek(len - window)
            raf.readFully(tail)
            var eocd = -1
            for (i in window - 22 downTo 0) {
                if (le32(tail, i) == 0x06054b50) { eocd = i; break }
            }
            require(eocd >= 0) { "产物没有 EOCD：$apk" }
            val cdSize = le32(tail, eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = le32(tail, eocd + 16).toLong() and 0xFFFFFFFFL
            val eocdAbs = len - window + eocd
            val prefix = (eocdAbs - cdSize) - cdOffset
            val buf = ByteArray(cdSize.toInt())
            raf.seek(cdOffset + prefix)
            raf.readFully(buf)

            var p = 0
            while (p + 46 <= buf.size && le32(buf, p) == 0x02014b50) {
                val method = le16(buf, p + 10)
                val nameLen = le16(buf, p + 28)
                val extraLen = le16(buf, p + 30)
                val commentLen = le16(buf, p + 32)
                val localOffset = le32(buf, p + 42).toLong() and 0xFFFFFFFFL
                val name = String(buf, p + 46, nameLen, Charsets.UTF_8)

                val saved = raf.filePointer
                raf.seek(localOffset + prefix)
                val lh = ByteArray(30)
                raf.readFully(lh)
                raf.seek(saved)
                require(le32(lh, 0) == 0x04034b50) { "local header 非法：$name" }

                val data = localOffset + prefix + 30 + le16(lh, 26) + le16(lh, 28)
                result += Entry(name, method, data)
                p += 46 + nameLen + extraLen + commentLen
            }
        }
        return result
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

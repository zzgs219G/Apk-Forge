package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ApkSigningBlock
import com.xixin.apkforge.core.apk.ZipCentralDirectory
import com.xixin.apkforge.core.axml.AxmlBoolean
import com.xixin.apkforge.core.axml.AxmlEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 去签名校验**整条流水线**对真实 APK 的验收。
 *
 * ## 这个测试是这套功能的唯一「装得上」证据
 *
 * 单测各自通过不代表组合正确：七个落点缺任何一个都是「能装、能启动、
 * 但去校验不生效」——没有报错。所以这条必须用真实 APK 走完整流程，
 * 然后逐条检查产物里每一个落点。
 *
 * ## 用真实 APK 的理由（`AGENTS.md` §3.1）
 *
 * 真实包有 `resources.arsc` 的 STORED + 4 字节对齐（AGP 保证）、
 * 多 dex、真实按钮表；伪造样本覆盖不到这些形态，而它们正是
 * 「数据复用平移后是否还装得上」的判据。
 */
class UncheckPipelineTest {

    private fun locateApk(): File? {
        System.getProperty("apkforge.testApk")?.let { p ->
            val f = File(p); if (f.isFile) return f
        }
        System.getenv("APKFORGE_TEST_APK")?.let { p ->
            val f = File(p); if (f.isFile) return f
        }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile }
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "uncheck-pipeline-$tag").apply {
            deleteRecursively(); mkdirs()
        }

    /**
     * 造一份载荷。
     *
     * 内容不需要是真的可执行载荷——本测试验的是**装配**，不是载荷行为。
     * 但魔数必须对，因为自检会查（那正是为了防止「拿 HTML 错误页当 dex」）。
     */
    private fun fakePayload(files: Map<String, ByteArray> = emptyMap()): UncheckPayload.Payload {
        fun dex(seed: Int) =
            "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(256) { (seed + it).toByte() }

        fun elf(seed: Int): ByteArray {
            val b = ByteArray(1024) { (seed + it % 64).toByte() }
            b[0] = 0x7F; b[1] = 'E'.code.toByte()
            b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
            return b
        }

        return UncheckPayload.Payload(
            version = "test-1",
            bootDex = files[UncheckPayload.BOOT_DEX] ?: dex(1),
            payloadDex = files[UncheckPayload.PAYLOAD_DEX] ?: dex(2),
            usignArm64 = files[UncheckPayload.USIGN_ARM64] ?: elf(3),
            usignArm32 = files[UncheckPayload.USIGN_ARM32] ?: elf(4),
        )
    }

    // ── 断言 ──────────────────────────────────────────────────

    /**
     * 七个落点全部到位。
     *
     * 这是本文件最重要的一条：少任何一个都不构成可用的去校验包，
     * 而失败形态是静默的（能装、能启动、不生效）。
     */
    @Test
    fun `真实 APK 跑完整流水线后七个落点齐全`() {
        val apk = locateApk() ?: return
        val work = workDir("full")

        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(
            input = apk,
            output = out,
            payload = fakePayload(),
            workDir = work,
        )
        assertTrue("产物应存在", out.isFile)
        assertTrue("产物不该是空文件", out.length() > 1024)

        val layout = ZipCentralDirectory.read(out)
        val names = layout.names

        // 落点 1：新 dex
        assertTrue("缺新 dex ${result.newDex}", names.contains(result.newDex))
        // 落点 2/3：payload.dex 与配置
        assertTrue("缺 payload.dex", names.contains(UncheckPayload.ASSET_PAYLOAD_DEX))
        assertTrue("缺 sigfix.json", names.contains(UncheckPayload.ASSET_CONFIG))
        // 落点 4：origin.apk
        assertTrue("缺 origin.apk", names.contains(UncheckPayload.ASSET_ORIGIN))
        // 落点 5：两个 ABI 的 .so
        assertTrue("缺 arm64 .so", names.contains(UncheckPayload.LIB_ARM64))
        assertTrue("缺 armv7 .so", names.contains(UncheckPayload.LIB_ARM32))

        // 落点 6：Manifest 指向 SigFixApp
        val mf = readEntry(out, UncheckPayload.MANIFEST)!!
        assertEquals(
            "android:name 没指向 SigFixApp —— 载荷永不执行",
            UncheckPayload.SIGFIX_APP,
            AxmlEditor.getAppName(mf),
        )

        // 落点 7：extractNativeLibs = true
        assertEquals(
            "extractNativeLibs 不是 true —— .so 不会被解压，System.load 必失败",
            true,
            AxmlBoolean.getExtractNativeLibs(mf),
        )

        println(
            "PIPELINE: ${result.sourceSize} → ${result.outputSize} B，" +
                "新 dex=${result.newDex}，省 ${result.savedByReuse} B，" +
                "签名块=${result.hadSignature}"
        )
    }

    /** 原包的每个条目都必须还在产物里（不能因为装配而丢东西）。 */
    @Test
    fun `原包条目一条不少`() {
        val apk = locateApk() ?: return
        val work = workDir("entries")
        val out = File(work, "out.apk")

        UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        val before = ZipCentralDirectory.read(apk).names.toSet()
        val after = ZipCentralDirectory.read(out).names.toSet()

        val lost = before - after
        assertTrue("产物丢了原包条目：$lost", lost.isEmpty())
    }

    /** 除新增条目外，产物不该比原包多出别的什么（防止越界写入）。 */
    @Test
    fun `产物只多出预期的新增条目`() {
        val apk = locateApk() ?: return
        val work = workDir("diff")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        val before = ZipCentralDirectory.read(apk).names.toSet()
        val after = ZipCentralDirectory.read(out).names.toSet()

        val expected = setOf(
            result.newDex,
            UncheckPayload.ASSET_PAYLOAD_DEX,
            UncheckPayload.ASSET_CONFIG,
            UncheckPayload.ASSET_ORIGIN,
            UncheckPayload.LIB_ARM64,
            UncheckPayload.LIB_ARM32,
        )
        val added = after - before
        assertEquals(
            "新增条目应恰好是预期的 6 个，实际多出：${added - expected}，缺少：${expected - added}",
            expected,
            added,
        )
    }

    /**
     * 原包带签名块时，origin.apk 必须也带 —— 这是 native 层读证书的唯一来源。
     *
     * 缺了它：产物能装、App 能启动、Java 层也生效，只有 native 层静默缺席。
     * 表现为「某些 App 去校验无效」，没有任何报错。
     */
    @Test
    fun `origin_apk 带着原签名块`() {
        val apk = locateApk() ?: return
        assumeTrue("测试 APK 没有签名块（未签名 debug 包？）", ApkSigningBlock.hasBlock(apk))

        val work = workDir("origin-sig")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)
        assertTrue("结果应报告 hadSignature", result.hadSignature)

        val layout = ZipCentralDirectory.read(out)
        val origin = layout.byName(UncheckPayload.ASSET_ORIGIN)!!
        val extracted = File(work, "origin.apk")
        extracted.writeBytes(ZipCentralDirectory.readRaw(out, origin))

        assertTrue(
            "origin.apk 里没有签名块 —— native 层读不到证书，去校验的 native 部分完全无效",
            ApkSigningBlock.hasBlock(extracted),
        )
        // 签名块内容必须与原包一致
        assertTrue(
            "origin.apk 的签名块与原包不一致",
            ApkSigningBlock.extract(apk)!!.contentEquals(ApkSigningBlock.extract(extracted)),
        )
    }

    /** 产物必须能通过结构和自检（含 arsc 对齐 —— 装机的硬判据）。 */
    @Test
    fun `产物通过结构与对齐自检`() {
        val apk = locateApk() ?: return
        val work = workDir("verify")
        val out = File(work, "out.apk")
        UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        val problems = ZipReuse.verify(out)
        assertTrue("产物体检不通过:\n  ${problems.joinToString("\n  ")}", problems.isEmpty())

        // 交叉验证：标准 ZipFile 也能正常打开（说明 ZIP 结构本身合法）
        val nameCount = ZipFile(out).use { zf -> zf.entries().toList().size }
        assertEquals(
            "标准 ZipFile 与自己的解析器条目数不一致",
            nameCount,
            ZipCentralDirectory.read(out).entries.size,
        )
    }

    /** sigfix.json 的内容必须能被解析出两个字段。 */
    @Test
    fun `sigfix_json 含证书与原始 Application`() {
        val apk = locateApk() ?: return
        val work = workDir("json")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        val json = String(readEntry(out, UncheckPayload.ASSET_CONFIG)!!, Charsets.UTF_8)
        assertTrue("缺 cert 字段: $json", json.contains("\"cert\":\""))
        assertTrue("缺 originalApp 字段: $json", json.contains("\"originalApp\":\""))

        // 证书必须非空且是 Base64（能被解回 DER）
        val certB64 = Regex("""\"cert\"\s*:\s*\"([^\"]*)\"""")
            .find(json)!!.groupValues[1]
        assertTrue("证书不该为空（否则 install() 直接 return，什么都不做）", certB64.isNotEmpty())
        val der = java.util.Base64.getDecoder().decode(certB64)
        assertTrue("证书 DER 太短，不像真实证书: ${der.size} B", der.size > 100)

        // originalApp 应与原包一致（可能为空 = 系统默认）
        val expectApp = result.originalApp ?: ""
        assertTrue(
            "originalApp 字段与结果不符",
            json.contains("\"originalApp\":\"${expectApp}\""),
        )
    }

    /** 数据复用必须生效：体积不能接近翻倍。 */
    @Test
    fun `数据复用让体积不翻倍`() {
        val apk = locateApk() ?: return
        val work = workDir("size")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        assertTrue(
            "产物 ${result.outputSize} B ≥ 源包 ${result.sourceSize} B 的 1.5 倍，复用没生效",
            result.outputSize < result.sourceSize * 1.5,
        )
        assertTrue("savedByReuse 应为正数", result.savedByReuse > 0)
    }

    /** 新 dex 名必须避开已有名字。 */
    @Test
    fun `新 dex 名不占用已有名字`() {
        assertEquals("classes2.dex", UncheckPipeline.nextDexName(listOf("classes.dex")))
        assertEquals(
            "classes4.dex",
            UncheckPipeline.nextDexName(listOf("classes.dex", "classes2.dex", "classes3.dex")),
        )
        // 序号不连续时取最大值 +1
        assertEquals(
            "classes9.dex",
            UncheckPipeline.nextDexName(listOf("classes.dex", "classes2.dex", "classes8.dex")),
        )
    }

    /** extractNativeLibs 的三种形态都要能被流水线正确报告。 */
    @Test
    fun `extractNativeLibs 改写结果被如实报告`() {
        val apk = locateApk() ?: return
        val work = workDir("libs")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        // 产物里必须最终是 true，不管原来是哪种形态
        val mf = readEntry(out, UncheckPayload.MANIFEST)!!
        assertEquals(true, AxmlBoolean.getExtractNativeLibs(mf))
        // 报告的形态必须是三者之一
        assertTrue(
            "结果应如实报告改写形态，实际 ${result.extractNativeLibs}",
            result.extractNativeLibs in setOf(
                AxmlBoolean.Outcome.ALREADY_ON,
                AxmlBoolean.Outcome.FLIPPED,
                AxmlBoolean.Outcome.ADDED,
            ),
        )
        println("extractNativeLibs 形态: ${result.extractNativeLibs}")
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun readEntry(apk: File, name: String): ByteArray? =
        ZipFile(apk).use { zf ->
            val e = zf.getEntry(name) ?: return null
            zf.getInputStream(e).use { it.readBytes() }
        }
}

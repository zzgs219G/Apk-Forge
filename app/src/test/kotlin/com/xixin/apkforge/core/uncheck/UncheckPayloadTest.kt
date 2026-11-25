package com.xixin.apkforge.core.uncheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UncheckPayload` 的纯逻辑：索引解析、sha256、载荷校验。
 *
 * ## 为什么这些必须是纯 JVM 单测
 *
 * 索引解析错的表现是「载荷获取失败」，而用户看到的只有这一句话 ——
 * 到底是网络问题、还是索引格式不对、还是 sha256 填错，全靠这里区分。
 * 靠点 UI 验证的话，每次都要联网、清缓存、重装。
 */
class UncheckPayloadTest {

    private fun validIndex(
        version: String = "abc123",
        boot: String = "a".repeat(64),
        payload: String = "b".repeat(64),
        arm64: String = "c".repeat(64),
        arm32: String = "d".repeat(64),
    ): String = """
        {
          "version": "$version",
          "files": {
            "boot.dex": { "sha256": "$boot", "size": 100 },
            "payload.dex": { "sha256": "$payload", "size": 200 },
            "libusign_arm64.so": { "sha256": "$arm64", "size": 300 },
            "libusign_armv7.so": { "sha256": "$arm32", "size": 400 }
          }
        }
    """.trimIndent()

    private fun dex(size: Int, seed: Int = 0): ByteArray =
        "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(size - 8) { (seed + it).toByte() }

    private fun elf(size: Int, seed: Int = 0): ByteArray {
        val b = ByteArray(size) { (seed + it % 64).toByte() }
        b[0] = 0x7F; b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        return b
    }

    // ── sha256 ────────────────────────────────────────────────

    /**
     * sha256 必须与标准实现一致。
     *
     * 这条不是形式主义：实现里曾经写成 `HEX[b and 0xFF]`（数组只有 16 个元素）
     * → 对任意非空输入都抛 ArrayIndexOutOfBoundsException，
     * 整个流水线在每个 APK 上都崩。而它是纯函数，本该有单测。
     */
    @Test
    fun `sha256 与标准实现一致`() {
        // 已知向量：空串
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UncheckPayload.sha256(ByteArray(0)),
        )
        // 已知向量："abc"
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UncheckPayload.sha256("abc".toByteArray()),
        )
        // 字节值覆盖 0x00~0xFF 全范围（旧实现正是在这里越界）
        val allBytes = ByteArray(256) { it.toByte() }
        val hex = UncheckPayload.sha256(allBytes)
        assertEquals("应是 64 位十六进制", 64, hex.length)
        assertTrue("只能有小写十六进制字符: $hex", hex.all { it in "0123456789abcdef" })
        // 与 JDK 自带的十六进制转换交叉验证
        val expect = java.security.MessageDigest.getInstance("SHA-256")
            .digest(allBytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expect, hex)
    }

    // ── 索引解析 ──────────────────────────────────────────────

    @Test
    fun `解析合法索引`() {
        val idx = UncheckPayload.parseIndex(validIndex())
        assertEquals("abc123", idx.version)
        assertEquals(4, idx.files.size)
        assertEquals(100L, idx.entry(UncheckPayload.BOOT_DEX).size)
        assertEquals("c".repeat(64), idx.entry(UncheckPayload.USIGN_ARM64).sha256)
    }

    /**
     * 缺任何一个文件都必须报错，不能静默继续。
     *
     * 静默跳过的后果：少 `.so` 时得到「Java 层能跑、native 层永远不生效」
     * 的载荷，而这个问题在真机上只表现为「某些包去校验无效」，没有报错。
     */
    @Test
    fun `缺必需文件时明确报错并指出是哪个`() {
        val noSo = """
            {
              "version": "v1",
              "files": {
                "boot.dex": { "sha256": "${"a".repeat(64)}", "size": 1 },
                "payload.dex": { "sha256": "${"b".repeat(64)}", "size": 2 }
              }
            }
        """.trimIndent()
        val err = runCatching { UncheckPayload.parseIndex(noSo) }.exceptionOrNull()
        assertTrue("应抛 PayloadException，实际: $err", err is UncheckPayload.PayloadException)
        val msg = err!!.message ?: ""
        assertTrue("报错应点名缺哪些文件，实际: $msg", msg.contains("libusign"))
    }

    @Test
    fun `缺 version 字段时报错`() {
        val noVersion = """{"files": {}}"""
        val err = runCatching { UncheckPayload.parseIndex(noVersion) }.exceptionOrNull()
        assertTrue(err is UncheckPayload.PayloadException)
        assertTrue(
            "报错应指出缺 version，实际: ${err!!.message}",
            err.message!!.contains("version"),
        )
    }

    /** sha256 长度不对（手填漏字符）时必须早报错，而不是等到下载完才发现「校验失败」。 */
    @Test
    fun `sha256 格式不对时报错`() {
        val short = validIndex(boot = "abc")
        val err = runCatching { UncheckPayload.parseIndex(short) }.exceptionOrNull()
        assertTrue("应抛异常，实际: $err", err is UncheckPayload.PayloadException)
        assertTrue(
            "报错应指出 sha256 格式问题，实际: ${err!!.message}",
            err.message!!.contains("sha256"),
        )
    }

    @Test
    fun `大小写混杂的 sha256 被归一成小写`() {
        val idx = UncheckPayload.parseIndex(validIndex(boot = "A".repeat(64)))
        assertEquals("a".repeat(64), idx.entry(UncheckPayload.BOOT_DEX).sha256)
    }

    // ── 载荷校验 ──────────────────────────────────────────────

    @Test
    fun `四个文件都正确时校验通过`() {
        val boot = dex(100, 1)
        val payload = dex(200, 2)
        val a64 = elf(300, 3)
        val a32 = elf(400, 4)
        val index = UncheckPayload.parseIndex(
            """
            {
              "version": "v1",
              "files": {
                "boot.dex": { "sha256": "${UncheckPayload.sha256(boot)}", "size": ${boot.size} },
                "payload.dex": { "sha256": "${UncheckPayload.sha256(payload)}", "size": ${payload.size} },
                "libusign_arm64.so": { "sha256": "${UncheckPayload.sha256(a64)}", "size": ${a64.size} },
                "libusign_armv7.so": { "sha256": "${UncheckPayload.sha256(a32)}", "size": ${a32.size} }
              }
            }
            """.trimIndent()
        )
        val p = UncheckPayload.Payload("v1", boot, payload, a64, a32)
        val problems = UncheckPayload.validate(index, p)
        assertTrue("合法载荷不该有问题: $problems", problems.isEmpty())
    }

    /** sha256 不符必须拦住 —— 载荷会被注入到别人的 App 里执行。 */
    @Test
    fun `sha256 不符时校验失败`() {
        val index = UncheckPayload.parseIndex(validIndex())
        val p = UncheckPayload.Payload("abc123", dex(100), dex(200), elf(300), elf(400))
        val problems = UncheckPayload.validate(index, p)
        assertTrue("应报 sha256 不符", problems.any { it.contains("sha256") })
    }

    @Test
    fun `大小不符时校验失败`() {
        val boot = dex(100)
        val index = UncheckPayload.parseIndex(validIndex(boot = UncheckPayload.sha256(boot)))
        // 有意给一个不同大小的
        val p = UncheckPayload.Payload("abc123", dex(999), dex(200), elf(300), elf(400))
        val problems = UncheckPayload.validate(index, p)
        assertTrue("应报大小不符", problems.any { it.contains("大小") })
    }

    /**
     * 魔数不对必须拦住：下载到 HTML 错误页时（源仓库转私有会回 200 + HTML），
     * urlopen 不报错，写下去就是个假 dex，要到运行时才炸。
     */
    @Test
    fun `dex 魔数不对时校验失败`() {
        val fakeDex = "这不是 dex".toByteArray()
        val index = UncheckPayload.parseIndex(
            validIndex(boot = UncheckPayload.sha256(fakeDex))
        )
        val p = UncheckPayload.Payload(
            "abc123", fakeDex, dex(200),
            // 其他几个也要对，否则会先报 sha256
            ByteArray(0).let { elf(4) }, elf(4),
        )
        val problems = UncheckPayload.validate(index, p)
        assertTrue(
            "应报魔数问题，实际: $problems",
            problems.any { it.contains("魔数") || it.contains("sha256") },
        )
    }

    @Test
    fun `so 魔数不对时校验失败`() {
        val fakeSo = "not elf".toByteArray()
        val boot = dex(100)
        val payload = dex(200)
        val a64 = elf(300)
        val index = UncheckPayload.parseIndex(
            """
            {
              "version": "v1",
              "files": {
                "boot.dex": { "sha256": "${UncheckPayload.sha256(boot)}", "size": ${boot.size} },
                "payload.dex": { "sha256": "${UncheckPayload.sha256(payload)}", "size": ${payload.size} },
                "libusign_arm64.so": { "sha256": "${UncheckPayload.sha256(fakeSo)}", "size": ${fakeSo.size} },
                "libusign_armv7.so": { "sha256": "${UncheckPayload.sha256(a64)}", "size": ${a64.size} }
              }
            }
            """.trimIndent()
        )
        val p = UncheckPayload.Payload("v1", boot, payload, fakeSo, a64)
        val problems = UncheckPayload.validate(index, p)
        assertTrue("应报 ELF 魔数问题: $problems", problems.any { it.contains("魔数") })
    }

    @Test
    fun `版本不一致时校验失败`() {
        val index = UncheckPayload.parseIndex(validIndex(version = "want"))
        val p = UncheckPayload.Payload("got", dex(100), dex(200), elf(300), elf(400))
        val problems = UncheckPayload.validate(index, p)
        assertTrue("应报版本不一致", problems.any { it.contains("版本") })
    }

    /** 载荷的落点常量必须与载荷 Java 源码里的常量逐字一致。 */
    @Test
    fun `落点常量与载荷契约一致`() {
        // 对应 SigFix.java 里的 PAYLOAD_ASSET / CONFIG_ASSET / ORIGIN_ASSET / NATIVE_LIB
        assertEquals("assets/nc_sigfix/payload.dex", UncheckPayload.ASSET_PAYLOAD_DEX)
        assertEquals("assets/nc_sigfix/sigfix.json", UncheckPayload.ASSET_CONFIG)
        assertEquals("assets/nc_sigfix/origin.apk", UncheckPayload.ASSET_ORIGIN)
        assertEquals("lib/arm64-v8a/libusign.so", UncheckPayload.LIB_ARM64)
        assertEquals("lib/armeabi-v7a/libusign.so", UncheckPayload.LIB_ARM32)
        // SigFixApp 的类名必须与 boot.dex 里的一致
        assertEquals("com.nc.uncheck.SigFixApp", UncheckPayload.SIGFIX_APP)
    }
}

package com.xixin.apkforge.core.uncheck

import com.xixin.apkforge.core.apk.ApkSigningBlock
import com.xixin.apkforge.core.apk.ZipCentralDirectory
import com.xixin.apkforge.core.axml.AxmlBoolean
import com.xixin.apkforge.core.axml.AxmlEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * 自检失败时**不能**留下产物。
     *
     * ## 防的是什么
     *
     * 产物是在第 ⑧ 步（数据复用）写出来的，第 ⑨ 步自检如果发现结构不对，
     * 必须把它删掉。否则用户会在 Download 里看到一个**能装但去校验不生效**的包，
     * 而比这更糟：他会以为工具"成功了"。
     *
     * ## ⚠️ 第一版这条测试是假的，值得记下来
     *
     * 原来用「输入不是 APK」当夹具，那种情况在读包阶段就抛异常，output 还没被创建，
     * 于是「删不删」都测不出差别 —— 把生产代码里的清理改成 `if (false)`，
     * 测试照样绿（实测过）。这违反了 `AGENTS.md` §2.2：一个测试若删掉被它测的
     * 生产逻辑仍然通过，它就什么都没测。
     *
     * 现在改成让自检**真的失败**：传一个 hadSignature 期望与实际不符的场景——
     * 用无签名块的包，但让自检认为原包有签名块，它会因为
     * 「origin.apk 里没有签名块」而抛异常，此时 output 已经完整写出来了。
     */
    @Test
    fun `自检失败时删掉已写出的产物`() {
        val apk = locateApk() ?: return
        // 这条只在「原包确实没有签名块」时有意义：那种包自检的第 8 步会被跳过，
        // 我们反过来用一个保证会失败的构造——见下方 selfCheck 的判据。
        assumeTrue(
            "这条测的是「产物已写出但自检不过」的清理；需要真实 APK",
            apk.isFile,
        )

        val work = workDir("selfcheck-fail")
        val out = File(work, "out.apk")

        // 用一个坏掉的载荷让流水线走到最后一步再失败：
        // boot.dex 不是合法 dex → 自检的「新 dex 必须是合法 dex」会拦住，
        // 而此时 output 已经被 ZipReuse 完整写出。
        val bad = UncheckPayload.Payload(
            version = "test-bad",
            bootDex = "不是 dex".toByteArray(),
            payloadDex = "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1),
            usignArm64 = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
            usignArm32 = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
        )

        val err = runCatching {
            UncheckPipeline.run(apk, out, bad, workDir = work)
        }.exceptionOrNull()

        assertTrue("自检应失败（bootDex 不是合法 dex），实际: $err", err != null)
        assertTrue(
            "自检失败后必须删掉已写出的产物，否则用户会拿到一个「能装但无效」的包：$out",
            !out.isFile,
        )
    }

    /** 成功时必须保留产物（别把清理做成"总是删"）。 */
    @Test
    fun `成功时产物保留`() {
        val apk = locateApk() ?: return
        val work = workDir("keep")
        val out = File(work, "out.apk")

        UncheckPipeline.run(apk, out, fakePayload(), workDir = work)
        assertTrue("成功时产物必须存在", out.isFile)
        assertTrue("产物不该是空文件", out.length() > 1024)
    }

    /**
     * `sigfix.json` 必须含 `hookLevel`，且落在 [1,3]。
     *
     * ## 防的是什么
     *
     * 载荷侧读的是 `o.optInt("hookLevel", 6)`。这个字段**缺失时不报错**，
     * 只会拿到默认值 —— 而用户以为自己在用低档（为了不破坏目标 App 的网络），
     * 实际跑的是全量 hook。这类「配置没写进去」的问题在真机上只表现为
     * 「某些 App 网络坏掉」，与本模块完全联想不到一起。
     *
     * 档位数值是跨语言协议（对应 native 的 `NC_HOOK_LEVEL_*`），改一个数
     * 就是协议变更，所以这里把 [1,3] 与默认值都钉住。
     */
    @Test
    fun `sigfix_json 含合法 hookLevel`() {
        val apk = locateApk() ?: return
        val work = workDir("hooklevel")
        val out = File(work, "out.apk")

        // 默认档位
        UncheckPipeline.run(apk, out, fakePayload(), workDir = work)
        val json = String(readEntry(out, UncheckPayload.ASSET_CONFIG)!!, Charsets.UTF_8)
        val lv = Regex(""""hookLevel"\s*:\s*(\d+)""")
            .find(json)?.groupValues?.get(1)?.toIntOrNull()
        assertNotNull("sigfix.json 必须有 hookLevel 字段（缺了会静默用默认值）: $json", lv)
        assertEquals(
            "默认档位必须是 FULL(3)，与历史行为一致",
            UncheckPipeline.HookLevel.FULL,
            lv,
        )
        assertEquals("FULL 的协议值必须是 3", 3, UncheckPipeline.HookLevel.FULL)
        assertEquals("CORE 的协议值必须是 1", 1, UncheckPipeline.HookLevel.CORE)
        assertEquals("AT 的协议值必须是 2", 2, UncheckPipeline.HookLevel.AT)
    }

    /** 越界的 hookLevel 必须被钳位，不能原样写进配置。 */
    @Test
    fun `hookLevel 越界被钳位`() {
        assertTrue(
            "小于 1 应钳到 CORE",
            String(
                UncheckPipeline.buildConfigJson("c", "a", hookLevel = 0),
                Charsets.UTF_8,
            ).contains("\"hookLevel\":1"),
        )
        assertTrue(
            "大于 3 应钳到 FULL",
            String(
                UncheckPipeline.buildConfigJson("c", "a", hookLevel = 99),
                Charsets.UTF_8,
            ).contains("\"hookLevel\":3"),
        )
        assertTrue(
            "合法值原样保留",
            String(
                UncheckPipeline.buildConfigJson("c", "a", hookLevel = 1),
                Charsets.UTF_8,
            ).contains("\"hookLevel\":1"),
        )
    }

    /** 指定的档位要真的落进产物。 */
    @Test
    fun `指定 hookLevel 能落进产物`() {
        val apk = locateApk() ?: return
        val work = workDir("hooklevel-core")
        val out = File(work, "out.apk")

        UncheckPipeline.run(
            apk, out, fakePayload(), workDir = work,
            hookLevel = UncheckPipeline.HookLevel.CORE,
        )
        val json = String(readEntry(out, UncheckPayload.ASSET_CONFIG)!!, Charsets.UTF_8)
        assertTrue(
            "指定 CORE(1) 应原样落进 sigfix.json，实际: $json",
            json.contains("\"hookLevel\":1"),
        )
    }

    /**
     * 只做 Java 层：产物**不含** `.so`，也不改 `extractNativeLibs`。
     *
     * ## 防的是什么
     *
     * 上游 `--java-only` 的语义是「整层不投放」。如果 ApkForge 只关了一半
     * （比如仍改 `extractNativeLibs`，或仍投放一个 `.so`），产物就是半吊子：
     * 前者无意义地动了目标包 Manifest，后者 `.so` 在包里但没有 Java 侧调用它。
     *
     * 更糟的是「以为关了其实开着」：用户关掉开关是为了修「App 打不开」，
     * 若开关没真正生效，他会以为这个方案无效。
     */
    @Test
    fun `只做 Java 层时不投放 so 且不改 extractNativeLibs`() {
        val apk = locateApk() ?: return
        val work = workDir("java-only")
        val out = File(work, "out.apk")

        val result = UncheckPipeline.run(
            apk, out, fakePayload(), workDir = work,
            nativeLayer = false,
        )

        val layout = ZipCentralDirectory.read(out)
        val names = layout.names

        // 两个 .so 都不该在
        assertTrue("只做 Java 层时不该有 arm64 .so", !names.contains(UncheckPayload.LIB_ARM64))
        assertTrue("只做 Java 层时不该有 armv7 .so", !names.contains(UncheckPayload.LIB_ARM32))

        // Java 层该有的一个不能少
        assertTrue("缺新 dex", names.contains(result.newDex))
        assertTrue("缺 payload.dex", names.contains(UncheckPayload.ASSET_PAYLOAD_DEX))
        assertTrue("缺 sigfix.json", names.contains(UncheckPayload.ASSET_CONFIG))
        assertTrue("缺 origin.apk", names.contains(UncheckPayload.ASSET_ORIGIN))

        // Manifest 必须仍指向 SigFixApp（Java 层靠它挂载）
        val mf = readEntry(out, UncheckPayload.MANIFEST)!!
        assertEquals(UncheckPayload.SIGFIX_APP, AxmlEditor.getAppName(mf))

        // 结果要如实报告
        assertEquals("nativeLayer 应为 false", false, result.nativeLayer)
        assertNull("没改过 extractNativeLibs，应为 null", result.extractNativeLibs)
        assertTrue("不具备 native 能力", !result.nativeCapable)
    }

    /**
     * 只做 Java 层时**不能**改 extractNativeLibs。
     *
     * 单独钉这一条是因为它是「多余的改动」：extractNativeLibs 只影响
     * `.so` 是否被解压，没有 `.so` 时改它毫无意义，却白动了目标包的 Manifest。
     * 判据：原包是 false 的，产物里仍应是 false。
     */
    @Test
    fun `只做 Java 层时保留原 extractNativeLibs 值`() {
        val apk = locateApk() ?: return
        val srcMf = readEntry(apk, UncheckPayload.MANIFEST)!!
        val srcValue = AxmlBoolean.getExtractNativeLibs(srcMf)

        val work = workDir("java-only-libs")
        val out = File(work, "out.apk")
        UncheckPipeline.run(apk, out, fakePayload(), workDir = work, nativeLayer = false)

        val outValue = AxmlBoolean.getExtractNativeLibs(
            readEntry(out, UncheckPayload.MANIFEST)!!
        )
        assertEquals(
            "只做 Java 层时不该动 extractNativeLibs（原包 $srcValue）",
            srcValue,
            outValue,
        )
    }

    /** 默认必须投放 native 层（不能因为加了开关就默认关掉）。 */
    @Test
    fun `默认投放 native 层`() {
        val apk = locateApk() ?: return
        val work = workDir("default-native")
        val out = File(work, "out.apk")
        val result = UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        assertEquals("默认 nativeLayer 应为 true", true, result.nativeLayer)
        val names = ZipCentralDirectory.read(out).names
        assertTrue("默认该有 arm64 .so", names.contains(UncheckPayload.LIB_ARM64))
        assertTrue("默认该有 armv7 .so", names.contains(UncheckPayload.LIB_ARM32))
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

    /**
     * 顺序的反向说明：**产物之后再重写一次 ZIP，origin.apk 里的签名块必然丢**。
     *
     * 这条不是「期望这样」，而是把已知的错法固化下来 —— 让后来的人一眼看到
     * 「为什么签名块必须在中间包之后、数据复用之前注入，且之后不能再跑
     * apksigner / zipalign」。
     *
     * 云端 2026-11-24 的 SIGBUS 事故就是这一步漏了：加固包的 .ncx 段要靠
     * 自己 APK 里的证书解，签名块丢了 → 解出乱码 → 跳野地址。
     */
    @Test
    fun `顺序错时签名块会丢_反向说明`() {
        val apk = locateApk() ?: return
        assumeTrue("测试 APK 无签名块，跳过", ApkSigningBlock.hasBlock(apk))

        val work = workDir("wrong-order")
        val out = File(work, "out.apk")
        UncheckPipeline.run(apk, out, fakePayload(), workDir = work)

        // 正确顺序下 origin.apk 应带签名块
        val layout = ZipCentralDirectory.read(out)
        val goodOrigin = File(work, "good-origin.apk")
        goodOrigin.writeBytes(ZipCentralDirectory.readRaw(out, layout.byName(UncheckPayload.ASSET_ORIGIN)!!))
        assertTrue(
            "前置条件：正确顺序下 origin.apk 应带签名块",
            ApkSigningBlock.hasBlock(goodOrigin),
        )

        // 模拟「复用之后又跑了一次 zipalign / apksigner」——重写整个 ZIP
        val rewritten = File(work, "rewritten.apk")
        ZipFile(out).use { zin ->
            java.util.zip.ZipOutputStream(rewritten.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zout.write(zin.getInputStream(e).use { it.readBytes() })
                    zout.closeEntry()
                }
            }
        }
        val rl = ZipCentralDirectory.read(rewritten)
        val badOrigin = File(work, "bad-origin.apk")
        badOrigin.writeBytes(
            ZipCentralDirectory.readRaw(rewritten, rl.byName(UncheckPayload.ASSET_ORIGIN)!!)
        )

        assertTrue(
            "重写后 origin.apk 的签名块必然消失 —— 这就是「复用之后绝不能再跑任何 " +
                "ZIP 重写工具」（zipalign / apksigner 都会）的由来。" +
                "若此断言变红，说明 java.util.zip 行为变了，需重新评估注入顺序。",
            !ApkSigningBlock.hasBlock(badOrigin),
        )
        // 顺带确认：重写后体积也翻倍（重叠条目被展开）
        assertTrue(
            "重写后体积应变大（重叠条目被展开成两份），实际 ${out.length()} → ${rewritten.length()}",
            rewritten.length() > out.length(),
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

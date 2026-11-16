package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实 release 包**（52MB / 3 个 dex / 全部 039 / 带 v1 签名）验证增量写入。
 *
 * 【为什么不用 app-debug.apk 就够了】debug 包 17 个 dex、版本全 039，
 * 但它的 dex 是 AGP 现造的，形态比线上包规整。这个 release 包是实际发布的产物：
 *
 * - 3 个 dex，`classes.dex` 28.5MB —— 正是 `docs/日志.md` 里那次「19 秒」的同一个包
 * - dex 版本 039，能验证「版本头不被改写」
 * - 带 `META-INF/CERT.RSA`（v1 签名），能验证重打包后的签名状态
 *
 * 样本路径通过 `-Dapkforge.releaseApk=/path` 或环境变量 `APKFORGE_RELEASE_APK` 指定；
 * 没指定时整组用例 skip（不伪绿）。
 */
class RealReleaseApkTest {

    private fun locateReleaseApk(): File? {
        val p = System.getProperty("apkforge.releaseApk") ?: System.getenv("APKFORGE_RELEASE_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        val guess = File(System.getProperty("user.home"), "sample-release.apk")
        return guess.takeIf { it.isFile }
    }

    /** 在指定 dex 里找「LAUNCHER 类同 dex 内的无参 static 方法」。 */
    private data class Target(
        val dexName: String,
        val dexBytes: ByteArray,
        val launcherType: String,
        val calleeType: String,
        val calleeName: String,
    )

    private fun findTarget(apk: File): Target? {
        val manifest = ApkRepackager.readEntry(apk, "AndroidManifest.xml")
            ?.let { com.xixin.apkforge.core.axml.AxmlManifest.parseBytes(it) }
        val launcher = manifest?.primaryLauncher ?: return null
        val launcherType = "L${launcher.replace('.', '/')};"

        for (dexName in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, dexName) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            }.getOrNull() ?: continue
            if (df.classes.none { it.type == launcherType }) continue

            for (cls in df.classes) {
                for (m in cls.methods) {
                    if (m.accessFlags and 0x8 != 0 && m.parameterTypes.isEmpty() &&
                        m.returnType == "V" && m.implementation != null &&
                        m.implementation!!.tryBlocks.isEmpty()
                    ) {
                        return Target(dexName, bytes, launcherType, cls.type, m.name)
                    }
                }
            }
        }
        return null
    }

    /**
     * 真实包上的速度对比。
     *
     * 这是本组最有价值的一条：`docs/日志.md` 记录的 18.9 秒就是同一个包、
     * 同一个位置。这里用实测数字确认增量把那段压到多少。
     */
    @Test
    fun `真实 release 包上增量写入的速度`() {
        val apk = locateReleaseApk()
        assumeTrue("没有指定 release 样本，跳过", apk != null)
        val t = findTarget(apk!!)
        assumeTrue("release 包里找不到合适靶子", t != null)
        val target = t!!

        val calls = com.xixin.apkforge.core.dex.SmaliCallParser.parseAll(
            "invoke-static {}, ${target.calleeType}->${target.calleeName}()V"
        ).first
        val spec = DexCallInjector.Spec(
            target.launcherType, "onCreate", "(Landroid/os/Bundle;)V", calls
        )

        // 全量
        val t0 = System.nanoTime()
        val full = DexCallInjector.inject(target.dexBytes, spec)
        val fullMs = (System.nanoTime() - t0) / 1_000_000
        assumeTrue("全量注入失败：${(full as? DexCallInjector.Outcome.Failed)?.reason}", full is DexCallInjector.Outcome.Injected)

        // 增量
        val t1 = System.nanoTime()
        val fast = FastInjector.tryInject(target.dexBytes, spec)
        val fastMs = (System.nanoTime() - t1) / 1_000_000

        println(
            "RELEASE dex=${target.dexName} ${target.dexBytes.size / 1048576}MB " +
                "全量=${fullMs}ms 增量=${fastMs}ms"
        )
        assertTrue("增量应适用，实际 $fast", fast is FastInjector.Result.Injected)
        val ok = fast as FastInjector.Result.Injected
        // 门槛定 2 倍：这是一条「防止退化成全量」的护栏，不是性能指标。
        // 早先写的是 5 倍，在手机上（JIT 预热差异、后台负载）经常卡在 4.x 倍而误报失败 ——
        // 那种波动说明不了实现有问题，只说明阈值定得不现实。
        // 真正要防的是「增量路径悄悄退化成全量」，2 倍足以区分。
        assertTrue(
            "增量应至少快 2 倍（否则说明退化成全量了）：全量=${fullMs}ms 增量=${fastMs}ms",
            fastMs * 2 < fullMs,
        )

        // 结构验证
        val newDf = runCatching {
            DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(ok.dex), ok.dex.inputStream())
        }.getOrNull()
        assertNotNull("产物 dex 结构非法", newDf)

        // 版本头不变（039 不能被写成 035）
        assertEquals(
            "dex 版本头被改写",
            DexOpcodes.dexVersion(target.dexBytes),
            DexOpcodes.dexVersion(ok.dex),
        )
        println("RELEASE ver=${DexOpcodes.label(ok.dex)} size ${ok.dex.size} 字节")
    }

    /**
     * 产物必须能被 dexlib2 完整解析，且**所有类都在**。
     *
     * 28.5MB / 16722 个类，逐个比对类名集合——
     * 这能抓到「新 class_data_item 编码错导致后面的类全丢」这类问题。
     */
    @Test
    fun `真实包增量写入后类集合不变`() {
        val apk = locateReleaseApk()
        assumeTrue("没有指定 release 样本，跳过", apk != null)
        val t = findTarget(apk!!)
        assumeTrue("找不到靶子", t != null)
        val target = t!!

        val before = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(target.dexBytes), target.dexBytes.inputStream()
        ).classes.map { it.type }.toSet()
        assumeTrue("类太少，测不出差异", before.size > 1000)

        val calls = com.xixin.apkforge.core.dex.SmaliCallParser.parseAll(
            "invoke-static {}, ${target.calleeType}->${target.calleeName}()V"
        ).first
        val fast = FastInjector.tryInject(
            target.dexBytes,
            DexCallInjector.Spec(target.launcherType, "onCreate", "(Landroid/os/Bundle;)V", calls)
        )
        assertTrue("增量应成功", fast is FastInjector.Result.Injected)

        val after = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes((fast as FastInjector.Result.Injected).dex),
            fast.dex.inputStream()
        ).classes.map { it.type }.toSet()

        assertEquals("类数量变了", before.size, after.size)
        assertEquals("类集合变了（有类丢失或多出）", before, after)
        println("RELEASE 类集合 ${before.size} 个，逐一并对一致")
    }

    /**
     * 真实包上，**未被修改的类**其方法指令必须完全不变。
     *
     * 抽查一部分类（全量比对 16722 个类太慢），重点覆盖第一个类与最后一个类——
     * 差分编码若算错，越靠后的类错得越离谱。
     */
    @Test
    fun `真实包未被修改的类指令不变`() {
        val apk = locateReleaseApk()
        assumeTrue("没有指定 release 样本，跳过", apk != null)
        val t = findTarget(apk!!)
        assumeTrue("找不到靶子", t != null)
        val target = t!!

        fun snapshot(df: DexBackedDexFile): Map<String, List<String>> {
            // 取前 5 个 + 后 5 个类，覆盖「开头」与「末尾」两个最容易出错的区间
            val all = df.classes.toList()
            val picked = all.take(5) + all.takeLast(5)
            return picked.associate { cls ->
                cls.type to cls.methods
                    .filter { it.implementation != null && it.definingClass != target.launcherType }
                    .map { m -> "${m.name}:" + m.implementation!!.instructions.joinToString(",") { it.opcode.name } }
            }
        }

        val beforeDf = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(target.dexBytes), target.dexBytes.inputStream()
        )
        val before = snapshot(beforeDf)

        val calls = com.xixin.apkforge.core.dex.SmaliCallParser.parseAll(
            "invoke-static {}, ${target.calleeType}->${target.calleeName}()V"
        ).first
        val fast = FastInjector.tryInject(
            target.dexBytes,
            DexCallInjector.Spec(target.launcherType, "onCreate", "(Landroid/os/Bundle;)V", calls)
        ) as FastInjector.Result.Injected

        val afterDf = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(fast.dex), fast.dex.inputStream()
        )
        val after = snapshot(afterDf)

        before.forEach { (clsType, methods) ->
            assertEquals("类 $clsType 的方法指令变了", methods, after[clsType])
        }
        println("RELEASE 抽查 ${before.size} 个类的方法指令，逐字一致")
    }
}

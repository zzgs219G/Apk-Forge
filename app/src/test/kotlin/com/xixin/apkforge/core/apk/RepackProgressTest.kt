package com.xixin.apkforge.core.apk

import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 钉住重打包时**大条目内部的字节进度回调**。
 *
 * ## 防的是什么
 *
 * `classes2.dex` 12.5MB，DEFLATE 压缩在手机上要 2 秒。早先 `repack()` 只在
 * **每个条目写完后**回调一次（`onEntry`），于是这 2 秒里进度条从 76/147
 * 冻到 79/147 不动 —— 用户看到的就是「卡死」。
 *
 * 现在 `writeEntry` 按 256KB 分块，边写边回调 `onBytes`。
 * 这个测试钉住：**大条目必须产生多次中间回调**，而不是只有 0% 和 100%。
 */
class RepackProgressTest {

    private fun targetApk(): File? {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("app/build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile }
    }

    @Test
    fun `大条目写出时必须产生多次字节进度回调`() {
        val apk = targetApk() ?: return

        // 找一个够大的 dex 当替换目标，确保它会分块写
        val bigDex = runCatching {
            ZipFile(apk).use { z ->
                z.entries().toList()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .maxByOrNull { it.size }
                    ?.let { e -> e.name to z.getInputStream(e).use { it.readBytes() } }
            }
        }.getOrNull() ?: return

        val (dexName, dexBytes) = bigDex
        // 小于分块大小的条目不会分块，这个测试就没意义
        if (dexBytes.size < 512 * 1024) return

        val out = File.createTempFile("repack-progress", ".apk").apply { deleteOnExit() }
        val ticks = ArrayList<Pair<Int, Int>>()   // (已写, 总字节)

        ApkRepackager.repack(
            sourceApk = apk,
            outputApk = out,
            targetDexName = dexName,
            newDexBytes = dexBytes,
            payloadDexBytes = null,
            onEntry = { _, _, _ -> },
            onBytes = { written, totalBytes, _ -> ticks += written to totalBytes },
        )

        // 至少要有 3 次回调：0 之后、中途、写完。
        // 只有 1 次说明整块写了，进度条会冻住。
        assertTrue(
            "大条目 ${dexName}（${dexBytes.size} 字节）应产生多次字节进度回调，" +
                "实际只有 ${ticks.size} 次 —— 说明又变回整块写出了，进度条会卡住",
            ticks.size >= 3,
        )
        // 必须是单调递增，且最后一次等于总长
        val totals = ticks.map { it.second }.distinct()
        assertTrue(
            "同一条目的总字节数应始终一致，实际出现 ${totals.size} 种：$totals",
            totals.size == 1,
        )
        assertTrue(
            "回调应是单调递增的，实际 ${ticks.map { it.first }}",
            ticks.zipWithNext().all { (a, b) -> b.first >= a.first },
        )
        assertTrue(
            "最后一次回调应等于总字节数 ${dexBytes.size}，实际 ${ticks.last().first}",
            ticks.last().first == dexBytes.size,
        )

        out.delete()
    }
}

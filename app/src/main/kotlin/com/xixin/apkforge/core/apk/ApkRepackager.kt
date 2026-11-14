package com.xixin.apkforge.core.apk

import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 重打包：只做「替换 dex + 追加 payload dex」，其余条目**原样二进制拷贝**。
 *
 * 设计取舍：
 * - 不用 apkzlib：它的 `bytestorage` 包引用 `java.nio.file`（Android 上被裁剪），
 *   而这里只需要 zip 读写，`java.util.zip` 在 Android 上完全够用。
 * - 不用 apksig：目前不重签名（用户明确「先不搞重签名」）。
 * - **不碰 AndroidManifest.xml**：原样二进制搬运。任何「解码 Manifest 再写回」
 *   的路径都会产出系统不认的灰包（artifact-pipeline 报错四/五的教训）。
 * - 保留原 entry 的压缩方式与时间戳：`resources.arsc` 与 `AndroidManifest.xml`
 *   必须是 STORED + 4 字节对齐校验通过，重压缩会破坏它们。
 */
object ApkRepackager {

    data class Stats(
        val totalEntries: Int,
        val replacedDex: String,
        val addedDex: String?,
        /** 本次写入（或替换）的非 dex 条目，如 `assets/apkforge_dialog.json` */
        val addedAssets: List<String> = emptyList(),
        val originalSize: Long,
        val outputSize: Long,
    )

    /**
     * 把 [targetDexName] 替换成 [newDexBytes]；若 [payloadDexBytes] 非空，
     * 追加成下一个可用的 `classesN.dex`。
     */
    fun repack(
        sourceApk: File,
        outputApk: File,
        targetDexName: String,
        newDexBytes: ByteArray,
        payloadDexBytes: ByteArray?,
        /**
         * 额外要写入的条目：path → 内容。
         *
         * 用途是「载荷的配置文件」——例如本地弹窗载荷会读宿主的
         * `assets/apkforge_dialog.json`。这类文件不是 dex，但必须跟 dex 一起进包，
         * 否则载荷跑起来找不到配置。
         *
         * 同名条目会被**替换**而不是重复写入：zip 里出现两个同路径条目，
         * Android 的 AssetManager 取到哪个是未定义的。
         */
        extraEntries: Map<String, ByteArray> = emptyMap(),
        /** 每写一个条目回调一次（index 从 1 开始，total 含目录条目）。用于进度与日志。 */
        onEntry: ((index: Int, total: Int, name: String) -> Unit)? = null,
    ): Stats {
        val zip = ZipFile(sourceApk)
        var entryCount = 0
        var addedName: String? = null
        // ZipFile.entries() 是 Enumeration，先物化成 List 才能同时拿到总数与逐个遍历
        val allEntries = zip.entries().toList()
        val existingNames = allEntries.map { it.name }.toSet()
        // 已存在的同名条目不额外增加条目数（它是替换，不是新增）
        val newExtras = extraEntries.filterKeys { !existingNames.contains(it) }
        // ⚠️ 括号不能省：`a + if (c) 1 else 0 + n` 会被解析成 `a + if (c) 1 else (0 + n)`
        val total = allEntries.size + (if (payloadDexBytes != null) 1 else 0) + newExtras.size
        val written = mutableListOf<String>()

        try {
            // 找下一个空闲的 classesN.dex 名字
            if (payloadDexBytes != null) {
                var n = 2
                while (existingNames.contains("classes$n.dex")) n++
                // classes.dex 存在时从 classes2 起；若原包只有 classes.dex，仍从 classes2 起
                addedName = "classes$n.dex"
            }

            outputApk.parentFile?.mkdirs()
            ZipOutputStream(outputApk.outputStream().buffered()).use { out ->
                // 1) 原样拷贝所有条目，途中替换目标 dex
                for (entry in allEntries) {
                    if (entry.isDirectory) {
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        out.closeEntry()
                        entryCount++
                        onEntry?.invoke(entryCount, total, entry.name)
                        continue
                    }

                    val bytes: ByteArray = when {
                        entry.name == targetDexName -> newDexBytes
                        extraEntries.containsKey(entry.name) -> {
                            written += entry.name
                            extraEntries.getValue(entry.name)
                        }
                        else -> zip.getInputStream(entry).use { it.readBytesCompat() }
                    }

                    writeEntry(out, entry, bytes)
                    entryCount++
                    onEntry?.invoke(entryCount, total, entry.name)
                }

                // 2) 追加原本不存在的额外条目（如 assets/ 下的配置）
                newExtras.forEach { (path, data) ->
                    val e = ZipEntry(path).apply {
                        method = ZipEntry.DEFLATED
                        time = 0L
                    }
                    writeEntry(out, e, data)
                    written += path
                    entryCount++
                    onEntry?.invoke(entryCount, total, path)
                }

                // 3) 追加 payload dex
                if (payloadDexBytes != null && addedName != null) {
                    val e = ZipEntry(addedName).apply {
                        method = ZipEntry.DEFLATED
                        time = 0L   // 与 AGP 产物一致：固定时间戳，保证可复现
                    }
                    writeEntry(out, e, payloadDexBytes)
                    entryCount++
                    onEntry?.invoke(entryCount, total, addedName)
                }
            }
        } finally {
            zip.close()
        }

        return Stats(
            totalEntries = entryCount,
            replacedDex = targetDexName,
            addedDex = addedName,
            addedAssets = written,
            originalSize = sourceApk.length(),
            outputSize = outputApk.length(),
        )
    }

    private fun writeEntry(out: ZipOutputStream, src: ZipEntry, data: ByteArray) {
        val e = ZipEntry(src.name).apply {
            time = src.time
            // 压缩方式跟随原条目：STORED 的别改成 DEFLATED
            method = if (src.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            if (method == ZipEntry.STORED) {
                size = data.size.toLong()
                val crc = CRC32().apply { update(data) }
                compressedSize = data.size.toLong()
                this.crc = crc.value
            }
        }
        out.putNextEntry(e)
        out.write(data)
        out.closeEntry()
    }

    private fun InputStream.readBytesCompat(): ByteArray {
        val buf = java.io.ByteArrayOutputStream(8192)
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    /** APK 内所有 `classes*.dex` 的条目名，按 classes.dex, classes2.dex, ... 排序。 */
    fun listDexNames(apk: File): List<String> {
        ZipFile(apk).use { zip ->
            return zip.entries().toList()
                .map { it.name }
                .filter { Regex("""^classes\d*\.dex$""").matches(it) }
                .sortedBy { name ->
                    val n = name.removePrefix("classes").removeSuffix(".dex")
                    if (n.isEmpty()) 1 else n.toIntOrNull() ?: Int.MAX_VALUE
                }
        }
    }

    fun readEntry(apk: File, name: String): ByteArray? {
        ZipFile(apk).use { zip ->
            val e = zip.getEntry(name) ?: return null
            return zip.getInputStream(e).use { it.readBytesCompat() }
        }
    }
}

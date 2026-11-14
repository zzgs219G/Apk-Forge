package com.xixin.apkforge.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 注入产物的落盘位置。
 *
 * 【为什么不用 getExternalFilesDir】那条路径是
 * `/sdcard/Android/data/<pkg>/files/`，Android 11+ 的文件管理器默认看不到，
 * MT 也要单独授权才能进——用户注入完去文件管理里找不到文件，等于白做。
 *
 * 【为什么用 MediaStore】`/sdcard/Download/` 在分区存储（Android 10+）下
 * 不能直接 File 写入，但通过 MediaStore 插入 Downloads 集合是**免权限**的，
 * 而且落盘的真实位置就是用户熟悉的 `Download/ApkForge/`。
 *
 * 【为什么要同时留一份 File 引用】安装 APK 和「MT 定位」都需要真实路径，
 * MediaStore 只给 content:// URI。所以插入后按 RELATIVE_PATH + DISPLAY_NAME
 * 反推文件路径，两个形态都保留。
 */
object OutputStore {

    private const val SUB_DIR = "ApkForge"
    private const val MIME_APK = "application/vnd.android.package-archive"

    data class Saved(
        val file: File,
        val uri: Uri?,
    )

    /** 用户可见的默认输出目录，仅用于展示。 */
    fun displayDir(): String = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"

    /**
     * 把 [source] 拷进 Download/ApkForge/[targetName]。
     * 同名文件已存在时自动加 `-1`、`-2` 后缀，不覆盖用户已有产物。
     */
    fun saveApk(context: Context, source: File, targetName: String): Saved {
        val name = uniqueName(context, targetName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME_APK)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore 插入失败")

            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入 Download")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$SUB_DIR/$name"
            )
            return Saved(file, uri)
        }

        // Android 9 及以下：直接 File 写（需要 WRITE_EXTERNAL_STORAGE，本工程 minSdk 28，
        // 由 Manifest 声明并按需申请；这里失败则退回应用私有目录，保证有产出）
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        return try {
            dir.mkdirs()
            val f = File(dir, name)
            source.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            Saved(f, null)
        } catch (_: Exception) {
            val fallback = File(context.getExternalFilesDir(null), "output").apply { mkdirs() }
            val f = File(fallback, name)
            source.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            Saved(f, null)
        }
    }

    /**
     * 写任意字节到 `Download/ApkForge/`。
     *
     * 与 [saveApk] 共用同一套落盘逻辑（MediaStore + 真实路径反推），
     * 区别只在 MIME：zip 用 `application/zip`，否则系统可能把它当成 APK 去装。
     * 单独抽一个函数而不是让调用方自己拼，是为了「产物路径用户可见」这条铁律
     * 只有一处实现——多一套写法就多一个「某些机型看不到文件」的坑。
     */
    fun saveFile(context: Context, bytes: ByteArray, targetName: String, mime: String): Saved {
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}_$targetName")
        tmp.writeBytes(bytes)
        try {
            val name = uniqueName(context, targetName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore 插入失败")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("无法写入 Download")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$SUB_DIR/$name"
                )
                return Saved(file, uri)
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUB_DIR
            ).apply { mkdirs() }
            val f = File(dir, name)
            tmp.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return Saved(f, null)
        } finally {
            tmp.delete()
        }
    }

    private fun uniqueName(context: Context, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "apk")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base-$i.$ext"
            i++
        }
        return candidate
    }

    /** 调起系统安装器。用 MediaStore 的 content URI 才不会被 FileUriExposed 拦住。 */
    fun installApk(context: Context, saved: Saved): Boolean = try {
        val uri = saved.uri ?: run {
            // 无 URI（Android 9 及以下直接文件写入）：走 FileProvider
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", saved.file
            )
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 按**真实路径**安装/分享/定位历史产物。
     *
     * 【为什么不能复用 [Saved] 的 URI】MediaStore 的 content URI 只在本次进程有效，
     * 任务记录里存的是文件路径（重启后仍要能用）。所以这里统一走 FileProvider，
     * 而 `file_paths.xml` 里为此声明了 `Download/ApkForge/` 这一条 external-path。
     */
    fun uriFor(context: Context, file: File): Uri? = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    } catch (_: Exception) {
        null
    }

    fun installPath(context: Context, file: File): Boolean = try {
        val uri = uriFor(context, file) ?: return false
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (_: Exception) {
        false
    }

    fun sharePath(context: Context, file: File): Boolean = try {
        val uri = uriFor(context, file) ?: return false
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = MIME_APK
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "分享注入产物"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    fun shareFile(context: Context, saved: Saved): Boolean = try {
        val uri = saved.uri ?: androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", saved.file
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = MIME_APK
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "分享注入产物"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}

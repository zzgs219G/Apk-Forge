package com.xixin.apkforge.inject

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 文件选择：优先 MT 管理器（AndroidCS / MT 用户的习惯路径），不可用时降级系统选择器。
 *
 * 不搬 jian_box 的 UI，只借用它的选择策略：
 * MT 已安装 → `ACTION_GET_CONTENT` 限定包名；否则 → 裸 `ACTION_GET_CONTENT`。
 *
 * 选完立刻把 content URI 的内容复制进 `cacheDir`：SAF 给的 URI 权限是临时的，
 * 复制后才是可反复读写的稳定 File，后续 zip/dex 操作全部基于 File。
 */
object FilePicker {

    const val MT_PACKAGE = "bin.mt.plus"

    fun isMtInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MT_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun buildIntent(context: Context, mime: String = "*/*"): Intent {
        val base = Intent(Intent.ACTION_GET_CONTENT).apply { type = mime }
        return if (isMtInstalled(context)) {
            Intent(base).apply { setPackage(MT_PACKAGE) }
        } else {
            base
        }
    }

    /**
     * 把选中的 URI 复制到 cacheDir，返回稳定 File。
     * 文件名冲突时加序号，避免第二次选择覆盖第一次。
     */
    fun copyToCache(context: Context, uri: Uri, subDir: String = "picked"): File {
        val dir = File(context.cacheDir, subDir).apply { mkdirs() }
        val name = queryDisplayName(context, uri) ?: "picked_${System.currentTimeMillis()}.bin"

        var target = File(dir, name)
        if (target.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var i = 1
            while (target.exists()) {
                target = File(dir, if (ext.isEmpty()) "$base-$i" else "$base-$i.$ext")
                i++
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return target
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun pickApkIntent(context: Context): Intent = buildIntent(context).apply {
        // MT 对 type 不敏感；系统选择器给 apk 专有 mime 反而可能筛不到
        type = "*/*"
    }

    fun pickDexIntent(context: Context): Intent = buildIntent(context)

    fun openWith(context: Context, file: File, mime: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            // 宿主没装能打开的应用时静默失败，UI 层已提示路径
        }
    }

    fun locateInMt(context: Context, file: File): Boolean {
        if (!isMtInstalled(context)) return false
        return try {
            context.startActivity(
                Intent("bin.mt.plus.ACTION_SHORTCUT").apply {
                    setComponent(ComponentName(MT_PACKAGE, "bin.mt.plus.ShortcutActivity"))
                    putExtra("operation", "locate")
                    putExtra("path", file.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

package com.xixin.apkforge.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 从一个文件夹（`ACTION_OPEN_DOCUMENT_TREE` 选出来的树 URI）里找出所有 APK。
 *
 * 【为什么不用 DocumentFile】`androidx.documentfile` 是个额外依赖，而它内部做的事
 * 就是 `DocumentsContract.buildChildDocumentsUriUsingTree` + cursor 遍历。
 * 本工程刻意只留一个运行时依赖（见 AGENTS.md 1.4），这里 40 行手写更划算，
 * 而且能顺手把「递归深度上限」这种东西写清楚。
 *
 * 【为什么要递归】用户选的目录常是 `Download/` 或某个 APK 合集包解出来的目录，
 * 真正的 apk 往往在子目录里。只扫一层会让「批量注入」变成「批量找不到」。
 * 但递归必须有上限：某些目录（或被恶意构造的树）会套很深。
 */
object ApkFolderScanner {

    /** 递归深度上限。超过就停，宁可少扫也不要把手机卡住。 */
    private const val MAX_DEPTH = 6

    /** 单次批量任务的 APK 上限。再多应该分批做，一屏队列也读不过来。 */
    const val MAX_FILES = 50

    data class Found(
        val uri: Uri,
        val name: String,
        val size: Long,
    )

    /**
     * 列出 [treeUri] 下所有 `.apk`。
     *
     * 返回的是 content URI 而非 File：分区存储下用户选的目录未必有可写 File 路径，
     * 后续由 `FilePicker.copyToCache` 统一把内容复制进私有缓存再处理——
     * 与单个选包走完全相同的路径，批量与单个的行为就不会分叉。
     */
    fun listApks(context: Context, treeUri: Uri): List<Found> {
        val out = mutableListOf<Found>()
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        walk(context, treeUri, root, 0, out)
        return out.sortedBy { it.name }
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        out: MutableList<Found>,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(context, treeUri, id, depth + 1, out)
                    } else if (name.endsWith(".apk", ignoreCase = true)) {
                        out += Found(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name = name,
                            size = size,
                        )
                    }
                }
            }
        }
    }

    /** 人类可读的体积，仅用于队列展示。 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 给 `FilePicker.copyToCache` 用的建议文件名。 */
    fun localName(f: Found): String = f.name.ifBlank { File(f.name).name }
}

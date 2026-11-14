package com.xixin.apkforge.ui.lifted.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * 「复制到剪贴板」的统一入口。
 *
 * 【为什么抽出来】`LocalClipboardManager` 已废弃，官方入口 `LocalClipboard.setClipEntry`
 * 是 suspend 的，每个调用点都要自带 scope + ClipData 三行样板。
 * 日志、调用清单、产物路径都要复制，复制三遍不如收成一处——
 * 将来要加「复制后 toast／震动反馈」也只改这里。
 */
@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ApkForge", text)))
            }
        }
    }
}

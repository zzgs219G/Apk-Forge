package com.xixin.apkforge.ui.lifted.components.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// =====================================================================
// 1. 标准化大文本输入框（起始单行高，随内容自动长高，上限 maxHeight；右上角悬浮粘贴/清空）
// =====================================================================
@Composable
fun ToolInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /**
     * 自动长高的上限（超过后输入框内部滚动）。
     *
     * 2026-11：此前是**固定 180dp**，空内容时也是一大块空白，视觉上压过页面其它元素。
     * 现在起始就是单行高（`minLines = 1`），随内容变多长高，最多到 [maxHeight] ——
     * 功能完全不变（仍是多行、仍可粘贴），只是不再一上来就占满。
     *
     * 调用方若传入固定 `height(xx.dp)`，该高度优先，自动长高自然失效（保持不变即可）。
     */
    maxHeight: Dp = 180.dp
) {
    // LocalClipboardManager 已废弃；新入口是 suspend 的 setClipEntry，
    // 这里只要「读文本」，用协程把 getClipEntry 拉回来即可。
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val paste: () -> Unit = {
        scope.launch {
            clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(null)?.toString()?.let { text ->
                    if (text.isNotEmpty()) onValueChange(text.replace("\n", "").replace("\r", ""))
                }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(max = maxHeight),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = 1,
        isError = isError,
        keyboardOptions = keyboardOptions,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
        // ⚠️ 2026-11：快捷操作从「右上角悬浮 Box 叠层」改为官方 trailingIcon。
        //    原因：输入框改为随内容长高后，空内容时只有一行高，悬浮按钮会压在浮起的 label 上。
        //    trailingIcon 由组件内部垂直居中，单行/多行都不会撞。
        trailingIcon = {
            if (value.isEmpty()) {
                IconButton(onClick = {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(null)?.toString()?.let { text ->
                                if (text.isNotEmpty()) onValueChange(text)
                            }
                    }
                }) {
                    Icon(
                        Icons.Rounded.ContentPaste,
                        contentDescription = "粘贴",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    )
}

// =====================================================================
// 2. 标准化单行小输入框 (高度自适应，适合短密码、密钥、正则规则)
// =====================================================================
@Composable
fun ToolSingleLineInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    /**
     * 单行视觉 / 多行功能（默认 false = 严格单行，行为与加此参数前完全一致）。
     *
     * 置 true 时：视觉起始高度仍是**一行**（首屏不浪费空白），但不锁 `singleLine`——
     * 用户回车即可换行、输入框随内容自动长高。
     * 适用于「外观要紧凑、内容天然多行」的场景（如分行的规则清单）。
     */
    autoGrow: Boolean = false,
    /** autoGrow = true 时的最大高度（超过后内部滚动）。默认 null = 不设上限 */
    maxHeight: Dp? = null
) {
    // LocalClipboardManager 已废弃；新入口是 suspend 的 setClipEntry，
    // 这里只要「读文本」，用协程把 getClipEntry 拉回来即可。
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val paste: () -> Unit = {
        scope.launch {
            clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(null)?.toString()?.let { text ->
                    if (text.isNotEmpty()) onValueChange(text.replace("\n", "").replace("\r", ""))
                }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            // autoGrow：只设上限、不设 min，起始即单行高（回车后自然长高）
            if (autoGrow && maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier
        ),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = !autoGrow,
        // autoGrow 时锁最小 1 行，保证空内容也是单行视觉
        minLines = if (autoGrow) 1 else 1,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
        trailingIcon = trailingIcon ?: {
            if (value.isEmpty()) {
                IconButton(onClick = paste) {
                    Icon(Icons.Rounded.ContentPaste, "粘贴", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            } else {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Clear, "清空", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
            }
        }
    )
}

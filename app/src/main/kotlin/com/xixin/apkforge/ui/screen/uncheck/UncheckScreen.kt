package com.xixin.apkforge.ui.screen.uncheck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.apkforge.data.FilePicker
import com.xixin.apkforge.feature.uncheck.UncheckUiState
import com.xixin.apkforge.feature.uncheck.UncheckViewModel
import com.xixin.apkforge.ui.lifted.AppConstants
import com.xixin.apkforge.ui.lifted.components.card.SectionCard
import com.xixin.apkforge.ui.lifted.components.result.LogPanel
import com.xixin.apkforge.ui.lifted.page.AppTopBar

/**
 * 去签名校验页。
 *
 * 界面只做三件事：选 APK、看状态、点运行。
 * 所有"能不能跑"的判断都在 [UncheckUiState.blockers] 里，
 * 界面不重复实现 —— 那样两处必然漂移。
 */
@Composable
fun UncheckScreen(
    onOpenDrawer: () -> Unit,
    vm: UncheckViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ui = vm.ui

    // 进页面就准备载荷：用户得先知道"能不能用"，而不是选完文件跑一半才说缺载荷
    LaunchedEffect(Unit) {
        if (ui.payloadVersion == null && !ui.busy) vm.loadPayload()
    }

    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching { FilePicker.copyToCache(context, uri, "uncheck") }
                .onSuccess { vm.setInput(it) }
                .onFailure { /* 失败时保持原状态，不打断用户 */ }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "去签名校验",
            onMenuClick = onOpenDrawer,
            actions = {
                TextButton(onClick = { vm.loadPayload(forceRefresh = true) }) {
                    Text("刷新载荷")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AppConstants.SCREEN_EDGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PayloadCard(ui, onRetry = { vm.loadPayload(forceRefresh = true) }) }
            item { InputCard(ui, onPick = { pickApk.launch("*/*") }) }

            if (ui.busy) {
                item { ProgressCard(ui) }
            }
            ui.error?.let { err ->
                item { ErrorCard(err, onDismiss = vm::clearError) }
            }
            ui.lastOutput?.let { out ->
                item { SuccessCard(ui, path = out.absolutePath) }
            }
            item { RunCard(ui, onRun = vm::run) }

            // 日志面板：自带复制按钮。
            // 失败时用户能一键复制完整堆栈贴出来，不用装 adb、不用会看 logcat。
            item {
                LogPanel(
                    logs = vm.logs,
                    isError = ui.error != null,
                    isLoading = ui.busy,
                    showClearButton = true,
                    hideWhenEmpty = true,
                    maxHeight = 320.dp,
                )
            }

            item { BoundaryCard() }
        }
    }
}

// ───────────────────────── 卡片 ─────────────────────────

@Composable
private fun PayloadCard(ui: UncheckUiState, onRetry: () -> Unit) {
    SectionCard(
        title = "载荷",
        subtitle = "决定怎么绕过签名校验。首次使用需联网下载，之后走缓存",
        icon = Icons.Rounded.Security,
    ) {
        if (ui.payloadVersion != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(AppConstants.SMALL_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "v${ui.payloadVersion} · ${if (ui.payloadFromCache) "本地缓存" else "已下载"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.stage == "准备载荷") {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在获取…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(AppConstants.SMALL_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("未就绪", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun InputCard(ui: UncheckUiState, onPick: () -> Unit) {
    SectionCard(
        title = "输入 APK",
        subtitle = "要处理的原包。产物未签名，需你自己签",
        icon = Icons.Rounded.Download,
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            ui.inputApk?.let { f ->
                Text(
                    f.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    humanSize(ui.inputSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.inputApk == null) "选择 APK" else "换一个")
            }
        }
    }
}

@Composable
private fun ProgressCard(ui: UncheckUiState) {
    SectionCard(title = "处理中", subtitle = ui.stage, icon = Icons.Rounded.BugReport) {
        Column(Modifier.padding(top = 4.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.stageDetail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.stageDetail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    SectionCard(title = "失败", icon = Icons.Rounded.ErrorOutline) {
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun SuccessCard(ui: UncheckUiState, path: String) {
    SectionCard(
        title = "完成",
        subtitle = "产物未签名 —— 请用你自己的 keystore 签",
        icon = Icons.Rounded.CheckCircle,
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            ui.lastSummary?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it.trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunCard(ui: UncheckUiState, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = onRun,
            enabled = ui.canRun,
            modifier = Modifier
                .fillMaxWidth()
                .height(AppConstants.STANDARD_BUTTON_HEIGHT),
        ) {
            Text(if (ui.busy) "处理中…" else "开始处理")
        }
        // 禁用时必须给出**可见原因**，否则用户只看到灰按钮无从下手
        if (!ui.canRun && !ui.busy) {
            Spacer(Modifier.height(6.dp))
            ui.blockers.forEach {
                Text(
                    "· $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 边界说明卡。
 *
 * 刻意放在界面上而不是藏在文档里：用户需要知道哪些 App 这个工具
 * **做不到**，否则会反复试、反复失败，最后以为工具坏了。
 */
@Composable
private fun BoundaryCard() {
    SectionCard(
        title = "能做什么 / 做不到什么",
        subtitle = "失败不是 bug，是边界",
        icon = Icons.Rounded.WarningAmber,
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            val ok = listOf(
                "App 通过 PackageManager 查签名",
                "查 signatures / signingInfo / checkSignatures",
                "无论它在 Application 还是 Activity 里校验",
            )
            val no = listOf(
                "App 自己打开 APK 文件算哈希",
                "native 层（.so 里）的校验",
                "上报服务端裁决 / Play Integrity",
            )
            ok.forEach {
                Text(
                    "✓ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            no.forEach {
                Text(
                    "✗ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

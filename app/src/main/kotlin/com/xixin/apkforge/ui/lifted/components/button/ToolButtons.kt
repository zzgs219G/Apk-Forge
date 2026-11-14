package com.xixin.apkforge.ui.lifted.components.button

import com.xixin.apkforge.ui.lifted.AppConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.xixin.apkforge.ui.lifted.m3e.loading.M3ExpressiveDownloadProgress
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =====================================================================
// 3. 标准化主操作按钮 (实心高亮)
//    支持进行中（loading）态：内嵌 M3ExpressiveDownloadProgress + 文案/百分比
//    （规范 §3.5：进行中状态按钮内嵌该指示器并附百分比文本）。
//    补齐这个能力的动机：此前货架没有 loading 形态，各页面只好绕过
//    ToolPrimaryButton 自己写裸 Button，导致按钮高度/形状/禁用逻辑各页漂移。
// =====================================================================
@Composable
fun ToolPrimaryButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),  // ← 外部只需要传宽度相关
    // 进行中：显示指示器替代图标，按钮自动禁用（防重复提交）
    loading: Boolean = false,
    // 0f..1f 显示确定进度；null = 不确定进度（转圈）
    progress: Float? = null,
    // 进行中时替换的文案；null = 沿用 text
    loadingText: String? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        // loading 时强制禁用，但配色单独处理，避免禁用色把进度指示器也一起压暗
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()   // ← 强制宽度
            .height(AppConstants.STANDARD_BUTTON_HEIGHT),   // ← 强制高度，外部无法覆盖
        shape = MaterialTheme.shapes.medium,
        colors = if (loading) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        if (loading) {
            M3ExpressiveDownloadProgress(
                progress = progress,
                // 24dp 对齐项目内所有"按钮内嵌"调用点的既有约定（DouyinParserTool /
                // UnifiedParserTool / 本文件 Download 按钮）。20dp 时中心字号仅 6sp 糊成一团，
                // 且百分比已由按钮文案给出，此处只作视觉锚点。
                size = 24.dp,
                // 按钮此时底色是 primary，指示器默认色（fillColor=primary /
                // trackColor=surfaceContainerHighest）在 primary 底上会与底色同色而"隐形"，
                // 故显式改走 onPrimary 前景语言（规范 §1.3：不透明语义色，禁止手调 alpha）
                trackColor = MaterialTheme.colorScheme.primary,
                fillColor = MaterialTheme.colorScheme.onPrimary,
                textColor = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
            // 确定进度时附百分比（规范 §3.5）
            val pct = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            Text((loadingText ?: text) + pct)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(text)
        }
    }
}
// =====================================================================
// 4. 标准化次要操作按钮 (空心线框)
// =====================================================================
@Composable
fun ToolSecondaryButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier.fillMaxWidth().height(AppConstants.STANDARD_BUTTON_HEIGHT),
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

// ──────────────────────────────────────────────────────────────
// 危险操作按钮（用于删除、修复等需要强调错误的场景）
// ──────────────────────────────────────────────────────────────
@Composable
fun ToolDangerButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(AppConstants.STANDARD_BUTTON_HEIGHT),
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

// =====================================================================
// 5. 标准化双按钮并排组 (自动平分宽度)
//    reversed = false (默认): [次要(左)] [主要(右)]  ← 加密/编码统一在右（右手拇指优先）
//    reversed = true        : [主要(左)] [次要(右)]  ← 特殊场景反转
// =====================================================================
@Composable
fun ToolDoubleButtonRow(
    primaryText: String,
    primaryIcon: ImageVector,
    onPrimaryClick: () -> Unit,
    secondaryText: String,
    secondaryIcon: ImageVector,
    onSecondaryClick: () -> Unit,
    isProcessing: Boolean = false,
    reversed: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (reversed) {
            ToolPrimaryButton(
                text = primaryText,
                icon = primaryIcon,
                modifier = Modifier.weight(1f).height(AppConstants.STANDARD_BUTTON_HEIGHT),
                onClick = onPrimaryClick,
                enabled = !isProcessing
            )
            ToolSecondaryButton(
                text = secondaryText,
                icon = secondaryIcon,
                modifier = Modifier.weight(1f).height(AppConstants.STANDARD_BUTTON_HEIGHT),
                onClick = onSecondaryClick
            )
        } else {
            ToolSecondaryButton(
                text = secondaryText,
                icon = secondaryIcon,
                modifier = Modifier.weight(1f).height(AppConstants.STANDARD_BUTTON_HEIGHT),
                onClick = onSecondaryClick
            )
            ToolPrimaryButton(
                text = primaryText,
                icon = primaryIcon,
                modifier = Modifier.weight(1f).height(AppConstants.STANDARD_BUTTON_HEIGHT),
                onClick = onPrimaryClick
            )
        }
    }
}
// =====================================================================
// 6. 统一高可用通用下载按钮 (精雕大厂视效版)
// =====================================================================
enum class DownloadBtnState {
    Idle,        // 闲置/待下载
    Preparing,   // 正在嗅探/准备资源
    Downloading  // 正在下载中
}

@Composable
fun SharedDownloadButton(
    text: String,
    state: DownloadBtnState,
    progress: Float, // 0.0f 到 1.0f
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && state == DownloadBtnState.Idle) 0.96f else 1f,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
        label = "btn_scale"
    )
    Button(
        onClick = onClick,
        enabled = state == DownloadBtnState.Idle,
        modifier = modifier
            .fillMaxWidth()
            .height(AppConstants.STANDARD_BUTTON_HEIGHT)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            when (state) {
                DownloadBtnState.Idle -> {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                DownloadBtnState.Preparing -> {
                    M3ExpressiveDownloadProgress(progress = null, size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在破译资源...", fontSize = 16.sp)
                }
                DownloadBtnState.Downloading -> {
                    M3ExpressiveDownloadProgress(progress = progress, size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在无痕下载...", fontSize = 16.sp)
                }
            }
        }
    }
}

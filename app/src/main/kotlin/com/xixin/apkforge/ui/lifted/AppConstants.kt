package com.xixin.apkforge.ui.lifted

import androidx.compose.ui.unit.dp

/**
 * UI 设计规范与动画时长。
 *
 * 逐字移植自 `克隆仓库x/jian_box` 的 `utils/AppConstants.kt`（第 58–86 行），
 * 只取了 UI 设计规范 / 动画与交互规范 / 组件尺寸规范三段；
 * 网络超时、下载策略、文件路径等与本工程无关的段落未搬。
 */
object AppConstants {
    // =========================== UI 设计规范 (Design Tokens) ===========================
    // 确保整个 App 的视觉风格保持统一

    /** 屏幕边缘通用安全间距，避免内容贴边（16dp 对应 Material Design 规范） */
    val SCREEN_EDGE_PADDING = 16.dp
    
    /** 标准图标尺寸（用于列表、按钮等常规场景） */
    val STANDARD_ICON_SIZE = 24.dp
    
    /** 小图标尺寸（用于标签、辅助按钮等紧凑场景） */
    val SMALL_ICON_SIZE = 16.dp


    // =========================== 动画与交互规范 ===========================
    // 控制页面转场、组件反馈的速度与节奏

    /** 默认动画持续时间（毫秒），适用于页面切换、弹窗显隐 */
    const val DEFAULT_ANIM_DURATION_MS = 300
    
    /** 快速动画持续时间（毫秒），适用于微交互、按钮点击反馈 */
    const val FAST_ANIM_DURATION_MS = 150


    // =========================== 组件尺寸规范 ===========================
    // 全局统一交互区域大小，提升触控体验

    /** 标准按钮高度（符合 Android 无障碍触控建议 48dp 以上） */
    val STANDARD_BUTTON_HEIGHT = 56.dp

}

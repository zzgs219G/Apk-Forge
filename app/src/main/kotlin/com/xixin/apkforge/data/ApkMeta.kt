package com.xixin.apkforge.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 未安装 APK 的「身份信息」：应用名、图标、包名、版本。
 *
 * 【为什么要读这几样】用户在列表里认包，靠的从来不是文件名。
 * `app-release-signed(1).apk` 和 `app-release-signed(2).apk` 长得一模一样，
 * 而应用名 + 图标 + 包名 + 版本 才是他真正在脑子里区分这两个包的东西。
 * 上一版只显示文件名，等于把「这是哪个包」的判断成本全推给用户。
 *
 * 【为什么用 PackageManager 而不是自己解 resources.arsc】
 * `getPackageArchiveInfo` + `loadIcon/loadLabel` 是系统标准路径：
 *   · 图标自带自适应/密度选择，不用自己处理 mipmap 多倍图；
 *   · 应用名直接取当前语言的最佳匹配，不用自己解析 strings pool 的 config；
 *   · 加固包/异常包返回 null，天然只有一条失败路径。
 * 自己解 arsc 要写几百行且必然在某些包上翻车——那是 apktool 的活，不是这里。
 */
object ApkMeta {

    data class Info(
        val label: String?,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        val icon: ImageBitmap?,
    ) {
        /** 列表里显示的名字：优先应用名，取不到退回文件名 */
        fun displayName(fallback: String): String = label?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** 同一路径的图标只解一次：列表滚动时反复 `loadIcon` 会明显掉帧。 */
    private val iconCache = HashMap<String, ImageBitmap?>()

    fun read(context: Context, apk: File): Info? {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return null

        // 未安装包的 icon 加载必须手动指路，否则 loadIcon 读到的是 null 资源
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }

        val icon = iconCache.getOrPut(apk.absolutePath) {
            runCatching {
                info.applicationInfo?.loadIcon(pm)?.toImageBitmap(96)
            }.getOrNull()
        }

        val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()

        return Info(
            label = label,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = runCatching { info.longVersionCode }.getOrDefault(0L),
            icon = icon,
        )
    }

    /**
     * Drawable → ImageBitmap。
     *
     * 不走 `drawable.toBitmap()`：它只对 BitmapDrawable 有效，自适应图标是
     * AdaptiveIconDrawable，会退回「按固有尺寸画」并可能在低密度机器上糊。
     * 这里固定渲染到 [size]×[size] 的正方形上，视觉大小一致、内存可控。
     */
    private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) {
            val src = bitmap
            if (src.width <= size && src.height <= size) return src.asImageBitmap()
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        setBounds(0, 0, size, size)
        draw(canvas)
        return out.asImageBitmap()
    }
}

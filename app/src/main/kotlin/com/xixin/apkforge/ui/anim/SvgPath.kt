package com.xixin.apkforge.ui.anim

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color

/**
 * 极简 SVG path 解析 + 描边动画。
 *
 * 【为什么手写解析而不是引库】androidx 没有 SVG path 解析器；
 * 引 `com.caverock:androidsvg`（~300KB）只为画一个加载动画不划算。
 * 这里只实现 `M m L l H h V v C c Q q Z z` 这几种指令，
 * 覆盖常见图标路径足够（也是各种 loader 动画实际用到的全部指令）。
 *
 * 解析结果是一个 Compose [Path]，再用 [PathMeasure] 做「描边生长」动画。
 */
object SvgPath {

    /**
     * 把 SVG path 的 `d` 属性解析成 Compose Path。
     *
     * @param d path 数据，坐标按 [viewBox] 的尺寸设计
     * @param viewBox 原始设计尺寸（宽, 高），用于缩放到目标画布
     * @param scale 目标缩放系数
     */
    fun parse(d: String, viewBox: Size, scale: Float, offset: Offset = Offset.Zero): Path {
        val path = Path()
        val tokens = tokenize(d)
        var i = 0

        // 当前点 / 起点 / 上一个控制点（用于 S/T 的反射，本实现未用到但保留结构）
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCmd = ' '

        fun sx(v: Float) = offset.x + v * scale
        fun sy(v: Float) = offset.y + v * scale

        fun readFloat(): Float = tokens[i++].toFloat()

        while (i < tokens.size) {
            val t = tokens[i]
            val cmd: Char
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
            } else {
                // 隐式重复上一条指令（SVG 允许省略重复的指令字母）
                cmd = when (lastCmd) {
                    'M' -> 'L'
                    'm' -> 'l'
                    else -> lastCmd
                }
            }
            lastCmd = cmd

            when (cmd) {
                'M' -> {
                    cx = readFloat(); cy = readFloat()
                    startX = cx; startY = cy
                    path.moveTo(sx(cx), sy(cy))
                }
                'm' -> {
                    cx += readFloat(); cy += readFloat()
                    startX = cx; startY = cy
                    path.moveTo(sx(cx), sy(cy))
                }
                'L' -> {
                    cx = readFloat(); cy = readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'l' -> {
                    cx += readFloat(); cy += readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'H' -> {
                    cx = readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'h' -> {
                    cx += readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'V' -> {
                    cy = readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'v' -> {
                    cy += readFloat()
                    path.lineTo(sx(cx), sy(cy))
                }
                'C' -> {
                    val x1 = readFloat(); val y1 = readFloat()
                    val x2 = readFloat(); val y2 = readFloat()
                    val x = readFloat(); val y = readFloat()
                    path.cubicTo(sx(x1), sy(y1), sx(x2), sy(y2), sx(x), sy(y))
                    cx = x; cy = y
                }
                'c' -> {
                    val x1 = cx + readFloat(); val y1 = cy + readFloat()
                    val x2 = cx + readFloat(); val y2 = cy + readFloat()
                    val x = cx + readFloat(); val y = cy + readFloat()
                    path.cubicTo(sx(x1), sy(y1), sx(x2), sy(y2), sx(x), sy(y))
                    cx = x; cy = y
                }
                'Q' -> {
                    val x1 = readFloat(); val y1 = readFloat()
                    val x = readFloat(); val y = readFloat()
                    path.quadraticTo(sx(x1), sy(y1), sx(x), sy(y))
                    cx = x; cy = y
                }
                'q' -> {
                    val x1 = cx + readFloat(); val y1 = cy + readFloat()
                    val x = cx + readFloat(); val y = cy + readFloat()
                    path.quadraticTo(sx(x1), sy(y1), sx(x), sy(y))
                    cx = x; cy = y
                }
                'Z', 'z' -> {
                    path.close()
                    cx = startX; cy = startY
                }
                else -> {
                    // 不支持的指令：跳过它的参数（尽量不炸）
                    i++
                }
            }
        }
        return path
    }

    /** 数字 / 指令字母 分词。处理 `-` 开头、`1.5.5` 这类紧凑写法。 */
    private fun tokenize(d: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                    out += c.toString()
                }
                c == ',' || c.isWhitespace() -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                }
                c == '-' || c == '+' -> {
                    // 负号是数字分隔符：1-2 表示 1 和 -2
                    if (sb.isNotEmpty() && sb.last() != 'e' && sb.last() != 'E') {
                        out += sb.toString(); sb.clear()
                    }
                    sb.append(c)
                }
                c == '.' -> {
                    // 1.5.5 → "1.5" 和 ".5"
                    if (sb.contains('.')) {
                        out += sb.toString(); sb.clear()
                    }
                    sb.append(c)
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * 按进度 [progress]（0..1）描出路径的一部分，实现「线条生长」效果。
     *
     * 注意 Compose 的 [PathMeasure] 与 android.graphics 版不同：
     * 它没有 `nextContour()`，`getSegment` 返回的是 Boolean（是否成功）而不是新 Path。
     * 所以这里只处理**单 contour** 路径——本项目所有 SVG 图形都是单段
     * （多段用 `a4,4 0 1,0` 画圆点那种也仍落在同一 contour 内），够用。
     */
    fun DrawScope.drawPartial(
        path: Path,
        progress: Float,
        color: Color,
        strokeWidth: Float,
        cap: StrokeCap = StrokeCap.Round,
    ) {
        if (progress <= 0f) return
        if (progress >= 1f) {
            drawPath(path, color, style = Stroke(strokeWidth, cap = cap))
            return
        }
        val measure = PathMeasure()
        measure.setPath(path, false)
        val total = measure.length
        if (total <= 0f) return

        val sub = Path()
        val ok = measure.getSegment(0f, total * progress, sub, true)
        if (ok) {
            drawPath(sub, color, style = Stroke(strokeWidth, cap = cap))
        }
    }
}

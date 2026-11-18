package com.xixin.apkforge.feature.console

import com.xixin.apkforge.core.apk.ApkInjectPipeline
import java.util.Locale

/**
 * 执行日志的**唯一**文本格式。
 *
 * 【为什么必须只有一处】
 * 同一份日志现在有两个去处：① 归档到 `records/logs/<id>.txt`，供事后回看；
 * ② 结果页上的「复制全文」。如果两处各拼一次，就会出现
 * 「页面上看到的和复制出去的差一个空格 / 差一个 ⚠」这种没法排查的小漂移。
 *
 * 因此这里只输出**行本体**，不带标题头：
 *  · 卡头已经有「执行日志 / N 行」，文件头再写一遍就是同一屏重复文本；
 *  · 源包、耗时、产物、时间这些元信息由 `TaskStore.Record` 持有，
 *    不该在日志文件里抄第二份（抄了就会不同步）。
 *
 * 行格式：`+1.24s  [阶段] 标签  细节`，warning 前面加 `! `。
 * 前导时间戳是给「贴给 AI 排查」用的——对方能据此判断卡在重打包还是解析就失败了。
 */
fun renderLog(steps: List<ApkInjectPipeline.Step>): String = buildString {
    steps.forEach { s ->
        append('+')
        append(String.format(Locale.US, "%.2fs", s.atMs / 1000.0))
        append("  [")
        append(s.stage.label)
        append("] ")
        if (s.warn) append("! ")
        append(s.label)
        if (s.detail.isNotBlank()) {
            append("  ")
            append(s.detail)
        }
        append('\n')
    }
}

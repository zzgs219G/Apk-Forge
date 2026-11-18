package com.xixin.apkforge.ui.screen.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.xixin.apkforge.data.TaskStore
import com.xixin.apkforge.feature.console.ConsoleUiState
import com.xixin.apkforge.feature.console.ConsoleViewModel
import com.xixin.apkforge.feature.console.renderLog

/**
 * 结果页的**唯一**入参。
 *
 * ## 为什么需要这一层
 *
 * 页面原先直接读 [ConsoleUiState]（`result` / `savedPath` / `steps`）。
 * 历史记录里没有这三样东西——它只有一条 [TaskStore.Record] 和一份归档日志。
 * 于是「点记录看日志」最省事的做法是另写一张页，但那是错误的方向：
 * 两张页会各自长出卡片、各自长出日志块，任何一处改版都要改两遍，
 * 而且用户看到的「刚跑完」和「三天后回看」会慢慢变成两个不同的产品。
 *
 * 正确做法是把「页面要显示什么」抽成一个值（就是本类），
 * 谁提供它、是刚跑的还是归档的，页面不需要知道。
 *
 * ## 两条数据源归一到同一形状
 *
 * | | [ConsoleUiState.toOutcome] | [rememberArchivedOutcome] |
 * |---|---|---|
 * | 日志 | 结构化 steps | 归档文本（读文件） |
 * | 落点 | 每个方法一条明细 | 只有方法名 + 签名 |
 * | 配置 / 源 APK | 不显示（可能已漂移） | 显示（记录里是快照） |
 * | 动作 | 安装 / 定位 / 分享 / 重跑 | 无（只读） |
 *
 * 「活的那一侧不显示配置名」是有意的：`activeConfigId` 在跑完之后仍可被用户改动，
 * 拿它回填结果页会显示一个**没被用过**的配置名。记录里的 `configName` 是写入时的
 * 快照，所以只有历史侧敢显示它。
 */
data class RunOutcome(
    /** true = 本次会话刚跑完（有动作）；false = 回看历史（只读） */
    val live: Boolean,
    val ok: Boolean,
    /** 结果卡副标题：成功 = 写了什么；失败 = 为什么 */
    val summary: String,
    /** 落点，每个方法一行（已格式化） */
    val targets: List<String>,
    val savedPath: String?,
    val addedDex: String?,
    val configName: String?,
    val apkName: String?,
    val elapsedMs: Long,
    val injectedCount: Int,
    val targetCount: Int,
    /** null = 不知道（产物已被移走 / 历史记录没存体积），此时不显示这一格 */
    val outputSize: Long?,
    /** 整段日志文本。活的那侧由 `renderLog()` 生成，历史那侧从归档文件读回。 */
    val logText: String,
)

/**
 * 活的那一侧：把一次运行的 `Result` 摊平成页面需要的形状。
 *
 * @return 还没跑过时返回 null（页面据此显示空态）
 */
fun ConsoleUiState.toOutcome(): RunOutcome? {
    val r = result ?: return null
    // 产物没保存成功就不算成功——一次「注入成功但没文件」对用户没有意义。
    val ok = r.ok && savedPath != null
    return RunOutcome(
        live = true,
        ok = ok,
        summary = if (ok) {
            r.targets.joinToString("、") { t ->
                if (t.createdMethod) "新建 ${t.methodName}" else "写入 ${t.methodName}"
            }.ifBlank { "调用已插入" }
        } else {
            r.error ?: "未知错误"
        },
        targets = r.targets.map { t ->
            "${t.methodName}${t.signature} · ${t.injectedCount} 条" +
                if (t.createdMethod) "（新建）" else ""
        },
        savedPath = savedPath,
        addedDex = r.stats?.addedDex,
        configName = null,
        apkName = null,
        elapsedMs = elapsedMs,
        injectedCount = r.injectedCount,
        targetCount = r.targets.size,
        outputSize = r.stats?.outputSize,
        logText = renderLog(steps),
    )
}

/**
 * 历史那一侧：读归档日志，再摊平成同一形状。
 *
 * 读文件放 [produceState] 而不是同步读：点开卡片的那一下必须立刻响应，
 * 而日志平均几 KB。首帧先给空字符串（卡头显示「0 行」），读完再替换。
 */
@Composable
fun rememberArchivedOutcome(
    vm: ConsoleViewModel,
    record: TaskStore.Record,
): RunOutcome {
    val log by produceState(initialValue = "", key1 = record.id) {
        value = vm.readLog(record.id).orEmpty()
    }
    return remember(record, log) { record.toOutcome(log) }
}

private fun TaskStore.Record.toOutcome(log: String): RunOutcome = RunOutcome(
    live = false,
    ok = ok,
    summary = if (ok) {
        targetsSummary.ifBlank { "调用已插入" }
    } else {
        // 老记录没有 error 字段，别假装知道原因——指向日志即可。
        error ?: "这次没有成功，原因见下方日志"
    },
    targets = targetNames.ifEmpty { listOfNotNull(targetMethod.takeIf { it.isNotBlank() }) }
        .map { "$it$targetSignature" },
    savedPath = outputPath,
    // 追加了哪个 dex 只存在于当次 Result 里，没进记录；历史侧不编造。
    addedDex = null,
    configName = configName,
    apkName = apkName,
    elapsedMs = durationMs,
    injectedCount = injectedCount,
    targetCount = targetNames.size.coerceAtLeast(1),
    outputSize = outputFile?.takeIf { it.isFile }?.length(),
    logText = log,
)

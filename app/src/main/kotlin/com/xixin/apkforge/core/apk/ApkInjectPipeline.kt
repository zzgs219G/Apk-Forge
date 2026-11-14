package com.xixin.apkforge.core.apk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.xixin.apkforge.core.axml.AxmlManifest
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.core.dex.DexCallInjector

/**
 * 注入流水线：APK → 解析 LAUNCHER → 定位 dex → 插调用（缺失则建方法）→ 追加 payload → 重打包。
 */
class ApkInjectPipeline(private val context: Context) {

    data class Input(
        val sourceApk: File,
        val outputApk: File,
        /** 注入点完整类名；留空则自动取 Manifest 的 LAUNCHER */
        val targetClass: String? = null,
        /** 注入点方法名，默认 onCreate；不存在时会新建 */
        val targetMethod: String = "onCreate",
        /** 注入点方法签名，默认 (Landroid/os/Bundle;)V */
        val targetSignature: String = "(Landroid/os/Bundle;)V",
        /** 用户粘贴的 smali 调用行（可多行、可含 # 注释） */
        val callsText: String,
        /** payload dex；null 表示不追加 */
        val payloadDex: File? = null,
    )

    data class Step(val label: String, val detail: String, val warn: Boolean = false)

    data class Result(
        val ok: Boolean,
        val steps: List<Step>,
        val error: String? = null,
        val outputApk: File? = null,
        val packageName: String? = null,
        val launcherFound: List<String> = emptyList(),
        val resolvedClass: String? = null,
        val createdMethod: Boolean = false,
        val injectedCount: Int = 0,
        val stats: ApkRepackager.Stats? = null,
    )

    /** 只解析 Manifest，用于「选完 APK 就自动填类名」。 */
    fun peekManifest(apk: File): AxmlManifest.Result? = try {
        ApkRepackager.readEntry(apk, MANIFEST)?.let { AxmlManifest.parseBytes(it) }
    } catch (_: Exception) {
        null
    }

    suspend fun run(input: Input, onStep: (Step) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val steps = mutableListOf<Step>()
            fun step(label: String, detail: String, warn: Boolean = false) {
                val s = Step(label, detail, warn)
                steps += s
                onStep(s)
            }

            try {
                require(input.sourceApk.isFile) { "APK 不存在" }

                // ── 1. Manifest ──
                step("解析 AndroidManifest", "读取二进制 AXML")
                val manifest = peekManifest(input.sourceApk)
                    ?: return@withContext Result(false, steps, "Manifest 解析失败或缺失")

                step(
                    "包信息",
                    "package=${manifest.packageName ?: "?"}  " +
                        "LAUNCHER ${manifest.launcherActivities.size} 个"
                )

                // ── 2. 注入点 ──
                val resolved = input.targetClass?.trim()?.takeIf { it.isNotEmpty() }
                    ?: manifest.primaryLauncher
                    ?: return@withContext Result(
                        false, steps,
                        "没填类名，且 Manifest 里找不到 LAUNCHER activity。请手动填写完整类名"
                    )
                val src = if (!input.targetClass.isNullOrBlank()) "用户指定" else "自动取自 LAUNCHER"
                step("注入点", "$resolved（$src）")

                // ── 3. 解析调用 ──
                val (calls, errors) = SmaliCallParser.parseAll(input.callsText)
                if (!errors.isEmpty) {
                    return@withContext Result(
                        false, steps,
                        "调用解析失败：\n" + errors.messages.joinToString("\n")
                    )
                }
                if (calls.isEmpty()) {
                    return@withContext Result(false, steps, "没有解析出任何调用，请粘贴 invoke 行")
                }
                step("解析调用", "${calls.size} 条")

                // ── 4. 定位 dex ──
                val dexNames = ApkRepackager.listDexNames(input.sourceApk)
                if (dexNames.isEmpty()) {
                    return@withContext Result(false, steps, "APK 内没有 dex")
                }
                step("扫描 dex", dexNames.joinToString())

                val ownerType = "L${resolved.replace('.', '/')};"
                var hitDex: String? = null
                for (name in dexNames) {
                    val bytes = ApkRepackager.readEntry(input.sourceApk, name) ?: continue
                    if (DexCallInjector.containsClass(bytes, ownerType)) {
                        hitDex = name
                        break
                    }
                }
                if (hitDex == null) {
                    return@withContext Result(
                        false, steps,
                        "所有 dex 里都没有类 $resolved。请确认类名完整（含包名）"
                    )
                }
                step("定位 dex", "目标类在 $hitDex")

                // ── 5. 注入 ──
                val targetDexBytes = ApkRepackager.readEntry(input.sourceApk, hitDex)!!
                val spec = DexCallInjector.Spec(
                    ownerType = ownerType,
                    methodName = input.targetMethod,
                    methodSignature = input.targetSignature,
                    calls = calls,
                )
                val newDexBytes = when (val o = DexCallInjector.inject(targetDexBytes, spec)) {
                    is DexCallInjector.Outcome.Injected -> {
                        step(
                            "注入调用",
                            if (o.createdMethod) {
                                "方法 ${input.targetMethod}${input.targetSignature} 不存在，已新建" +
                                    "（含父类调用），插入 ${o.injectedCount} 条调用"
                            } else {
                                "在 ${input.targetMethod} 开头插入 ${o.injectedCount} 条调用"
                            }
                        )
                        o.warnings.forEach { step("警告", it, warn = true) }
                        o.dex
                    }
                    is DexCallInjector.Outcome.Skipped ->
                        return@withContext Result(false, steps, "注入跳过: ${o.reason}")
                    is DexCallInjector.Outcome.Failed ->
                        return@withContext Result(false, steps, "注入失败: ${o.reason}")
                }

                // ── 6. payload dex ──
                val payloadBytes = input.payloadDex?.let { f ->
                    require(f.isFile) { "payload dex 不存在" }
                    val raw = f.readBytes()
                    require(
                        raw.size > 8 &&
                            raw[0] == 'd'.code.toByte() && raw[1] == 'e'.code.toByte() &&
                            raw[2] == 'x'.code.toByte() && raw[3] == '\n'.code.toByte()
                    ) { "payload 不是合法 dex（缺少 dex\\n 魔数）" }
                    step("读取 payload", "${f.name}（${raw.size / 1024} KB）")
                    raw
                }

                // ── 7. 重打包 ──
                step("重打包", "替换 $hitDex" + if (payloadBytes != null) "，追加 payload" else "")
                val stats = ApkRepackager.repack(
                    sourceApk = input.sourceApk,
                    outputApk = input.outputApk,
                    targetDexName = hitDex,
                    newDexBytes = newDexBytes,
                    payloadDexBytes = payloadBytes,
                )
                step(
                    "完成",
                    "${input.outputApk.name}，${stats.totalEntries} 条目，" +
                        "${stats.originalSize / 1024 / 1024} MB → ${stats.outputSize / 1024 / 1024} MB"
                )

                Result(
                    ok = true,
                    steps = steps,
                    outputApk = input.outputApk,
                    packageName = manifest.packageName,
                    launcherFound = manifest.launcherActivities,
                    resolvedClass = resolved,
                    stats = stats,
                    injectedCount = input.callsText.lineSequence()
                        .count { it.substringBefore('#').isNotBlank() },
                )
            } catch (e: Exception) {
                Result(false, steps, e.message ?: e.javaClass.simpleName)
            }
        }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
    }
}

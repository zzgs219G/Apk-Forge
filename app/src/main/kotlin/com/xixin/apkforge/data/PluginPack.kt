package com.xixin.apkforge.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 插件包：一份「弹窗载荷 dex + 调用行」的可移植两件套。
 *
 * 【为什么只有两件，没有 json】
 * 上一版是「dex + assets/apkforge_dialog.json + call.txt」三件套，配置靠资产文件
 * 跟着 dex 一起进宿主。这带来三个真问题：
 *   1. 注入产物凭空多一个文件。用户的包是他自己的，往里面塞配置不是我们的权利；
 *   2. 配置有两条通道，任一条没写进去就是「注入成功、不弹窗」——**不报错**；
 *   3. 导出的 zip 里带着 json，导回来时又要把表单和 json 对齐，两处默认值必然漂。
 * 现在配置**烘焙进 dex**（[com.xixin.apkforge.core.dex.PayloadBaker]），
 * zip 里只有 dex 与调用行，产物里只有 `classesN.dex`。
 *
 * 【代价，说清楚】改文案要重新烘焙 + 重新注入，不能像资产那样事后用 MT 改。
 * 这是刻意的取舍：本地弹窗是「一次性投放」，改文案的频率远低于「产物干净」的价值。
 *
 * 【弹窗能力对齐清羽弹窗 7.0】字段名沿用它的，老配置能直接粘进来：
 * 总开关 / 智能弹出 / 静默天数 / 版本 / 强制性 / 点击外部关闭弹窗 /
 * 弹窗风格 / 外部背景透明 / 标题 / 颜色 / 信息 / 三个按钮（开关 + 文本 + 颜色 + 事件）。
 */
object PluginPack {

    const val DEX_ASSET = "plugin/PayloadDialog.dex"
    const val DEX_NAME = "PayloadDialog.dex"
    const val CALL_NAME = "call.txt"
    const val ZIP_NAME = "apkforge-plugin.zip"

    /** 调用行。与 dex 里的 `PayloadDialog.inject(Activity)` 逐字对应。 */
    const val CALL =
        "invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V"

    /** 弹窗风格：与载荷 `PayloadUi` 的 switch 一一对应。 */
    val STYLES = listOf("默认暗", "默认亮", "HOLO暗", "HOLO亮", "传统")

    /**
     * 按钮动作。值必须与载荷 `PayloadConfig.ACTION_ALIAS` 认识的动作名一致，
     * 否则会被当成「无」——静默失效，看不出来。
     */
    val ACTIONS = listOf(
        "无" to "仅关闭",
        "网址" to "打开网页",
        "退出" to "退出应用",
        "提示" to "只弹提示",
        "智能" to "记住版本",
        "QQ名片" to "QQ 名片",
        "QQ群" to "QQ 群",
        "QQ咨询" to "QQ 咨询",
    )

    /** 一个按钮的完整形态。位置固定：0=左（同意）1=右（拒绝）2=中（中立）。 */
    data class Button(
        val slot: Int = 0,
        val on: Boolean = true,
        val text: String = "知道了",
        val color: String = "",
        val action: String = "无",
        /** 网址写这里；QQ 类动作写 QQ 号（与 7.0 的键名「QQ号」对应） */
        val arg: String = "",
        val toast: String = "",
    ) {
        val actionLabel: String
            get() = ACTIONS.firstOrNull { it.first == action }?.second ?: "仅关闭"

        val needsArg: Boolean get() = action == "网址" || action.startsWith("QQ")
    }

    /** 弹窗配置的**表单形态**。字段比上一版多得多，因为要复刻 7.0 的整套能力。 */
    data class DialogForm(
        val enabled: Boolean = true,
        val smart: Boolean = false,
        /** 静默天数，0 = 关 */
        val silentDays: Int = 0,
        val version: String = "1.0",
        val forced: Boolean = false,
        val outsideClose: Boolean = false,
        val style: String = "默认暗",
        val dimBehind: Boolean = false,

        val title: String = "ApkForge 提示",
        val titleColor: String = "#4da3ff",
        val message: String = "这段弹窗由 ApkForge 注入，配置已烘焙进 dex。",
        val messageColor: String = "#c8c8c8",

        val buttons: List<Button> = DEFAULT_BUTTONS,
    ) {
        /** 标题和正文都空 = 弹出来一个空白框，没有意义。 */
        val valid: Boolean get() = title.isNotBlank() || message.isNotBlank()

        /** 三个按钮全关 = 用户关不掉的死弹窗，载荷会跳过显示。UI 必须提前拦住。 */
        val anyButtonOn: Boolean get() = buttons.any { it.on }

        /** 需要填参数却没填的按钮（网址没网址、QQ 没号）。 */
        val badButtons: List<Button>
            get() = buttons.filter { it.on && it.needsArg && it.arg.isBlank() }
    }

    val DEFAULT_BUTTONS = listOf(
        Button(slot = 0, text = "知道了", action = "无"),
        Button(
            slot = 1,
            text = "查看项目",
            action = "网址",
            arg = "https://cnb.cool/zzgs219/Apk-Forge",
            toast = "正在打开浏览器",
        ),
        Button(slot = 2, on = false, text = "加群反馈", action = "QQ群", arg = ""),
    )

    /** 按钮槽位的展示名。 */
    fun slotLabel(slot: Int): String = when (slot) {
        0 -> "左（同意）"
        1 -> "右（拒绝）"
        else -> "中（中立）"
    }

    // ────────────────────── 表单 ↔ JSON ──────────────────────

    /**
     * 表单 → 要烘焙进 dex 的 JSON（含注释）。
     *
     * 注释留着：这份文本最终会被人看到（App 里可预览、可复制），
     * 带上取值说明比只给一个裸 JSON 有用。载荷解析时会先去掉注释。
     */
    fun toJson(form: DialogForm): String {
        val obj = JSONObject()
        obj.put("总开关", onOff(form.enabled))
        obj.put("智能弹出", onOff(form.smart))
        obj.put("静默天数", if (form.silentDays > 0) form.silentDays.toString() else "关")
        obj.put("版本", form.version)
        obj.put("强制性", onOff(form.forced))
        obj.put("点击外部关闭弹窗", onOff(form.outsideClose))
        obj.put("弹窗风格", form.style)
        obj.put("外部背景透明", onOff(form.dimBehind))
        obj.put("标题", form.title)
        obj.put("标题颜色", form.titleColor)
        // 正文按行存成数组：与 7.0 的写法一致，解析端会拼成 <br> 分隔
        obj.put("信息", JSONArray(form.message.split('\n').filter { it.isNotEmpty() }))
        obj.put("信息颜色", form.messageColor)

        val names = arrayOf("同意", "拒绝", "中立")
        form.buttons.sortedBy { it.slot }.forEach { b ->
            val n = names[b.slot.coerceIn(0, 2)]
            obj.put("${n}按钮", onOff(b.on))
            obj.put("${n}按钮文本", b.text)
            if (b.color.isNotBlank()) obj.put("${n}按钮颜色", b.color)
            val ev = JSONObject().put("动作", b.action)
            if (b.action == "网址") ev.put("网址", b.arg)
            else if (b.action.startsWith("QQ")) ev.put("QQ号", b.arg.trim())
            if (b.toast.isNotBlank()) ev.put("提示", b.toast)
            obj.put("${n}按钮事件", ev)
        }
        // 三个槽即便关着也写出来：解析端按「开/关」判定，缺键等于关，
        // 但留着能让用户在预览里看到「这个位置是关的」，不至于以为丢了配置。
        (0..2).filter { slot -> form.buttons.none { it.slot == slot } }.forEach { slot ->
            val n = names[slot]
            obj.put("${n}按钮", "关")
            obj.put("${n}按钮文本", "")
            obj.put("${n}按钮事件", JSONObject().put("动作", "无"))
        }

        return """
            |/* ApkForge 本地弹窗配置（已烘焙进 dex，产物里不会有这个文件）
            | *
            | * 开关只认「开」「关」；颜色写 #rrggbb，写错退回宿主主题色。
            | * 动作：无 / 网址 / 退出 / 提示 / 智能 / QQ名片 / QQ群 / QQ咨询
            | * 静默天数：填正整数 N = 安装后前 N 天不弹；「关」= 不启用
            | */
            |${obj.toString(2)}
        """.trimMargin()
    }

    /** JSON（可带注释）→ 表单。解析失败返回默认表单，不抛给 UI。 */
    fun fromJson(text: String): DialogForm = runCatching {
        val o = JSONObject(stripComments(text))
        val names = arrayOf("同意", "拒绝", "中立")
        val buttons = (0..2).mapNotNull { slot ->
            val n = names[slot]
            if (!o.has("${n}按钮") && !o.has("${n}按钮文本")) return@mapNotNull null
            val ev = o.optJSONObject("${n}按钮事件")
            val action = normalizeAction(ev?.optString("动作", "无").orEmpty())
            val arg = when {
                action == "网址" -> ev?.optString("网址", "")?.ifBlank {
                    ev.optString("参数", "")
                }.orEmpty()
                action.startsWith("QQ") -> ev?.optString("QQ号", "")?.ifBlank {
                    ev.optString("参数", "")
                }.orEmpty()
                else -> ""
            }
            Button(
                slot = slot,
                on = o.optString("${n}按钮", "关") == "开",
                text = o.optString("${n}按钮文本", ""),
                color = o.optString("${n}按钮颜色", ""),
                action = action,
                arg = arg,
                toast = ev?.optString("提示", "").orEmpty(),
            )
        }
        val msgRaw = o.opt("信息")
        val message = when (msgRaw) {
            is JSONArray -> (0 until msgRaw.length()).joinToString("\n") { msgRaw.optString(it) }
            null -> ""
            else -> msgRaw.toString()
        }
        DialogForm(
            enabled = o.optString("总开关", "开") != "关",
            smart = o.optString("智能弹出", "关") == "开",
            silentDays = o.optString("静默天数", "关").trim().toIntOrNull() ?: 0,
            version = o.optString("版本", "1.0"),
            forced = o.optString("强制性", "关") == "开",
            outsideClose = o.optString("点击外部关闭弹窗", "关") == "开",
            style = o.optString("弹窗风格", "默认暗").let { s ->
                // 7.0 的写法是「默认白/默认黑」，本工程用「默认亮/默认暗」；
                // 两个都认，老配置直接粘进来才不会风格全丢。
                when (s) {
                    "默认白" -> "默认亮"
                    "默认黑" -> "默认暗"
                    "HOLO白" -> "HOLO亮"
                    "HOLO黑" -> "HOLO暗"
                    else -> s
                }
            }.takeIf { STYLES.contains(it) } ?: "默认暗",
            dimBehind = o.optString("外部背景透明", "关") == "开",
            title = o.optString("标题", ""),
            titleColor = o.optString("标题颜色", ""),
            message = message,
            messageColor = o.optString("信息颜色", ""),
            buttons = buttons.ifEmpty { DEFAULT_BUTTONS },
        )
    }.getOrDefault(DialogForm())

    /** 把 7.0 的动作名归一化到本工程的值（未知的一律「无」，绝不猜）。 */
    private fun normalizeAction(raw: String): String {
        val v = raw.trim()
        return when (v) {
            "打开网页", "打开链接", "网址" -> "网址"
            "关闭应用", "退出", "退出应用" -> "退出"
            "记住版本", "智能" -> "智能"
            "仅提示", "提示" -> "提示"
            "QQ名片", "QQ群", "QQ咨询" -> v
            else -> "无"
        }
    }

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    /** 去 JSONC 注释（区分字符串内外）。与载荷里的实现保持同一语义。 */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var inStr = false
        var esc = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                out.append(ch)
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
                i++
                continue
            }
            if (ch == '"') {
                inStr = true
                out.append(ch)
                i++
                continue
            }
            if (ch == '/' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '/' -> {
                        while (i < text.length && text[i] != '\n') i++
                        out.append('\n')
                        continue
                    }
                    '*' -> {
                        i += 2
                        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i += 2
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    // ────────────────────── 打包 / 解开 ──────────────────────

    /** 从 assets 读出内置载荷 dex（模板版，配置是占位符）。 */
    fun bundledDex(context: Context): ByteArray =
        context.assets.open(DEX_ASSET).use { it.readBytes() }

    /** 打一个插件包 zip：只放 dex 与调用行，配置在 dex 里。 */
    fun buildZip(dex: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            // 固定时间戳：同样的输入产出同样的字节，便于比对
            z.putNextEntry(ZipEntry(DEX_NAME).apply { time = 0L })
            z.write(dex)
            z.closeEntry()

            z.putNextEntry(ZipEntry(CALL_NAME).apply { time = 0L })
            z.write(CALL.toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    /** 解开插件包。缺 dex 视为无效包；配置从 dex 里读回来（读不到就用默认表单）。 */
    fun readZip(bytes: ByteArray): Result? {
        var dex: ByteArray? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val data = z.readBytes()
                if (e.name.substringAfterLast('/') == DEX_NAME) dex = data
                z.closeEntry()
            }
        }
        val d = dex ?: return null
        val text = com.xixin.apkforge.core.dex.PayloadBaker.extract(d)
        return Result(d, text?.let { fromJson(it) } ?: DialogForm())
    }

    data class Result(val dex: ByteArray, val form: DialogForm)

    /** dex 是否是合法 dex（只看魔数，够用且不会误判）。 */
    fun isDex(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() &&
            b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

    /** 把烘焙好的 dex 落盘，返回文件供 ConfigStore 引用。 */
    fun exportDex(dir: File, dex: ByteArray): File {
        dir.mkdirs()
        return File(dir, DEX_NAME).apply { writeBytes(dex) }
    }
}

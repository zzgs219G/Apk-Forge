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
 * 插件包：一份「弹窗载荷 dex + 配置 + 调用行」的可移植三件套。
 *
 * 【为什么是一个 zip 而不是三段散装内容】用户要把它带到别的手机、发给别人、
 * 或者在 MT 里手工塞进 APK。三件套散着发必然丢一件；打成一个 zip，
 * `PayloadDialog.dex` / `ApkForgeDialog.json` / `call.txt` 的关系就固定下来了。
 *
 * 【为什么配置是 assets 而不是 dex 里的常量】改一句文案不该要求重新编译 dex。
 * 配置作为资产写进宿主的 `assets/apkforge_dialog.json`，用户用 MT 就能改，
 * 改完不需要重新注入。
 *
 * 【为什么模板留在代码里而不是 assets】模板要参与「表单 ↔ JSON」的双向转换，
 * 它是一份**结构**而不是一份文件。放 assets 里只能整体读出来，改一个字段还得
 * 字符串替换，反而更脆。
 */
object PluginPack {

    const val DEX_ASSET = "plugin/PayloadDialog.dex"
    const val DEX_NAME = "PayloadDialog.dex"
    /** 插件包 zip 里配置的文件名（给人看的，大小写随习惯） */
    const val CONFIG_NAME = "ApkForgeDialog.json"

    /**
     * 载荷在宿主 assets 下找的那个文件名。
     *
     * ⚠️ 必须与 `PayloadDialog.ASSET_NAME` 逐字一致（小写）。两者分居 Java/Kotlin
     * 两个模块，靠人记住一定会漂，所以 `PluginPayloadTest` 钉住了这条等式。
     */
    const val PAYLOAD_ASSET_NAME = "apkforge_dialog.json"
    const val CALL_NAME = "call.txt"
    const val ZIP_NAME = "apkforge-plugin.zip"

    /** 写进宿主的资产路径。必须与 `PayloadDialog.ASSET_NAME` 一字不差。 */
    const val HOST_ASSET_PATH = "assets/$PAYLOAD_ASSET_NAME"

    /** 调用行。与 dex 里的 `PayloadDialog.inject(Activity)` 逐字对应。 */
    const val CALL =
        "invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V"

    /**
     * 弹窗配置的**表单形态**。
     *
     * 刻意只覆盖「用户真会改的东西」：标题、正文、三个按钮的文字与跳转。
     * 颜色、静默期、智能弹出这些是给库作者调的，摆在表单里只会让人误以为必须填。
     */
    data class DialogForm(
        val title: String = "ApkForge 提示",
        val message: String = "这段弹窗由 ApkForge 注入。",
        val titleColor: String = "#4da3ff",
        val messageColor: String = "#c8c8c8",
        val cancelable: Boolean = false,
        val enabled: Boolean = true,
        val buttons: List<DialogButton> = DEFAULT_BUTTONS,
    ) {
        /** 至少要有可关闭的路径：全关掉会造出一个「点了没反应、也关不掉」的弹窗。 */
        val valid: Boolean get() = title.isNotBlank() || message.isNotBlank()
    }

    data class DialogButton(
        /** 0=左 1=右 2=中 */
        val slot: Int = 0,
        val text: String = "知道了",
        /** none / url / exit / custom */
        val action: String = "none",
        val arg: String = "",
        val toast: String = "",
    ) {
        val actionLabel: String
            get() = when (action) {
                "url" -> "打开网页"
                "exit" -> "退出应用"
                "custom" -> "自定义跳转"
                else -> "仅关闭"
            }
    }

    val DEFAULT_BUTTONS = listOf(
        DialogButton(slot = 0, text = "知道了", action = "none"),
        DialogButton(
            slot = 1,
            text = "查看项目",
            action = "url",
            arg = "https://cnb.cool/zzgs219/Apk-Forge",
            toast = "正在打开浏览器",
        ),
    )

    /** 动作白名单。载荷 dex 只认这几个，UI 也只给这几个选项。 */
    val ACTIONS = listOf("none" to "仅关闭", "url" to "打开网页", "exit" to "退出应用", "custom" to "自定义跳转")

    // ────────────────────── 表单 ↔ JSON ──────────────────────

    /**
     * 表单 → JSONC 文本。
     *
     * 输出**带注释**：这份文件最终会落在宿主的 assets 里，用户很可能会去改它。
     * 一份裸 JSON 会让他猜「动作」能填什么；带注释的 JSONC 直接告诉他。
     */
    fun toJsonc(form: DialogForm): String {
        val arr = JSONArray()
        form.buttons.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("位置", b.slot)
                    put("文本", b.text)
                    put("动作", b.action)
                    if (b.arg.isNotBlank()) put("参数", b.arg)
                    if (b.toast.isNotBlank()) put("提示", b.toast)
                }
            )
        }
        val obj = JSONObject().apply {
            put("总开关", if (form.enabled) "开" else "关")
            put("点击外部关闭", if (form.cancelable) "开" else "关")
            put("标题", form.title)
            put("标题颜色", form.titleColor)
            put("信息", form.message)
            put("信息颜色", form.messageColor)
            put("按钮", arr)
        }
        // JSONObject.toString(2) 已是缩进格式，这里补一段头部说明
        return """
            |// ApkForge 本地弹窗配置
            |// 这份文件会被放进宿主的 assets/apkforge_dialog.json，载荷只读它、不联网。
            |//
            |// 开关只有「开」「关」；颜色写 #rrggbb 或 #aarrggbb，写错会被忽略。
            |// 动作取值：none 仅关闭 / url 打开网页（参数=网址）/ exit 退出宿主 /
            |//           custom 自定义 scheme（参数=完整 URI）
            |// 位置：0=左 1=右 2=中
            |
            |${obj.toString(2)}
        """.trimMargin()
    }

    /** JSON(C) → 表单。解析失败时返回默认表单，绝不抛给 UI。 */
    fun fromJsonc(text: String): DialogForm = runCatching {
        val o = JSONObject(stripComments(text))
        val arr = o.optJSONArray("按钮")
        val buttons = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    add(
                        DialogButton(
                            slot = b.optInt("位置", i),
                            text = b.optString("文本", ""),
                            action = b.optString("动作", "none"),
                            arg = b.optString("参数", ""),
                            toast = b.optString("提示", ""),
                        )
                    )
                }
            }
        }.ifEmpty { DEFAULT_BUTTONS }
        DialogForm(
            title = o.optString("标题", ""),
            message = o.optString("信息", ""),
            titleColor = o.optString("标题颜色", "#4da3ff"),
            messageColor = o.optString("信息颜色", "#c8c8c8"),
            cancelable = o.optString("点击外部关闭", "关") == "开",
            enabled = o.optString("总开关", "开") != "关",
            buttons = buttons,
        )
    }.getOrDefault(DialogForm())

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

    /** 从 assets 读出内置载荷 dex。 */
    fun bundledDex(context: Context): ByteArray =
        context.assets.open(DEX_ASSET).use { it.readBytes() }

    /** 打一个插件包 zip。 */
    fun buildZip(dex: ByteArray, configJsonc: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            // 固定时间戳：同样的输入产出同样的字节，便于比对/校验
            z.putNextEntry(ZipEntry(DEX_NAME).apply { time = 0L })
            z.write(dex)
            z.closeEntry()

            z.putNextEntry(ZipEntry(CONFIG_NAME).apply { time = 0L })
            z.write(configJsonc.toByteArray())
            z.closeEntry()

            z.putNextEntry(ZipEntry(CALL_NAME).apply { time = 0L })
            z.write(CALL.toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    /** 解开插件包。缺 dex 视为无效包；缺配置则用默认表单兜底。 */
    fun readZip(bytes: ByteArray): Result? {
        var dex: ByteArray? = null
        var config: String? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val data = z.readBytes()
                when (e.name.substringAfterLast('/')) {
                    DEX_NAME -> dex = data
                    CONFIG_NAME -> config = String(data)
                }
                z.closeEntry()
            }
        }
        val d = dex ?: return null
        return Result(d, config?.let { fromJsonc(it) } ?: DialogForm())
    }

    data class Result(val dex: ByteArray, val form: DialogForm)

    /** dex 是否是合法 dex（只看魔数，够用且不会误判）。 */
    fun isDex(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() &&
            b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

    /** 把 dex/配置落盘到私有目录，返回路径供 ConfigStore 引用。 */
    fun exportTo(context: Context, dir: File, dex: ByteArray, configJsonc: String): Pair<File, File> {
        dir.mkdirs()
        val dexFile = File(dir, DEX_NAME).apply { writeBytes(dex) }
        val cfgFile = File(dir, CONFIG_NAME).apply { writeText(configJsonc) }
        return dexFile to cfgFile
    }
}


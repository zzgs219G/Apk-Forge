package com.xixin.apkforge.core.axml

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 二进制 AndroidManifest.xml（AXML）解析器，只做一件事：
 * 找出带 `android.intent.action.MAIN` + `android.intent.category.LAUNCHER` 的
 * 启动组件，返回**真实存在且可注入的 Activity 类名**（如 `com.foo.MainActivity`）。
 *
 * 【`<activity-alias>` 必须换算成 `targetActivity`】
 * 「换图标」类功能（MT 管理器、许多国产 App 的「暗色/无背景图标」）的典型写法是：
 * 声明一个真正的 `<activity android:name="bin.mt.plus.Main">`（不带 LAUNCHER），
 * 再挂若干个 `<activity-alias android:name="...NoBgIcon" targetActivity="...Main">`，
 * 每个 alias 各带一份 MAIN+LAUNCHER，靠 `android:enabled` 开关决定当前用哪个图标。
 *
 * 此时 `activity-alias` 的 `android:name` **不是类名**，只是清单里的别名，
 * dex 里没有这个类。若把它当注入点填进 UI，注入会报「包里找不到这个类」——
 * 而这正是旧实现的错法：它把 alias 的 name 直接当成 launcher 返回了。
 * 真实落点只能是 `android:targetActivity` 指向的那个类。
 *
 * 【`android:enabled="false"` 的组件必须跳过】
 * 同一套「换图标」里通常并排声明多个 alias，只有一个是 enabled 的。
 * 未启用的组件不会出现在启动器里，也就不会是用户点开的那个入口。
 * 旧实现不看 enabled，取 `first()` 于是稳定地拿到清单里第一个——恰好是禁用项。
 *
 * 【为什么只能在同一 `<intent-filter>` 内配对 MAIN 与 LAUNCHER】
 * Android 的判定是「同一个 filter 里同时声明 MAIN 和 LAUNCHER」才算启动入口。
 * 旧实现按元素出现顺序跨 filter 累积两个布尔值，会把
 * 「filter A 有 MAIN、filter B 有 LAUNCHER」这种组件误判成启动入口——
 * 而它在启动器里根本不出现。故改为每进入一层 `intent-filter` 重置配对状态。
 *
 * 【为什么不引依赖】可选方案是 net.dongliu:apk-parser（拉 bcprov/bcpkix 1.58）
 * 或 JitPack 上的 axml（仓库 401 未验证）。而本项目只需要「读」，不需要「写回」，
 * 手写 AXML 解析约 200 行且零依赖，比背两个大依赖划算。
 *
 * 【为什么不解码成文本 Manifest】apktool 的教训（artifact-pipeline 报错四/五）：
 * 文本 Manifest 用 `apktool b -r` 装回包会产出 MT/系统都不认的「灰包」。
 * 本类全程只读字节，绝不产生文本 Manifest，回包时 Manifest 原样二进制拷贝。
 *
 * AXML 结构：
 * ```
 * RES_XML_TYPE(0x0003) 头
 *   ├─ RES_STRING_POOL_TYPE(0x0001)  字符串池
 *   ├─ RES_XML_RESOURCE_MAP_TYPE(0x0180)  属性名→资源 id 映射
 *   ├─ RES_XML_START_NAMESPACE_TYPE(0x0100)
 *   ├─ RES_XML_START_ELEMENT_TYPE(0x0102)  ← 靠它拿元素/属性
 *   ├─ RES_XML_END_ELEMENT_TYPE(0x0103)
 *   └─ RES_XML_END_NAMESPACE_TYPE(0x0101)
 * ```
 * 每个 chunk 头统一为：type(u16) headerSize(u16) size(u32)。
 */
object AxmlManifest {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * 解析结果。
     *
     * [launcherActivities] 里**只会是真正的 Activity 类名**——
     * `activity-alias` 已经被换算成它的 `targetActivity`。可能为空
     * （包内只有一个 Application，或确实没有 LAUNCHER）。
     */
    data class Result(
        val packageName: String?,
        val launcherActivities: List<String>,
        val applicationName: String?,
        /**
         * 被换算掉的 alias 名 → 真实类名。仅用于日志/说明，
         * 让用户知道「你看到的入口名」与「实际注入的类」不是一回事。
         */
        val aliasMap: Map<String, String> = emptyMap(),
    ) {
        val primaryLauncher: String? get() = launcherActivities.firstOrNull()
    }

    fun parse(file: File): Result = file.inputStream().use { parseBytes(it.readBytes()) }

    fun parseBytes(bytes: ByteArray): Result {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // 文件级头：type(2) + headerSize(2) + size(4)，AXML 固定 0x0003
        val magic = buf.getShort(0).toInt() and 0xFFFF
        require(magic == 0x0003) { "不是合法的二进制 AXML（magic=0x${magic.toString(16)}）" }

        var pos = buf.getShort(2).toInt() and 0xFFFF  // headerSize，正常为 8
        if (pos <= 0) pos = 8

        var strings: List<String> = emptyList()

        // 元素栈：用来把 <activity> 与嵌套的 <intent-filter>/<action>/<category> 关联起来。
        // 只需要记住最近一次 START 的组件名、它是不是 disabled、以及当前 filter 的配对状况。
        val launchers = ArrayList<String>()
        val aliasMap = LinkedHashMap<String, String>()
        var packageName: String? = null
        var applicationName: String? = null

        var inActivityDepth = -1          // >0 表示当前在 activity / activity-alias 内部
        var currentActivityName: String? = null
        var currentTargetActivity: String? = null   // activity-alias 的 targetActivity
        var currentEnabled = true                    // android:enabled="false" 时跳过
        var currentActivityHasMain = false
        var currentActivityHasLauncher = false
        // 进入 <intent-filter> 时的 depth；-1 表示当前不在 filter 内。
        // MAIN 与 LAUNCHER 必须在**同一个** filter 里才算启动入口，故每进一层 filter
        // 重置配对状态——旧实现跨 filter 累积，会把「A 有 MAIN、B 有 LAUNCHER」误判成入口。
        var inFilterDepth = -1
        var depth = 0

        while (pos + 8 <= buf.limit()) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > buf.limit()) break

            when (type) {
                CHUNK_STRING_POOL -> strings = readStringPool(buf, pos, headerSize, chunkSize)

                CHUNK_START_ELEMENT -> {
                    depth++
                    val el = readStartElement(buf, pos, headerSize, strings)
                    when (el.name) {
                        "manifest" -> packageName = el.attrs["package"]
                        "application" -> applicationName = el.attrs["name"]
                        "activity", "activity-alias" -> {
                            inActivityDepth = depth
                            currentActivityName = el.attrs["name"]
                            currentTargetActivity = el.attrs["targetActivity"]
                            // 只有显式 false 才算禁用；缺省 / 资源引用一律视为启用。
                            currentEnabled = el.attrs["enabled"] != "false"
                            currentActivityHasMain = false
                            currentActivityHasLauncher = false
                            inFilterDepth = -1
                        }
                        "intent-filter" -> {
                            if (inActivityDepth > 0 && depth > inActivityDepth) {
                                inFilterDepth = depth
                                currentActivityHasMain = false
                                currentActivityHasLauncher = false
                            }
                        }
                        "action" -> {
                            if (inActivityDepth > 0 && inFilterDepth > 0 &&
                                el.attrs["name"] == "android.intent.action.MAIN"
                            ) {
                                currentActivityHasMain = true
                            }
                        }
                        "category" -> {
                            if (inActivityDepth > 0 && inFilterDepth > 0 &&
                                el.attrs["name"] == "android.intent.category.LAUNCHER"
                            ) {
                                currentActivityHasLauncher = true
                            }
                        }
                    }
                }

                CHUNK_END_ELEMENT -> {
                    val name = readEndElementName(buf, pos, headerSize, strings)
                    if (name == "intent-filter" && depth == inFilterDepth) {
                        inFilterDepth = -1
                    }
                    if ((name == "activity" || name == "activity-alias") && depth == inActivityDepth) {
                        val n = currentActivityName
                        if (!n.isNullOrBlank() && currentEnabled &&
                            currentActivityHasMain && currentActivityHasLauncher
                        ) {
                            // activity-alias 的 android:name 只是清单别名，dex 里没有这个类。
                            // 真实落点必须是 targetActivity 指向的那个 Activity。
                            val target = currentTargetActivity
                            if (name == "activity-alias" && !target.isNullOrBlank()) {
                                val real = normalizeClassName(target, packageName)
                                launchers += real
                                aliasMap[normalizeClassName(n, packageName)] = real
                            } else {
                                // 相对类名（".MainActivity"）补全成完整类名
                                launchers += normalizeClassName(n, packageName)
                            }
                        }
                        inActivityDepth = -1
                        inFilterDepth = -1
                        currentActivityName = null
                        currentTargetActivity = null
                        currentEnabled = true
                    }
                    depth--
                }
            }
            pos += chunkSize
        }

        return Result(
            packageName = packageName,
            launcherActivities = launchers.distinct(),
            applicationName = applicationName?.let { normalizeClassName(it, packageName) },
            aliasMap = aliasMap,
        )
    }

    /** `.MainActivity` → `com.foo.MainActivity`；`MainActivity`（无点）同样补全。 */
    private fun normalizeClassName(name: String, pkg: String?): String {
        if (pkg.isNullOrBlank()) return name.removePrefix(".")
        return when {
            name.startsWith(".") -> pkg + name
            !name.contains('.') -> "$pkg.$name"   // 相对名（无前导点）
            else -> name
        }
    }

    // ────────────────────────── chunk 解析 ──────────────────────────

    private class Element(val name: String, val attrs: Map<String, String>)

    /**
     * RES_XML_START_ELEMENT 结构（相对 chunk 起点）：
     * ```
     * 0  type(2) headerSize(2) size(4)
     * 8  lineNumber(4) comment(4)
     * 16 ns(4) name(4)
     * 24 attributeStart(u16) attributeSize(u16) attributeCount(u16)
     *    idIndex(u16) classIndex(u16) styleIndex(u16)
     * 36 属性数组：每项 attributeSize(默认 20) 字节
     *      ns(4) name(4) rawValue(4) typedValue{ size(2) res0(1) dataType(1) data(4) }
     * ```
     */
    private fun readStartElement(
        buf: ByteBuffer,
        chunkPos: Int,
        headerSize: Int,
        strings: List<String>,
    ): Element {
        val nameIdx = buf.getInt(chunkPos + 20)
        val elName = strings.getOrNull(nameIdx) ?: ""

        val attrStart = buf.getShort(chunkPos + 24).toInt() and 0xFFFF
        val attrSize = buf.getShort(chunkPos + 26).toInt() and 0xFFFF
        val attrCount = buf.getShort(chunkPos + 28).toInt() and 0xFFFF

        val attrs = LinkedHashMap<String, String>()
        var p = chunkPos + headerSize + attrStart
        val step = if (attrSize >= 20) attrSize else 20

        repeat(attrCount) {
            if (p + step <= buf.limit()) {
                val nsIdx = buf.getInt(p)
                val attrNameIdx = buf.getInt(p + 4)
                val rawIdx = buf.getInt(p + 8)
                val dataType = buf.get(p + 15).toInt() and 0xFF
                val data = buf.getInt(p + 16)

                // 只收 android 命名空间下的属性；package 等无命名空间属性 nsIdx == -1
                val ns = if (nsIdx >= 0) strings.getOrNull(nsIdx) else null
                val isRelevant = ns == null || ns == ANDROID_NS

                if (isRelevant) {
                    val key = strings.getOrNull(attrNameIdx) ?: ""
                    val value = when {
                        // rawValue 优先：对字符串型属性它总是给原始文本，比 data 索引更稳
                        rawIdx >= 0 -> strings.getOrNull(rawIdx)
                        dataType == TYPE_STRING -> strings.getOrNull(data)
                        dataType == TYPE_REFERENCE -> "@ref/0x${data.toString(16)}"
                        dataType == TYPE_INT_BOOLEAN -> (data != 0).toString()
                        dataType == TYPE_INT_DEC -> data.toString()
                        dataType == TYPE_INT_HEX -> "0x${data.toString(16)}"
                        else -> null
                    }
                    if (key.isNotEmpty() && value != null) attrs.putIfAbsent(key, value)
                }
            }
            p += step
        }
        return Element(elName, attrs)
    }

    private fun readEndElementName(
        buf: ByteBuffer,
        chunkPos: Int,
        @Suppress("UNUSED_PARAMETER") headerSize: Int,
        strings: List<String>,
    ): String {
        val nameIdx = buf.getInt(chunkPos + 20)
        return strings.getOrNull(nameIdx) ?: ""
    }

    /**
     * RES_STRING_POOL：
     * ```
     * 0  type(2) headerSize(2) size(4)
     * 8  stringCount(4) styleCount(4) flags(4) stringsStart(4) stylesStart(4)
     * 28 stringCount 个 u32 偏移（相对 chunk 起点 + stringsStart）
     *    随后是字符串数据区
     * ```
     * flags & 0x100 (UTF8_FLAG) 决定编码：
     * - UTF-8：u16 长度（字符数）+ u8 长度（字节数）+ 数据 + \0
     * - UTF-16：u16 长度（字符数）+ 数据 + \0\0
     */
    private fun readStringPool(
        buf: ByteBuffer,
        chunkPos: Int,
        headerSize: Int,
        chunkSize: Int,
    ): List<String> {
        val count = buf.getInt(chunkPos + 8)
        val flags = buf.getInt(chunkPos + 16)
        val stringsStart = buf.getInt(chunkPos + 20)
        val isUtf8 = (flags and 0x100) != 0

        if (count <= 0 || count > 1_000_000) return emptyList()

        val out = ArrayList<String>(count)
        val offsetsBase = chunkPos + headerSize
        val dataBase = chunkPos + stringsStart
        val dataEnd = chunkPos + chunkSize

        for (i in 0 until count) {
            val offPos = offsetsBase + i * 4
            if (offPos + 4 > buf.limit()) break
            var p = dataBase + buf.getInt(offPos)
            if (p < 0 || p >= dataEnd || p >= buf.limit()) {
                out.add("")
                continue
            }
            out.add(
                try {
                    if (isUtf8) readUtf8(buf, p, dataEnd) else readUtf16(buf, p, dataEnd)
                } catch (_: Exception) {
                    ""
                }
            )
        }
        return out
    }

    private fun readUtf8(buf: ByteBuffer, start: Int, limit: Int): String {
        var p = start
        // u16 字符长度：单字节，高位为 1 表示两字节
        val b0 = buf.get(p).toInt() and 0xFF
        p += if (b0 and 0x80 != 0) 2 else 1
        // u8 字节长度：同上
        val b1 = buf.get(p).toInt() and 0xFF
        val byteLen: Int
        if (b1 and 0x80 != 0) {
            val lo = buf.get(p + 1).toInt() and 0xFF
            byteLen = ((b1 and 0x7F) shl 8) or lo
            p += 2
        } else {
            byteLen = b1
            p += 1
        }
        val end = minOf(p + byteLen, limit, buf.limit())
        val bytes = ByteArray(maxOf(0, end - p))
        for (i in bytes.indices) bytes[i] = buf.get(p + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, start: Int, limit: Int): String {
        var p = start
        val len = buf.getShort(p).toInt() and 0xFFFF
        p += 2
        val safeLen = minOf(len, (minOf(limit, buf.limit()) - p) / 2)
        if (safeLen <= 0) return ""
        val chars = CharArray(safeLen)
        for (i in 0 until safeLen) {
            chars[i] = (buf.getShort(p + i * 2).toInt() and 0xFFFF).toChar()
        }
        return String(chars)
    }
}

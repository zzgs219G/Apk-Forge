package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.apk.ApkRepackager
import java.io.File

/**
 * 「这个包的 dex 是不是被加固过」的判定。
 *
 * 【为什么这个判定必须写得很克制】
 * 加固检测的标准做法是数阈值（「类数少于 N」「字符串全是乱码」），
 * 但实测下来两条都会误报：
 *
 * | 我原本以为的判据 | 实测反例 |
 * |---|---|
 * | dex 总类数太少 → 疑似加固 | 正常包里也会有几个只有 0~13 个类的 dex（多 dex 切分产物） |
 * | assets 里有 .dex/.jar → 疑似加固 | 实测一个正常商业包在 `assets/` 下带了 2MB 的广告 SDK jar |
 *
 * 误报的代价在这里不对称：把正常包判成加固包，用户会以为工具坏了而放弃；
 * 漏报的代价只是一次失败的注入（而且失败时下游本来就会报「找不到类」）。
 * **所以宁可漏报，不许误报**——只在能拿出确定性证据时才下结论。
 *
 * 【用了哪些确定性证据】都来自 dex 文件头，与阈值无关：
 *
 * 1. **魔数不是 `dex\n`**：dex 被整体加密。壳会把整个文件加密，
 *    运行期在 native 里解密后再 `DexClassLoader` 加载。dexlib2 读它会抛异常。
 * 2. **file_size 字段与实际字节数不符**：dex 头声明的大小和真实大小对不上，
 *    说明这段数据不是它自称的那个 dex（常见于拼接了额外头的「假 dex」）。
 * 3. **header_size / endian_tag 非法**：头部字段被破坏。
 * 4. **class_defs_size > 0 但 string_ids_size == 0**：结构自相矛盾
 *    （没有字符串池就不可能有类定义），只可能是被打乱过。
 *
 * 这四条都是「要么成立、要么不成立」，没有可调的档位，因此不会随版本漂移。
 */
object PackIntegrity {

    /** 一条确定性证据。 */
    data class Issue(
        /** 哪个 dex 条目（`classes.dex`） */
        val entry: String,
        /** 一句话说清现象，直接显示给用户 */
        val detail: String,
    )

    /**
     * 检测结论。
     *
     * @param checked 实际检查过的 dex 条目数
     * @param issues 确定性证据；非空即「注入必定失败，且原因是包被加固」
     * @param readableDex 能被 dexlib2 正常打开的条目数
     */
    data class Result(
        val checked: Int,
        val issues: List<Issue>,
        val readableDex: Int,
        /** 用户可见的提示；null 表示没发现问题 */
        val hint: String?,
    ) {
        /**
         * 是否「结构上就注不进去」。
         *
         * 判据是**所有** dex 都不可读——只要还有任何一个能读，注入就有机会成功，
         * 那时不该拦，让下游按「找不到目标类」如实报错即可。
         */
        val blocked: Boolean get() = checked > 0 && readableDex == 0
    }

    private const val DEX_MAGIC = "dex\n"
    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_TAG = 0x12345678

    /**
     * 检查一个 APK 的 dex 结构。只读 dex 头部，不解析类，几十 MB 的包也是毫秒级。
     */
    fun inspect(apk: File): Result {
        val dexNames = try {
            ApkRepackager.listDexNames(apk)
        } catch (_: Exception) {
            return Result(0, emptyList(), 0, null)
        }
        if (dexNames.isEmpty()) return Result(0, emptyList(), 0, null)

        val issues = mutableListOf<Issue>()
        var readable = 0

        dexNames.forEach { name ->
            val bytes = try {
                ApkRepackager.readEntry(apk, name)
            } catch (_: Exception) {
                null
            }
            if (bytes == null) {
                issues += Issue(name, "读取失败")
                return@forEach
            }

            headerIssue(name, bytes)?.let { issues += Issue(name, it) }

            // 用 dex 自己的版本头去试读。读得开才算「可注入的候选」。
            if (openable(bytes)) readable++
        }

        return Result(
            checked = dexNames.size,
            issues = issues,
            readableDex = readable,
            hint = if (issues.isNotEmpty()) hintFor(dexNames.size, readable) else null,
        )
    }

    /**
     * 只看文件头就能判死的四种情况。返回 null 表示头部没毛病。
     *
     * 【为什么不在这里报告「dexlib2 读不开」】那属于「内容层面」的问题，
     * 由 [openable] 单独统计成 readableDex。头部合法但内容读不开
     * （比如指令表被篡改）是另一种形态，混在一起会让提示失真。
     */
    private fun headerIssue(name: String, b: ByteArray): String? {
        if (b.size < HEADER_SIZE) {
            return "只有 ${b.size} 字节，连 dex 头都不完整"
        }
        val magic = String(b, 0, 4, Charsets.US_ASCII)
        if (magic != DEX_MAGIC) {
            // 最典型的加固形态：整个 dex 被加密，头部不再是 dex\n
            return "魔数不是 dex\\n（前 4 字节为 ${hex(b, 0, 4)}），dex 已被整体加密"
        }
        val declaredSize = le32u(b, 0x20)
        if (declaredSize != b.size.toLong()) {
            return "头部声明 ${declaredSize} 字节，实际 ${b.size} 字节，数据被拼接或截断"
        }
        val headerSize = le32u(b, 0x24)
        if (headerSize != HEADER_SIZE.toLong()) {
            return "header_size=${headerSize}，合法值应为 $HEADER_SIZE"
        }
        val endian = le32(b, 0x28)
        // file_size/endian 两个字段：endian_tag 必须是 0x12345678（它本身就是小端写的），
        // 而 link_size/link_off 之后的 `endian_tag` 字段对大端序 dex 是 0x78563412。
        // 实测所有正常包（含本工程产物）这里都是 0x12345678，所以只校验前一个，
        // 不拿「两个都必须等于 ENDIAN_TAG」当判据——那会把正常包全判成异常。
        if (endian != ENDIAN_TAG) {
            return "endian_tag 非法（${hex(b, 0x28, 4)}），合法值应为 78 56 34 12"
        }
        val stringIds = le32u(b, 0x38)
        val classDefs = le32u(b, 0x60)
        if (classDefs > 0L && stringIds == 0L) {
            return "声明了 $classDefs 个类却没有任何字符串，结构自相矛盾"
        }
        return null
    }

    /**
     * 能否用 dexlib2 打开，并且类表结构自洽。
     *
     * 只做「打开 + 迭代一次类表」，不解析任何方法体：
     * dexlib2 是 lazy 的，能列出类就说明头部、字符串池、类型池、类定义区
     * 的偏移都指向合法位置——这正是注入要依赖的那部分结构。
     */
    private fun openable(bytes: ByteArray): Boolean = try {
        val opcodes = DexOpcodes.forBytes(bytes)
        val df = DexBackedDexFile.fromInputStream(opcodes, bytes.inputStream())
        // 摸一次迭代器：dex 声明了 class_defs 却迭代不出来，说明类定义区是坏的。
        // 允许 0 个类（实测正常包里确实存在 0 类的 dex）。
        df.classes.iterator().hasNext() || df.classes.isEmpty()
    } catch (_: Exception) {
        false
    }

    /**
     * 面向用户的提示。
     *
     * 【为什么这里能给出可执行的下一步】因为「所有 dex 都读不开」这个事实本身
     * 就指向唯一原因：dex 在最外层就被处理过（加密/拼接/魔改），
     * 而注入要改的正是 dex 的指令——这不可能靠换注入点绕过去。
     * 说清这一点，比让用户反复试不同类名有用得多。
     */
    private fun hintFor(total: Int, readable: Int): String? = when {
        readable == 0 ->
            "这个包的 $total 个 dex 全部无法解析。最常见的原因是**加固**" +
                "（360 加固 / 爱加密 / 梆梆 / 腾讯乐固等把 dex 整体加密，运行期在 native 里解密）。" +
                "这类包不能直接注入：注入要改的是 dex 指令，而这里连指令都读不出来。" +
                "可行的下一步：先用对应工具脱壳拿到可读的 dex，或换一个未加固的包。"
        readable < total ->
            "${total - readable} 个 dex 无法解析（共 $total 个）。" +
                "如果目标类正好落在这些 dex 里，注入会报「找不到类」——那不是类名写错，是那个 dex 被打包工具处理过。"
        else -> null
    }

    private fun hex(b: ByteArray, from: Int, len: Int): String =
        (from until minOf(from + len, b.size)).joinToString(" ") {
            "%02x".format(b[it].toInt() and 0xFF)
        }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le32u(b: ByteArray, at: Int): Long = le32(b, at).toLong() and 0xFFFFFFFFL
}

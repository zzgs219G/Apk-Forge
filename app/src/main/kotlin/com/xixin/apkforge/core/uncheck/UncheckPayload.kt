package com.xixin.apkforge.core.uncheck

import java.security.MessageDigest

/**
 * 去签名校验载荷：四个文件 + 一份索引。
 *
 * ## 为什么载荷是「下载」而不是打进 App
 *
 * 载荷会被注入到**别人的 App 里执行**，且随目标行为 / Android 版本持续调整。
 * 打进 ApkForge 意味着每改一次载荷就要发一次 App 版本；走网络则只需往
 * json 仓库推新文件，所有已装用户立刻拿到。
 *
 * ## 为什么四个文件缺一不可
 *
 * ```
 * boot.dex            → 目标包的 classes{N+1}.dex（SigFix + SigFixApp 引导器）
 * payload.dex         → assets/nc_sigfix/payload.dex（SigFixImpl，BootClassLoader 加载）
 * libusign_arm64.so   → lib/arm64-v8a/libusign.so（native APK 路径重定向）
 * libusign_armv7.so   → lib/armeabi-v7a/libusign.so
 * ```
 *
 * 少任何一个都不构成可用的载荷：
 * - 少 `boot.dex`：没有任何代码被注入，什么都不发生
 * - 少 `payload.dex`：引导器找不到实现类，`install()` 抛异常后静默失败
 * - 少 `.so`：只能拦「通过 PackageManager 问签名」的路径，
 *   App 自己 `ZipFile(base.apk)` 读签名块、native so 直接解析 Signing Block
 *   这两条路径**完全绕过**，而现代 App 普遍至少做其中一个
 *
 * 所以索引解析把四个文件都设为**必需**，缺一个就报错，不产出「残缺载荷」。
 * 这与「Java-only 也算能用」的乐观假设相反——按实测，那个假设不成立。
 *
 * ## 这个类是纯 JVM 的
 *
 * 不碰 `Context` / `Log` / 网络。下载由 `data/uncheck/PayloadFetcher` 负责，
 * 校验与解析在这里，因此可以在手机上直接跑单测（`AGENTS.md` §1.1）。
 */
object UncheckPayload {

    // ── 载荷文件名（与 json 仓库 backend/uncheck/ 下的文件名逐一对应）──
    const val BOOT_DEX = "boot.dex"
    const val PAYLOAD_DEX = "payload.dex"
    const val USIGN_ARM64 = "libusign_arm64.so"
    const val USIGN_ARM32 = "libusign_armv7.so"

    /** 必需文件清单。顺序即日志/报错里的展示顺序。 */
    val REQUIRED = listOf(BOOT_DEX, PAYLOAD_DEX, USIGN_ARM64, USIGN_ARM32)

    // ── 载荷在目标 APK 里的落点（唯一真相源）────────────────────
    //
    // 这些路径必须与载荷 Java 源码里的常量逐字一致，否则载荷读不到自己的东西：
    //   SigFix.PAYLOAD_ASSET  = "nc_sigfix/payload.dex"   → assets 前缀补齐
    //   SigFix.CONFIG_ASSET   = "nc_sigfix/sigfix.json"
    //   SigFix.ORIGIN_ASSET   = "nc_sigfix/origin.apk"
    //   SigFix.NATIVE_LIB     = "usign"                   → libusign.so
    const val ASSET_DIR = "assets/nc_sigfix"
    const val ASSET_PAYLOAD_DEX = "$ASSET_DIR/payload.dex"
    const val ASSET_CONFIG = "$ASSET_DIR/sigfix.json"
    const val ASSET_ORIGIN = "$ASSET_DIR/origin.apk"

    const val LIB_DIR_ARM64 = "lib/arm64-v8a"
    const val LIB_DIR_ARM32 = "lib/armeabi-v7a"
    const val LIB_NAME = "libusign.so"
    const val LIB_ARM64 = "$LIB_DIR_ARM64/$LIB_NAME"
    const val LIB_ARM32 = "$LIB_DIR_ARM32/$LIB_NAME"

    /** 载荷挂载的 Application 类名，必须与 boot.dex 里的类一致。 */
    const val SIGFIX_APP = "com.nc.uncheck.SigFixApp"

    /** 资产里的 Manifest 条目名。 */
    const val MANIFEST = "AndroidManifest.xml"

    class PayloadException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** 索引里一个文件的期望值。 */
    data class Entry(val sha256: String, val size: Long)

    /** 解析后的索引。 */
    data class Index(
        val version: String,
        val files: Map<String, Entry>,
    ) {
        fun entry(name: String): Entry =
            files[name] ?: throw PayloadException("索引里没有 $name 的条目")
    }

    /**
     * 可用的载荷（四个文件的字节）。
     *
     * 字节全部常驻内存：四个文件加起来约 290 KB，没必要做流式处理，
     * 而流水线要反复用（写 dex、写 .so），留在内存里更简单。
     */
    data class Payload(
        val version: String,
        val bootDex: ByteArray,
        val payloadDex: ByteArray,
        val usignArm64: ByteArray,
        val usignArm32: ByteArray,
    ) {
        /** 供日志与自检使用：各文件大小。 */
        fun sizes(): Map<String, Int> = mapOf(
            BOOT_DEX to bootDex.size,
            PAYLOAD_DEX to payloadDex.size,
            USIGN_ARM64 to usignArm64.size,
            USIGN_ARM32 to usignArm32.size,
        )
    }

    // ── 索引解析 ──────────────────────────────────────────────

    /**
     * 解析 `index.json`。
     *
     * ## 为什么手写解析而不引 JSON 库
     *
     * 结构固定且极浅（一个 version + 4 个 {sha256, size}），
     * `AGENTS.md` §1.4 要求「新依赖先问能不能用内置的」。Android 内置的
     * `org.json` 在 JVM 单测里是空壳（每个方法都抛 "not mocked"），
     * 用它反而让这个类不能单测。手写正则约 20 行，换来纯 JVM 可测。
     *
     * ## 严格性
     *
     * 四个必需文件**必须全部出现**。缺任何一个都抛异常——静默跳过会得到
     * 一个「Java 层能跑、native 层永远不生效」的载荷，而这个问题在真机上
     * 只表现为「某些包去校验无效」，没有报错。
     */
    fun parseIndex(text: String): Index {
        val version = Regex("""\"version\"\s*:\s*\"([^\"]+)\"""")
            .find(text)?.groupValues?.get(1)
            ?: throw PayloadException(
                "index.json 里没有 version 字段。\n" +
                    "  索引由 json 仓库的 scripts/uncheck_payload_sync.py 生成，" +
                    "请检查同步是否跑过。"
            )

        val files = LinkedHashMap<String, Entry>()
        val missing = ArrayList<String>()

        for (name in REQUIRED) {
            val block = Regex(
                "\"${Regex.escape(name)}\"\\s*:\\s*\\{([^}]*)\\}"
            ).find(text)?.groupValues?.get(1)
            if (block == null) {
                missing += name
                continue
            }
            val sha = Regex("""\"sha256\"\s*:\s*\"([0-9a-fA-F]{64})\"""")
                .find(block)?.groupValues?.get(1)
                ?: throw PayloadException(
                    "index.json 里 $name 的 sha256 缺失或格式不对（应为 64 位十六进制）"
                )
            val size = Regex("""\"size\"\s*:\s*(\d+)""")
                .find(block)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw PayloadException("index.json 里 $name 的 size 缺失")

            files[name] = Entry(sha.lowercase(), size)
        }

        if (missing.isNotEmpty()) {
            throw PayloadException(
                "index.json 缺少必需文件：${missing.joinToString(", ")}\n" +
                    "  这四项缺一不可：dex 负责 Java 层 hook，.so 负责 native 层重定向。\n" +
                    "  少了 .so 时 App 自读 APK / native 读签名块的校验会完全绕过本工具。"
            )
        }

        return Index(version, files)
    }

    /**
     * 校验一份载荷是否与索引一致。
     *
     * @return 问题列表；空表示通过
     */
    fun validate(index: Index, payload: Payload): List<String> {
        val problems = ArrayList<String>()
        if (payload.version != index.version) {
            problems += "载荷版本(${payload.version})与索引(${index.version})不一致"
        }
        val actual = payload.sizes()
        for (name in REQUIRED) {
            val want = index.entry(name)
            val bytes = when (name) {
                BOOT_DEX -> payload.bootDex
                PAYLOAD_DEX -> payload.payloadDex
                USIGN_ARM64 -> payload.usignArm64
                USIGN_ARM32 -> payload.usignArm32
                else -> continue
            }
            if (want.size >= 0 && bytes.size.toLong() != want.size) {
                problems += "$name 大小不符：期望 ${want.size}，实际 ${bytes.size}"
                continue
            }
            val got = sha256(bytes)
            if (got != want.sha256) {
                problems += "$name sha256 不符：期望 ${want.sha256.take(16)}…，实际 ${got.take(16)}…"
            }
            // 格式自检：dex 与 .so 的魔数
            val magicOk = when (name) {
                BOOT_DEX, PAYLOAD_DEX -> bytes.size >= 8 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "dex\n"
                USIGN_ARM64, USIGN_ARM32 -> bytes.size >= 4 &&
                    bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
                else -> true
            }
            if (!magicOk) {
                problems += "$name 魔数不对（不是合法 ${if (name.endsWith(".dex")) "dex" else "ELF"}）"
            }
        }
        return problems
    }

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

package com.xixin.apkforge.core.axml

/**
 * 二进制 AXML 的**布尔属性**原位改写（等长，不重建字符串池）。
 *
 * ## 为什么需要：`extractNativeLibs` 决定 .so 能不能被解压
 *
 * 去签名校验要往目标包里塞 `lib/{abi}/libusign.so`，Java 侧用
 * `System.load(nativeLibraryDir/libusign.so)` 加载。而
 * `nativeLibraryDir` 只有在 **`extractNativeLibs != false`** 时才有内容：
 *
 * ```
 * extractNativeLibs="true"  → 安装时把 .so 解压到 /data/app/<pkg>/lib/<abi>/
 *                              nativeLibraryDir 可读 → System.load 成功
 * extractNativeLibs="false" → .so 留在 APK 里，由 linker 直接从 zip 映射
 *                              nativeLibraryDir 为空 → System.load 失败
 * ```
 *
 * 目标包若是 `false`（`useLegacyPackaging false` 打包的），我们的 .so
 * 永远不会被解压出来 → native hook 装不上 → 去校验只剩 Java 层 → 基本没用。
 *
 * ## 为什么是「原位」改写而不是走 [AxmlEditor.setAppName] 那套
 *
 * 布尔属性只有一个 4 字节的 data 字段要改（`0x00000000` → `0xFFFFFFFF`），
 * **文件长度与所有偏移都不变**。重建字符串池+整体后移是完全没有必要的风险。
 *
 * ## 属性不存在时怎么办
 *
 * 这是本类与 artifact-pipeline `patch-extractnativelibs.py` 的**关键差异**：
 *
 * | | 云端脚本 | 本实现 |
 * |---|---|---|
 * | 属性已存在 | 原位改 false→true ✅ | ✅ |
 * | 属性不存在 | **报错退出**（无重建能力） | **借用 [AxmlEditor] 新增属性** ✅ |
 *
 * 目标包没写 `extractNativeLibs` 时（默认值在 targetSdk < 23 是 true、
 * 之后由打包器决定），必须能补上，否则这类包直接处理不了。
 *
 * 新增走 [AxmlEditor.appendBooleanToApplication]，它已实现「按 resourceId
 * 升序插入」这条红线（见 `docs/去签名校验.md` §7①）。
 */
object AxmlBoolean {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val NO_INDEX = 0xFFFFFFFF.toInt()

    /** framework 资源 ID：android:extractNativeLibs */
    private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104EA.toInt()

    class AxmlBooleanException(message: String) : Exception(message)

    /** 改写结果。 */
    enum class Outcome {
        /** 原本就是 true，未改动 */
        ALREADY_ON,

        /** 原位把 false 改成了 true */
        FLIPPED,

        /** 原本没有该属性，已新增 */
        ADDED,
    }

    /**
     * 把 `<application android:extractNativeLibs>` 改为 `true`。
     *
     * @return 改写后的 Manifest 字节与结果类型
     * @throws AxmlBooleanException 不是合法 AXML / 结构异常
     */
    fun setExtractNativeLibsTrue(manifest: ByteArray): Pair<ByteArray, Outcome> {
        val pool = parsePoolOrNull(manifest)
            ?: throw AxmlBooleanException("不是合法 AXML（找不到字符串池）")

        // 1) 先看字符串池里有没有 extractNativeLibs
        val attrNameIdx = pool.strings.indexOf("extractNativeLibs")

        if (attrNameIdx >= 0) {
            // 属性名在池里 → 找 <application> 上有没有这个属性
            val appOff = findApplicationOffset(manifest, pool)
                ?: throw AxmlBooleanException("找不到 <application> 元素")

            val hit = findBooleanAttr(manifest, appOff, attrNameIdx)
            if (hit != null) {
                val (dataOff, value) = hit
                if (value != 0) return manifest to Outcome.ALREADY_ON
                // 原位改成 true：只写这 4 字节，长度与偏移全不变
                val out = manifest.copyOf()
                putInt(out, dataOff, 0xFFFFFFFF.toInt())
                return out to Outcome.FLIPPED
            }
        }

        // 2) 属性不存在（或连属性名都不在池里）→ 交给 AxmlEditor 新增。
        //    它能扩字符串池、按 resourceId 升序插入，这两件都是本类做不到的。
        val out = AxmlEditor.setBooleanAttrOnApplication(
            data = manifest,
            attrName = "extractNativeLibs",
            attrResourceId = ATTR_EXTRACT_NATIVE_LIBS,
            value = true,
        )
        return out to Outcome.ADDED
    }

    /** 读当前值；属性不存在返回 null。 */
    fun getExtractNativeLibs(manifest: ByteArray): Boolean? {
        val pool = parsePoolOrNull(manifest) ?: return null
        val nameIdx = pool.strings.indexOf("extractNativeLibs")
        if (nameIdx < 0) return null
        val appOff = findApplicationOffset(manifest, pool) ?: return null
        return findBooleanAttr(manifest, appOff, nameIdx)?.let { it.second != 0 }
    }

    // ── 内部 ─────────────────────────────────────────────────

    private class Pool(val off: Int, val headerSize: Int, val size: Int, val strings: List<String>)

    /** 找不到字符串池时返回 null（调用方决定抛异常还是继续）。 */
    private fun parsePoolOrNull(data: ByteArray): Pool? {
        var off = 8
        while (off + 8 <= data.size) {
            val type = getShort(data, off)
            val headerSize = getShort(data, off + 2)
            val size = getInt(data, off + 4)
            if (size < 8 || off + size > data.size) return null
            if (type == RES_STRING_POOL_TYPE) {
                return Pool(off, headerSize, size, readStrings(data, off))
            }
            off += size
        }
        return null
    }

    private fun readStrings(data: ByteArray, off: Int): List<String> {
        val count = getInt(data, off + 8)
        val flags = getInt(data, off + 16)
        val stringsStart = getInt(data, off + 20)
        val utf8 = (flags and 0x100) != 0
        if (count <= 0 || count > 1_000_000) return emptyList()

        val base = off + stringsStart
        val end = off + getInt(data, off + 4)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val p = base + getInt(data, off + 28 + i * 4)
            out += try {
                if (utf8) readUtf8(data, p, end) else readUtf16(data, p, end)
            } catch (_: Exception) {
                ""
            }
        }
        return out
    }

    private fun readUtf8(data: ByteArray, start: Int, limit: Int): String {
        var p = start
        val b0 = data[p].toInt() and 0xFF
        p += if (b0 and 0x80 != 0) 2 else 1
        val b1 = data[p].toInt() and 0xFF
        val byteLen: Int
        if (b1 and 0x80 != 0) {
            byteLen = ((b1 and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)
            p += 2
        } else {
            byteLen = b1
            p += 1
        }
        val end2 = minOf(p + byteLen, limit, data.size)
        return String(data, p, maxOf(0, end2 - p), Charsets.UTF_8)
    }

    private fun readUtf16(data: ByteArray, start: Int, limit: Int): String {
        var p = start
        var n = getShort(data, p)
        p += 2
        if (n and 0x8000 != 0) {
            n = ((n and 0x7FFF) shl 16) or getShort(data, p)
            p += 2
        }
        val safe = minOf(n, (minOf(limit, data.size) - p) / 2)
        if (safe <= 0) return ""
        return String(data, p, safe * 2, Charsets.UTF_16LE)
    }

    /** 找 `<application>` 的 START_ELEMENT chunk 偏移。 */
    private fun findApplicationOffset(data: ByteArray, pool: Pool): Int? {
        var off = 8
        while (off + 8 <= data.size) {
            val type = getShort(data, off)
            val headerSize = getShort(data, off + 2)
            val size = getInt(data, off + 4)
            if (size < 8 || off + size > data.size) return null
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val nameIdx = getInt(data, off + headerSize + 4)
                if (pool.strings.getOrNull(nameIdx) == "application") return off
            }
            off += size
        }
        return null
    }

    /**
     * 在 `<application>` 的属性数组里找 [nameIdx] 对应的布尔属性。
     *
     * @return (data 字段绝对偏移, 当前值) 或 null
     */
    private fun findBooleanAttr(data: ByteArray, elemOff: Int, nameIdx: Int): Pair<Int, Int>? {
        val headerSize = getShort(data, elemOff + 2)
        val attrStart = getShort(data, elemOff + headerSize + 8)
        val attrSize = getShort(data, elemOff + headerSize + 10)
        val attrCount = getShort(data, elemOff + headerSize + 12)
        if (attrSize < 20) return null

        val base = elemOff + headerSize + attrStart
        for (i in 0 until attrCount) {
            val p = base + i * attrSize
            if (p + 20 > data.size) break
            if (getInt(data, p + 4) != nameIdx) continue
            val dataType = data[p + 15].toInt() and 0xFF
            if (dataType != TYPE_INT_BOOLEAN) {
                throw AxmlBooleanException(
                    "extractNativeLibs 的类型不是 BOOLEAN（type=0x${dataType.toString(16)}），" +
                        "不支持原位改写"
                )
            }
            return (p + 16) to getInt(data, p + 16)
        }
        return null
    }

    // ── 小端读写 ─────────────────────────────────────────────

    private fun getShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun getInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }
}

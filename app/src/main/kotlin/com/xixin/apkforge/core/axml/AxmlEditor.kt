package com.xixin.apkforge.core.axml

import java.io.File

/**
 * AxmlEditor —— 二进制 AXML 改写：给 `<application>` 加/改 `android:name`。
 *
 * ## 为什么需要它
 *
 * 去签名校验载荷要挂在**最早**的执行时机。可选挂载点按时间排序：
 *
 * ```
 * ContentProvider.onCreate      最早
 * Application.attachBaseContext  ← 我们挂这里
 * Application.onCreate
 * Activity.onCreate             最晚（只挂这里会漏掉上面三种）
 * ```
 *
 * 要挂到 Application 这一级，只有一条路：改 AndroidManifest 的
 * `android:name`，让它指向我们自己的 Application 子类。
 *
 * ## 与 [AxmlManifest] 的分工
 *
 * [AxmlManifest] 只读（解析包名 / LAUNCHER）。
 * 本文件负责**写**：追加或修改属性。写比读难得多：
 * 加一个属性会让 START_ELEMENT 块变长，字符串池也要扩容，
 * 之后所有 chunk 都要后移。
 *
 * ## 实现要点（顺序即风险从高到低）
 *
 * 1. **字符串池扩容**
 *    ```
 *    [chunk header 8B][strcount 4B][stylecount 4B][flags 4B]
 *    [stringsStart 4B][stylesStart 4B][offsets 4B×strcount][data...]
 *    ```
 *    追加字符串后：strcount+1、offsets 数组 +4B、stringsStart+4B。
 *    各字符串的 offset **不用改** —— 它们是相对 stringsStart 的。
 *
 * 2. **后续 chunk 整体后移**
 *    AXML 的 chunk 之间没有绝对偏移引用，只有各自的 size 字段，
 *    所以池变长后把后面的字节整体后移即可。
 *
 * 3. **START_ELEMENT 追加属性**
 *    属性结构 20 字节：`ns(4) name(4) rawValue(4) typedValue(8)`。
 *
 * ## 安全边界
 *
 * 解析失败一律抛异常，**绝不产出畸形 Manifest**（畸形 Manifest 会被系统
 * 拒装，且报错信息毫无指向性，是最难排查的一类问题）。
 */
object AxmlEditor {

    // ── AXML 常量 ──────────────────────────────────────────────
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val TYPE_STRING = 0x03
    private const val NO_INDEX = 0xFFFFFFFF.toInt()

    /** android:name 的属性名（framework 固定值，见 attrs.xml） */
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    class AxmlException(message: String) : Exception(message)

    /** 一个 START_ELEMENT 的位置信息。 */
    private data class Element(
        val off: Int,
        val headerSize: Int,
        val size: Int,
        val attrStart: Int,
        val attrCount: Int,
        val attrSize: Int,
    )

    /** 字符串池的内存模型。 */
    private class StringPool(
        val off: Int,
        val utf8: Boolean,
        val flags: Int,
        val styleCount: Int,
        val strings: MutableList<String>,
        val styleData: ByteArray,
        val chunkSize: Int,
    ) {
        fun indexOf(s: String): Int = strings.indexOf(s)

        fun add(s: String): Int {
            val i = indexOf(s)
            if (i >= 0) return i
            strings.add(s)
            return strings.size - 1
        }

        /** 重新序列化整个池（header + offsets + data）。 */
        fun serialize(): ByteArray {
            val datas = strings.map { encode(it) }
            val offsets = IntArray(datas.size)
            var cur = 0
            for (i in datas.indices) {
                offsets[i] = cur
                cur += datas[i].size
            }
            val dataBlob = concat(datas)
            val padded = if (dataBlob.size % 4 == 0) dataBlob
            else dataBlob + ByteArray(4 - dataBlob.size % 4)

            val offsetsBlob = ByteArray(offsets.size * 4)
            for (i in offsets.indices) {
                putInt(offsetsBlob, i * 4, offsets[i])
            }

            val headerSize = 28
            val stringsStart = headerSize + offsetsBlob.size + styleData.size
            val stylesStart = if (styleData.isEmpty()) 0 else stringsStart + padded.size
            val totalSize = stringsStart + padded.size

            val out = ByteArray(totalSize)
            putShort(out, 0, RES_STRING_POOL_TYPE)
            putShort(out, 2, headerSize)
            putInt(out, 4, totalSize)
            putInt(out, 8, strings.size)
            putInt(out, 12, styleCount)
            putInt(out, 16, flags)
            putInt(out, 20, stringsStart)
            putInt(out, 24, stylesStart)
            System.arraycopy(offsetsBlob, 0, out, headerSize, offsetsBlob.size)
            System.arraycopy(styleData, 0, out, headerSize + offsetsBlob.size, styleData.size)
            System.arraycopy(padded, 0, out, stringsStart, padded.size)
            return out
        }

        private fun encode(s: String): ByteArray {
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                val out = ArrayList<Byte>()
                if (s.length > 0x7F) {
                    out.add(((s.length and 0xFF) or 0x80).toByte())
                    out.add(((s.length shr 8) and 0xFF).toByte())
                } else {
                    out.add(s.length.toByte())
                }
                if (bytes.size > 0x7F) {
                    out.add(((bytes.size and 0xFF) or 0x80).toByte())
                    out.add(((bytes.size shr 8) and 0xFF).toByte())
                } else {
                    out.add(bytes.size.toByte())
                }
                bytes.forEach { out.add(it) }
                out.add(0)
                return out.toByteArray()
            }
            val bytes = s.toByteArray(Charsets.UTF_16LE)
            val out = ArrayList<Byte>()
            val n = s.length
            if (n > 0x7FFF) {
                out.add((((n shr 16) or 0x8000) and 0xFFFF).toByte())
                out.add((((n shr 24) and 0xFF)).toByte())
            } else {
                out.add((n and 0xFF).toByte())
                out.add(((n shr 8) and 0xFF).toByte())
            }
            bytes.forEach { out.add(it) }
            out.add(0)
            out.add(0)
            return out.toByteArray()
        }
    }

    // ── 公开 API ──────────────────────────────────────────────

    /** 读 `<application>` 的 android:name；没有则返回 null。 */
    fun getAppName(file: File): String? = getAppName(file.readBytes())

    fun getAppName(data: ByteArray): String? {
        val pool = requireStringPool(data)
        val el = findApplication(data, pool) ?: return null
        for (i in 0 until el.attrCount) {
            val p = el.off + el.headerSize + el.attrStart + i * el.attrSize
            val nsIdx = getInt(data, p)
            val nameIdx = getInt(data, p + 4)
            if (pool.strings[nameIdx] != "name") continue
            if (pool.strings[nsIdx] != ANDROID_NS) continue
            val raw = getInt(data, p + 8)
            val typedData = getInt(data, p + 16)
            val idx = if (raw != NO_INDEX) raw else typedData
            return pool.strings.getOrNull(idx)
        }
        return null
    }

    /**
     * 设置 `<application>` 的 android:name，返回改写后的完整 Manifest 字节。
     *
     * @throws AxmlException 不是合法 AXML、找不到 `<application>`、或结构异常
     */
    fun setAppName(data: ByteArray, newClass: String): ByteArray {
        if (data.size < 8 || getShort(data, 0) != RES_XML_TYPE) {
            throw AxmlException("不是二进制 AXML（文件头不是 0x0003）")
        }
        loadResMap(data)
        val pool = requireStringPool(data)
        val el = findApplication(data, pool)
            ?: throw AxmlException("找不到 <application> 元素")

        // 找现有的 android:name
        var existingIdx = -1
        for (i in 0 until el.attrCount) {
            val p = el.off + el.headerSize + el.attrStart + i * el.attrSize
            val nsIdx = getInt(data, p)
            val nameIdx = getInt(data, p + 4)
            if (nameIdx < pool.strings.size && nsIdx < pool.strings.size &&
                pool.strings[nameIdx] == "name" && pool.strings[nsIdx] == ANDROID_NS
            ) {
                existingIdx = i
                break
            }
        }

        // 幂等：已经是目标值就原样返回
        if (existingIdx >= 0) {
            val p = el.off + el.headerSize + el.attrStart + existingIdx * el.attrSize
            val raw = getInt(data, p + 8)
            if (raw != NO_INDEX && pool.strings.getOrNull(raw) == newClass) {
                return data
            }
        }

        val valueIdx = pool.add(newClass)
        val nsIdx = pool.add(ANDROID_NS)
        val nameStrIdx = pool.add("name")

        return if (existingIdx >= 0) {
            replaceAttrValue(data, pool, el, existingIdx, valueIdx)
        } else {
            appendAttr(data, pool, el, nsIdx, nameStrIdx, valueIdx)
        }
    }

    fun setAppName(file: File, newClass: String): Boolean {
        val before = file.readBytes()
        val after = setAppName(before, newClass)
        if (after.contentEquals(before)) return false
        file.writeBytes(after)
        return true
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private fun replaceAttrValue(
        data: ByteArray,
        pool: StringPool,
        el: Element,
        attrIndex: Int,
        valueIdx: Int,
    ): ByteArray {
        val newPool = pool.serialize()
        val delta = newPool.size - pool.chunkSize
        val rest = data.copyOfRange(pool.off + pool.chunkSize, data.size)
        val newElemOff = el.off - (pool.off + pool.chunkSize)

        val p = newElemOff + el.headerSize + el.attrStart + attrIndex * el.attrSize
        // 与 appendAttr 同一形态：rawValue=NO_INDEX、typedValue 为字符串索引
        putInt(rest, p + 8, valueIdx)
        putShort(rest, p + 12, 8)
        rest[p + 14] = 0
        rest[p + 15] = TYPE_STRING.toByte()
        putInt(rest, p + 16, valueIdx)

        return assemble(data, newPool, rest, delta)
    }

    private fun appendAttr(
        data: ByteArray,
        pool: StringPool,
        el: Element,
        nsIdx: Int,
        nameIdx: Int,
        valueIdx: Int,
    ): ByteArray {
        if (el.attrSize != 20) {
            throw AxmlException("属性尺寸不是 20（实际 ${el.attrSize}），不支持")
        }
        val newPool = pool.serialize()
        val delta = newPool.size - pool.chunkSize
        val rest = data.copyOfRange(pool.off + pool.chunkSize, data.size)
        val newElemOff = el.off - (pool.off + pool.chunkSize)

        // ⚠️ 插入位置不是"追加到末尾"，而是**按 resourceId 升序插入**。
        // 实测对照 MT 生成的 Manifest：android:name（resourceId 0x01010003，
        // 这几个属性里最小）必须插在属性数组最前面。追加到末尾时
        // PackageParser 会提前停止扫描，系统读不到该属性 ——
        // 表现为 dumpsys 里 applicationInfo 没有 className、App 照旧跑原类。
        val insIndex = attrInsertIndex(
            newElemOff + el.headerSize + el.attrStart, el.attrCount, el.attrSize,
            rest, nameIdx,
        )
        val insertAt = newElemOff + el.headerSize + el.attrStart + insIndex * el.attrSize
        // 属性 20 字节布局（实测自原包 appComponentFactory）：
        //   ns(4) name(4) rawValue(4) size(2) res0(1) dataType(1) data(4)
        // rawValue 必须是**字符串池索引**（不是 NO_INDEX）。实测 MT 与
        // 原包 appComponentFactory 的形态都是 raw == data == 字符串索引。
        // dataType 用 TYPE_STRING(0x03)，data 是**字符串池索引**。
        // 属性的身份（这里是 android:name）由 chunk 0x0180 的 resource map
        // 把 name 索引映射到 0x01010003 决定，不需要把资源 ID 塞进 typedValue。
        val attr = ByteArray(20)
        putInt(attr, 0, nsIdx)
        putInt(attr, 4, nameIdx)
        putInt(attr, 8, valueIdx)   // rawValue = 字符串索引（实测 MT 的正确形态）
        putShort(attr, 12, 8)              // typedValue.size
        attr[14] = 0                        // res0
        attr[15] = TYPE_STRING.toByte()
        putInt(attr, 16, valueIdx)

        val merged = ByteArray(rest.size + 20)
        System.arraycopy(rest, 0, merged, 0, insertAt)
        System.arraycopy(attr, 0, merged, insertAt, 20)
        System.arraycopy(rest, insertAt, merged, insertAt + 20, rest.size - insertAt)

        // attrCount +1（在 START_ELEMENT body 里，偏移 headerSize+8+4）
        val countOff = newElemOff + el.headerSize + 8 + 4
        putShort(merged, countOff, el.attrCount + 1)
        // 该 chunk 的 size +20
        putInt(merged, newElemOff + 4, el.size + 20)

        return assemble(data, newPool, merged, delta)
    }

    private fun assemble(
        original: ByteArray,
        newPool: ByteArray,
        rest: ByteArray,
        delta: Int,
    ): ByteArray {
        val head = original.copyOfRange(0, 8)
        val out = ByteArray(8 + newPool.size + rest.size)
        System.arraycopy(head, 0, out, 0, 8)
        System.arraycopy(newPool, 0, out, 8, newPool.size)
        System.arraycopy(rest, 0, out, 8 + newPool.size, rest.size)
        putInt(out, 4, out.size)   // 更新文件头里的总大小
        return out
    }

    /** resource map：属性 name 索引 → framework 资源 ID。每次改写前重载。 */
    private var resMap: Map<Int, Int> = emptyMap()

    private fun loadResMap(data: ByteArray) {
        val m = HashMap<Int, Int>()
        for ((type, off, headerSize, size) in chunks(data)) {
            if (type != RES_XML_RESOURCE_MAP_TYPE) continue
            val n = (size - headerSize) / 4
            for (i in 0 until n) {
                m[i] = getInt(data, off + headerSize + i * 4)
            }
            break
        }
        resMap = m
    }

    /**
     * 按 resourceId 升序决定新属性插在第几位。
     *
     * PackageParser 读 `<application>` 的属性时期望按 framework 资源 ID 升序，
     * 乱序会让它提前停止扫描、后面的属性全部丢失。android:name 的 ID 是
     * 0x01010003，通常是这批里最小的，所以要插最前。
     * 拿不到 resourceId 的属性（ID 视为最大）排最后。
     */
    private fun attrInsertIndex(
        attrBase: Int,
        attrCount: Int,
        attrSize: Int,
        rest: ByteArray,
        newNameIdx: Int,
    ): Int {
        val newId = resMap[newNameIdx] ?: return attrCount
        for (i in 0 until attrCount) {
            val p = attrBase + i * attrSize
            val nm = getInt(rest, p + 4)
            val curId = resMap[nm] ?: Int.MAX_VALUE
            if (newId < curId) return i
        }
        return attrCount
    }

    private fun requireStringPool(data: ByteArray): StringPool {
        for ((type, off, headerSize, size) in chunks(data)) {
            if (type == RES_STRING_POOL_TYPE) return parsePool(data, off)
        }
        throw AxmlException("找不到字符串池")
    }

    private fun parsePool(data: ByteArray, off: Int): StringPool {
        val size = getInt(data, off + 4)
        val strCount = getInt(data, off + 8)
        val styleCount = getInt(data, off + 12)
        val flags = getInt(data, off + 16)
        val stringsStart = getInt(data, off + 20)
        val stylesStart = getInt(data, off + 24)
        val utf8 = (flags and 0x100) != 0
        val base = off + stringsStart

        val strings = ArrayList<String>(strCount)
        for (i in 0 until strCount) {
            val strOff = base + getInt(data, off + 28 + i * 4)
            strings.add(readString(data, strOff, utf8))
        }
        val styleData = if (styleCount > 0 && stylesStart > 0) {
            data.copyOfRange(off + stylesStart, off + size)
        } else ByteArray(0)

        return StringPool(off, utf8, flags, styleCount, strings, styleData, size)
    }

    private fun readString(data: ByteArray, start: Int, utf8: Boolean): String {
        var p = start
        if (utf8) {
            var n = data[p].toInt()
            p += if (n and 0x80 != 0) 2 else 1
            n = data[p].toInt()
            if (n and 0x80 != 0) {
                n = ((n and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)
                p += 2
            } else {
                p += 1
            }
            return String(data, p, n, Charsets.UTF_8)
        }
        var n = getShort(data, p)
        p += 2
        if (n and 0x8000 != 0) {
            n = ((n and 0x7FFF) shl 16) or getShort(data, p)
            p += 2
        }
        return String(data, p, n * 2, Charsets.UTF_16LE)
    }

    private fun findApplication(data: ByteArray, pool: StringPool): Element? {
        for ((type, off, headerSize, size) in chunks(data)) {
            if (type != RES_XML_START_ELEMENT_TYPE) continue
            val nameIdx = getInt(data, off + headerSize + 4)
            if (pool.strings.getOrNull(nameIdx) != "application") continue
            val attrStart = getShort(data, off + headerSize + 8)
            val attrSize = getShort(data, off + headerSize + 10)
            val attrCount = getShort(data, off + headerSize + 12)
            return Element(off, headerSize, size, attrStart, attrCount, attrSize)
        }
        return null
    }

    /** 遍历 chunk。**从 8 开始**：最外层是覆盖整个文件的 XML 容器。 */
    private fun chunks(data: ByteArray): Sequence<Chunk> = sequence {
        var off = 8
        while (off + 8 <= data.size) {
            val type = getShort(data, off)
            val headerSize = getShort(data, off + 2)
            val size = getInt(data, off + 4)
            if (size < 8 || off + size > data.size) {
                throw AxmlException("chunk 尺寸异常 @0x${off.toString(16)}: size=$size")
            }
            yield(Chunk(type, off, headerSize, size))
            off += size
        }
    }

    private data class Chunk(val type: Int, val off: Int, val headerSize: Int, val size: Int)

    // ── 字节工具（小端）──────────────────────────────────────
    private fun getShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun getInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var p = 0
        for (x in parts) {
            System.arraycopy(x, 0, out, p, x.size)
            p += x.size
        }
        return out
    }
}

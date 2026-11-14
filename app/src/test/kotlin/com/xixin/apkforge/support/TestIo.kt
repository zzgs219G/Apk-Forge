package com.xixin.apkforge.support

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 本地 JVM 测试用的最小 IO 封装（注入核心不含 Android API，可直接在此验证）。 */
internal object TestIo {

    fun readEntry(apk: File, name: String): ByteArray? {
        java.util.zip.ZipFile(apk).use { zip ->
            val e = zip.getEntry(name) ?: return null
            return zip.getInputStream(e).use { drain(it) }
        }
    }

    fun copy(src: File, dst: File): File {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        return dst
    }

    private fun drain(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream(8192)
        val tmp = ByteArray(8192)
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }
}

package com.xixin.apkforge.core.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * 把弹窗配置**烘焙进载荷 dex**。
 *
 * 【为什么要烘焙，而不是像上一版那样塞 assets】
 * 上一版把 `assets/apkforge_dialog.json` 和 dex 一起写进宿主，代价有三：
 *   1. 注入产物凭空多一个文件，用户会问「你往我包里塞了什么」——他的质疑没错；
 *   2. 宿主的 assets 是它的自有资产，第三方工具去写它属于越界；
 *   3. 一条配置两条通道（dex + 资产），任一没写进去就是「注入成功、不弹窗」，
 *      而这种失败**不会报错**，只在运行期表现为什么都不发生。
 * 烘焙之后产物里只有 `classesN.dex` 一个新增条目，配置就在里面。
 *
 * 【怎么做到不用改 dex 的字符串表长度】
 * 载荷里 `PayloadConfig.baked()` 返回一个占位串。烘焙 = 把这个方法的**方法体**
 * 换成 `const-string v0, "<真配置>"` + `return-object v0`，再用 DexPool 整体重写。
 * 这样新字符串是 DexPool 正常写入的，不涉及手工改偏移量那种容易出灰包的操作。
 *
 * 【为什么占位符必须由方法返回，不能写成 static final 常量】
 * javac 会把 `static final String` 内联到每个使用点，dex 里出现多份拷贝，
 * 就没有「唯一那个常量」可定位了。`PluginPayloadTest` 钉住「只出现一次」。
 */
object PayloadBaker {

    /** 与 `PayloadConfig.baked()` 里的字符串逐字一致（测试钉住这条等式）。 */
    const val PLACEHOLDER = "__APKFORGE_CONFIG__"

    const val CONFIG_CLASS = "Lcom/apkforge/payload/dialog/PayloadConfig;"
    private const val BAKED_METHOD = "baked"
    private const val BAKED_SIGNATURE = "()Ljava/lang/String;"

    sealed interface Outcome {
        data class Ok(val dex: ByteArray) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 把 [configJsonc] 写进 [dexBytes] 的 `PayloadConfig.baked()`。 */
    fun bake(dexBytes: ByteArray, configJsonc: String): Outcome {
        // 载荷 dex 是我们自己用 d8 产出的，版本头取决于 build-tools；
        // 同样不能假设它是 035——用文件头里写着的那一版。
        val opcodes = DexOpcodes.forBytes(dexBytes)
        val src = try {
            DexBackedDexFile.fromInputStream(
                opcodes, java.io.BufferedInputStream(dexBytes.inputStream())
            ) as DexFile
        } catch (e: Exception) {
            return Outcome.Failed("载荷 dex 解析失败：${e.message}")
        }

        val cls = src.classes.firstOrNull { it.type == CONFIG_CLASS }
            ?: return Outcome.Failed("载荷 dex 里没有 $CONFIG_CLASS，不是 ApkForge 的弹窗载荷")
        val methods = cls.methods.toList()
        val baked = methods.firstOrNull {
            it.name == BAKED_METHOD && signatureOf(it) == BAKED_SIGNATURE
        } ?: return Outcome.Failed(
            "载荷里没有 $BAKED_METHOD$BAKED_SIGNATURE，无法烘焙配置。请用 payload/dialog/build.sh 重新生成"
        )

        val newImpl = ImmutableMethodImplementation(
            1,
            listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0, ImmutableStringReference(configJsonc)
                ),
                // RETURN_OBJECT 是 11x 格式（带一个寄存器），不是 10x。
                // 写成 ImmutableInstruction10x 会被 Preconditions 直接拒掉。
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
            null,
            null,
        )
        val newMethod = ImmutableMethod(
            cls.type,
            baked.name,
            baked.parameterTypes.map {
                com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(it.toString(), null, null)
            },
            baked.returnType,
            baked.accessFlags,
            baked.annotations.map {
                com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation.of(it)
            }.toSet(),
            baked.hiddenApiRestrictions,
            newImpl,
        )
        val newClass = ImmutableClassDef(
            cls.type,
            cls.accessFlags,
            cls.superclass,
            cls.interfaces,
            cls.sourceFile,
            cls.annotations,
            cls.fields,
            methods.map {
                if (it.name == BAKED_METHOD && signatureOf(it) == BAKED_SIGNATURE) newMethod else it
            },
        )
        val bakedFile = object : DexFile {
            override fun getClasses(): Set<ClassDef> =
                LinkedHashSet(src.classes.map { if (it.type == CONFIG_CLASS) newClass else it })

            override fun getOpcodes(): Opcodes = opcodes
        }

        return try {
            val store = MemoryDataStore()
            DexPool.writeTo(store, bakedFile)
            val out = store.data
            // 自检：占位符必须已经消失，配置必须真的在里面。
            // 少了这两句，写坏 dex 的表现是「注入成功、不弹窗」，没有报错可查。
            //
            // ⚠️ 必须在**字节层**比，不能 `String(dex, Charsets.ISO_8859_1).contains(...)`：
            // dex 的字符串池是 MUTF-8，中文在文件里是 3 字节的 UTF-8 序列，
            // 按 ISO-8859-1 解出来只会得到一堆 Latin-1 字符，中文永远匹配不上——
            // 那样写出来的自检会把「烘焙成功」误判成失败。
            if (containsBytes(out, PLACEHOLDER.toByteArray(Charsets.US_ASCII))) {
                return Outcome.Failed("烘焙后占位符仍在（可能存在第二份拷贝），拒绝产出不可用的 dex")
            }
            if (!containsBytes(out, configJsonc.toByteArray(Charsets.UTF_8))) {
                return Outcome.Failed("烘焙后找不到配置内容，dex 写出异常")
            }
            Outcome.Ok(out)
        } catch (e: Exception) {
            Outcome.Failed("烘焙 dex 写出失败：${e.message}")
        }
    }

    /**
     * 从**已烘焙**的 dex 里把配置读回来。
     *
     * 用途：导入插件包时恢复表单（zip 里只有 dex + 调用行两件东西，
     * 配置就在 dex 里，不需要额外的 json 文件跟着跑）。
     */
    fun extract(dexBytes: ByteArray): String? = try {
        val df = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(dexBytes), java.io.BufferedInputStream(dexBytes.inputStream())
        )
        val m = df.classes.firstOrNull { it.type == CONFIG_CLASS }
            ?.methods?.toList()
            ?.firstOrNull { it.name == BAKED_METHOD && signatureOf(it) == BAKED_SIGNATURE }
        val insn = m?.implementation?.instructions?.firstOrNull() as? ReferenceInstruction
        val text = (insn?.reference as? StringReference)?.string
        // 不能要求它「以 { 开头」：烘焙进去的配置带一段 /* */ 说明注释（故意的，
        // 它是给人看的）。判据改成「不是占位符且非空白」——模板 dex 返回的正是占位符。
        text?.takeIf { it.isNotBlank() && it != PLACEHOLDER }
    } catch (_: Exception) {
        null
    }

    /** dex 里是否已经是「烘焙好」的状态（占位符已不在）。 */
    fun isBaked(dexBytes: ByteArray): Boolean =
        !containsBytes(dexBytes, PLACEHOLDER.toByteArray(Charsets.US_ASCII))

    /** 字节级子串查找。占位符是纯 ASCII，配置串按 UTF-8 编（与 MUTF-8 对 BMP 字符一致）。 */
    internal fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        var i = 0
        outer@ while (i <= hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    private fun signatureOf(m: Method): String =
        "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
}

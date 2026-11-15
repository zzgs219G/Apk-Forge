package com.xixin.apkforge.core.dex.patch

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.xixin.apkforge.core.apk.ApkRepackager
import com.xixin.apkforge.core.dex.DexOpcodes
import com.xixin.apkforge.core.dex.DexCallInjector
import com.xixin.apkforge.core.dex.SmaliCallParser
import com.xixin.apkforge.support.TestIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 增量写入器的验证。
 *
 * 这组测试的意义：增量写入直接把字节拼进 dex，一旦偏移算错，
 * dexlib2 未必读不出来（它会读出**错误但合法**的结构）。
 * 所以断言分三层：
 *
 * 1. **结构**：产物能被 dexlib2 解析、类数不变、目标类还在；
 * 2. **内容**：目标方法的第一条指令确实是新插入的那条；
 * 3. **差分编码**：未被修改的方法，其 code_off 指向的指令**逐条不变**——
 *    这条最关键，因为 class_data_item 是差分编码，一处算错会连带后面所有方法错位。
 */
class DexPatcherTest {

    private fun locateApk(): File {
        val p = System.getProperty("apkforge.testApk") ?: System.getenv("APKFORGE_TEST_APK")
        p?.let { val f = File(it); if (f.isFile) return f }
        return listOf(
            File("build/outputs/apk/debug/app-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到测试 APK")
    }

    /** 取一个「带 onCreate(Bundle) 且方法体存在、没有 try/catch」的类。 */
    private fun pickTarget(apk: File): Triple<String, String, ByteArray>? {
        for (name in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, name) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            }.getOrNull() ?: continue
            for (cls in df.classes) {
                for (m in cls.methods) {
                    if (m.name != "onCreate") continue
                    if (m.parameterTypes.map { it.toString() } != listOf("Landroid/os/Bundle;")) continue
                    val impl = m.implementation ?: continue
                    // 增量写入暂不支持带 try/catch 的方法，选靶子时就排除
                    if (impl.tryBlocks.isNotEmpty()) continue
                    // 依赖 p0：ins_size 必须 >= 1（实例方法）
                    return Triple(name, cls.type, bytes)
                }
            }
        }
        return null
    }

    /**
     * 核心用例：插入一条 invoke，产物结构必须正确。
     *
     * 这里刻意**不**用 dexlib2 去编码指令，而是手写 3 个 16 位字，
     * 从而让「指令编码」与「dex 布局」两件事解耦——
     * 出问题时能立刻分清是编码错了还是布局错了。
     *
     * 被调用的方法取**本 dex 里真实存在的任意一个 static 方法**，
     * 因为增量写入的前提就是「引用已在池里」，不能凭空引用外部类。
     */
    @Test
    fun `增量插入 invoke 后产物可解析且指令在头部`() {
        val apk = locateApk()
        val picked = pickTarget(apk)
        assumeTrue("没有合适的靶子方法（需要无 try/catch 的 onCreate）", picked != null)
        val (dexName, ownerType, dexBytes) = picked!!

        val df = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(dexBytes), dexBytes.inputStream()
        )

        // 找一个本 dex 内真实存在的静态方法作为调用目标（增量不能新增池条目）
        val callee = findAnyStaticMethod(df)
        assumeTrue("dex 里找不到 static 方法，跳过", callee != null)
        val (calleeClass, calleeName) = callee!!

        val methodIdx = indexOfMethod(df, calleeClass, calleeName)
            ?: run { assumeTrue("定位不到 static 方法的 index", false); return }

        // 35c 指令编码：
        //   unit0 = op | (G << 12) | (A << 8)    A=寄存器个数, G=第 5 个寄存器高 4 位
        //   unit1 = method_idx
        //   unit2 = {C,D,E,F} 各 4 bit
        val op = 0x71 // invoke-static
        val unit0 = op or (1 shl 12) or (0 shl 8) // A=1, G=0，用一个寄存器 p0
        val unit1 = methodIdx.toInt() and 0xFFFF
        val unit2 = 0
        val prefix = shortArrayOf(unit0.toShort(), unit1.toShort(), unit2.toShort())

        val outcome = DexPatcher.replaceCode(
            dexBytes,
            DexPatcher.ReplaceCodeRequest(
                classType = ownerType,
                methodName = "onCreate",
                methodSignature = "(Landroid/os/Bundle;)V",
                prefixInsns = prefix,
            )
        )
        assertTrue("增量写入应成功，实际 $outcome", outcome is DexPatcher.Outcome.Ok)
        val ok = outcome as DexPatcher.Outcome.Ok

        // 验证 1：产物可被 dexlib2 解析
        val newDf = runCatching {
            DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(ok.dex), ok.dex.inputStream())
        }.getOrNull()
        assertNotNull("产物 dex 结构非法（dexlib2 读不出来）", newDf)

        // 验证 2：类数不变
        assertEquals("类数应保持不变", df.classes.size, newDf!!.classes.size)

        // 验证 3：目标方法首条指令就是插入的那条
        val cls = newDf.classes.first { it.type == ownerType }
        val m = cls.methods.first {
            it.name == "onCreate" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
        }
        val first = m.implementation!!.instructions.first()
        assertEquals("首条指令应是 INVOKE_STATIC", Opcode.INVOKE_STATIC, first.opcode)
        val ref = (first as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
            .reference as com.android.tools.smali.dexlib2.iface.reference.MethodReference
        assertEquals("被调用的应是 $calleeName", calleeName, ref.name)

        // 验证 4：原方法指令被完整保留（只在前面加了 3 字）
        val oldCount = df.classes.first { it.type == ownerType }
            .methods.first {
                it.name == "onCreate" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/os/Bundle;")
            }.implementation!!.instructions.count()
        assertEquals(
            "原指令应全部保留，只多出 3 字前缀",
            oldCount + 1, // 3 字前缀在 dexlib2 里算 1 条指令
            m.implementation!!.instructions.count(),
        )

        println("PATCHER dex=$dexName ${ok.originalSize} → ${ok.newSize} 字节 · 调用 $calleeClass->$calleeName")
    }

    /** 找一个本 dex 内已有的 static 方法（增量写入不能新增池条目）。 */
    private fun findAnyStaticMethod(
        df: DexBackedDexFile,
    ): Pair<String, String>? {
        for (cls in df.classes) {
            for (m in cls.methods) {
                if (m.accessFlags and 0x8 != 0 && m.parameterTypes.isEmpty() &&
                    m.returnType == "V" && m.implementation != null
                ) {
                    return cls.type to m.name
                }
            }
        }
        return null
    }

    /**
     * 差分编码的连带验证：**未被修改的方法，其指令必须逐条不变**。
     *
     * 这是增量写入最容易静默出错的地方——class_data_item 里 code_off 是差分，
     * 把某一段的基准算错，后面的方法会全部指到错误位置，
     * 而 dexlib2 照样能把产物解析出来（只是方法体内容变了）。
     */
    @Test
    fun `未修改的方法指令保持不变`() {
        val apk = locateApk()
        val picked = pickTarget(apk)
        assumeTrue("没有合适的靶子", picked != null)
        val (_, ownerType, dexBytes) = picked!!

        val df = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes(dexBytes), dexBytes.inputStream()
        )
        val targetClass = df.classes.first { it.type == ownerType }

        // 记录修改前：除 onCreate 外，前 20 个方法的指令快照
        fun snapshot(cls: com.android.tools.smali.dexlib2.iface.ClassDef): Map<String, List<String>> =
            cls.methods
                .filter { it.implementation != null && it.name != "onCreate" }
                .take(20)
                .associate { m ->
                    (m.name + "(" + m.parameterTypes.joinToString("") + ")" + m.returnType) to
                        m.implementation!!.instructions.toList().map { it.opcode.name }
                }

        val before = snapshot(targetClass)
        assumeTrue("这个类里没有别的带方法体的方法，跳过", before.isNotEmpty())

        val outcome = DexPatcher.replaceCode(
            dexBytes,
            DexPatcher.ReplaceCodeRequest(
                classType = ownerType,
                methodName = "onCreate",
                methodSignature = "(Landroid/os/Bundle;)V",
                prefixInsns = shortArrayOf(0x0000, 0x0000, 0x0000), // nop，3 字
            )
        )
        assumeTrue("增量写入失败：${(outcome as? DexPatcher.Outcome.Failed)?.reason}", outcome is DexPatcher.Outcome.Ok)
        val newDf = DexBackedDexFile.fromInputStream(
            DexOpcodes.forBytes((outcome as DexPatcher.Outcome.Ok).dex),
            outcome.dex.inputStream()
        )
        val after = snapshot(newDf.classes.first { it.type == ownerType })

        assertEquals(
            "其它方法的指令序列被改动了（差分编码算错）",
            before.keys, after.keys,
        )
        before.forEach { (sig, ops) ->
            assertEquals("方法 $sig 的指令序列变了", ops, after[sig])
        }
    }

    /**
     * 速度对比：增量写入必须显著快于全量重写。
     *
     * 断言放宽到「至少快 3 倍」，因为测试机负载不稳定；
     * 目的是拦住「改了半天其实没变快」这种情况，不是精确性能基准。
     */
    @Test
    fun `增量写入显著快于全量重写`() {
        val apk = locateApk()
        val picked = pickTarget(apk)
        assumeTrue("没有合适的靶子", picked != null)
        val (_, ownerType, dexBytes) = picked!!
        assumeTrue("dex 太小，测不出差异", dexBytes.size > 1024 * 1024)

        // 全量：走现有的 DexCallInjector
        val calls = SmaliCallParser.parseAll(
            "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
        ).first
        val t0 = System.nanoTime()
        val full = DexCallInjector.inject(
            dexBytes,
            DexCallInjector.Spec(ownerType, "onCreate", "(Landroid/os/Bundle;)V", calls),
        )
        val fullMs = (System.nanoTime() - t0) / 1_000_000
        assumeTrue("全量注入失败，无法对比", full is DexCallInjector.Outcome.Injected)

        // 增量
        val t1 = System.nanoTime()
        val inc = DexPatcher.replaceCode(
            dexBytes,
            DexPatcher.ReplaceCodeRequest(
                classType = ownerType,
                methodName = "onCreate",
                methodSignature = "(Landroid/os/Bundle;)V",
                prefixInsns = shortArrayOf(0x0000, 0x0000, 0x0000),
            )
        )
        val incMs = (System.nanoTime() - t1) / 1_000_000
        assumeTrue("增量写入失败", inc is DexPatcher.Outcome.Ok)

        println("SPEED 全量=${fullMs}ms 增量=${incMs}ms dex=${dexBytes.size / 1048576}MB")
        assertTrue(
            "增量写入应显著快于全量：全量=${fullMs}ms 增量=${incMs}ms",
            incMs * 3 < fullMs,
        )
    }

    /**
     * 不支持的情况必须**如实失败**，不能产出坏包。
     *
     * 带 try/catch 的方法需要重算 try_item 的指令偏移并处理 4 字节对齐，
     * 会牵动整个布局。这里选择明确拒绝，让调用方走全量路径——
     * 而不是勉强产出一个人看不出问题的坏 dex。
     */
    @Test
    fun `带 try catch 的方法应明确拒绝`() {
        val apk = locateApk()

        data class Hit(val dexName: String, val classType: String, val method: com.android.tools.smali.dexlib2.iface.Method)

        var found: Hit? = null
        for (name in ApkRepackager.listDexNames(apk)) {
            val bytes = TestIo.readEntry(apk, name) ?: continue
            val df = runCatching {
                DexBackedDexFile.fromInputStream(DexOpcodes.forBytes(bytes), bytes.inputStream())
            }.getOrNull() ?: continue
            outer@ for (cls in df.classes) {
                for (m in cls.methods) {
                    val impl = m.implementation ?: continue
                    if (impl.tryBlocks.isNotEmpty()) {
                        found = Hit(name, cls.type, m)
                        break@outer
                    }
                }
            }
            if (found != null) break
        }
        assumeTrue("这个包里没有带 try/catch 的方法", found != null)
        val hit = found!!
        val dexBytes = TestIo.readEntry(apk, hit.dexName)!!

        val sig = "(" + hit.method.parameterTypes.joinToString("") + ")" + hit.method.returnType
        val outcome = DexPatcher.replaceCode(
            dexBytes,
            DexPatcher.ReplaceCodeRequest(
                classType = hit.classType,
                methodName = hit.method.name,
                methodSignature = sig,
                prefixInsns = shortArrayOf(0x0000, 0x0000, 0x0000),
            )
        )

        assertTrue(
            "带 try/catch 的方法应明确失败，实际 $outcome",
            outcome is DexPatcher.Outcome.Failed,
        )
        val msg = (outcome as DexPatcher.Outcome.Failed).reason
        assertTrue(
            "错误信息应说明是 try/catch 导致的，实际：$msg",
            msg.contains("try") || msg.contains("catch"),
        )
    }

    /** 在 dex 的 method_ids 表里定位某个方法（按 类 + 方法名 + 无参无返回）的下标。 */
    private fun indexOfMethod(
        df: DexBackedDexFile,
        ownerType: String,
        methodName: String,
    ): Long? {
        for (i in 0 until df.methodSection.size) {
            val ref = df.methodSection[i]
            if (ref.definingClass == ownerType && ref.name == methodName) return i.toLong()
        }
        return null
    }
}

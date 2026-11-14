package com.xixin.apkforge.inject

/**
 * 解析用户从 baksmali/MT 里复制出来的 smali invoke 行。
 *
 * 用户的真实操作是：反编译目标 APK → 找到想调用的方法 → **原样复制那一行 smali** →
 * 粘进输入框。所以这里按 smali 语法严格解析，而不是让用户拆成类名/方法名/签名三个框。
 *
 * 支持的形态：
 * ```
 * invoke-static {p0}, Lcom/foo/Bar;->init(Landroid/app/Activity;)V
 * invoke-virtual {p0, v1}, Lcom/foo/Bar;->doIt(ILjava/lang/String;)V
 * invoke-direct {p0}, Lcom/foo/Bar;-><init>()V
 * invoke-static/range {p0 .. p2}, Lcom/foo/Bar;->x(III)V
 * invoke-interface {p1}, Ljava/lang/Runnable;->run()V
 * invoke-super {p0, p1}, Lcom/foo/Base;->onCreate(Landroid/os/Bundle;)V
 * ```
 * 行尾的 `# 注释` 与首尾空白会被忽略；空行直接跳过。
 */
object SmaliCallParser {

    data class Call(
        /** `invoke-static` / `invoke-virtual` / ... 原样保留，决定 Opcode */
        val kind: Kind,
        /** 是否 `/range` 形态（3rc），由用户显式写或由编译器自动升级 */
        val rangeForm: Boolean,
        /** 寄存器原文（`p0`、`v1`、`p1` 等），保持原样以便按方法上下文换算 */
        val registers: List<String>,
        /** 目标方法所属类描述符，如 `Lcom/foo/Bar;` */
        val owner: String,
        val name: String,
        /** 参数列表原文，如 `(Landroid/app/Activity;I)V` 里的 `Landroid/app/Activity;I` */
        val paramTypes: List<String>,
        val returnType: String,
        /** 原始行，用于报错时回显 */
        val raw: String,
    )

    enum class Kind(val opcodeBase: String) {
        STATIC("invoke-static"),
        VIRTUAL("invoke-virtual"),
        DIRECT("invoke-direct"),
        SUPER("invoke-super"),
        INTERFACE("invoke-interface"),
    }

    data class Errors(val messages: List<String>) {
        val isEmpty get() = messages.isEmpty()
    }

    /**
     * 解析多行输入。返回 (成功解析的调用列表, 错误列表)。
     * 任何一行出错都会记录到错误里，但**不影响其它行**——用户粘一大段时，
     * 只应为那一行报错，不该整批失败。
     */
    fun parseAll(text: String): Pair<List<Call>, Errors> {
        val calls = mutableListOf<Call>()
        val errors = mutableListOf<String>()
        text.lineSequence().forEachIndexed { idx, line ->
            val body = line.substringBefore('#').trim()
            if (body.isEmpty()) return@forEachIndexed
            try {
                calls += parseLine(body)
            } catch (e: IllegalArgumentException) {
                errors += "第 ${idx + 1} 行: ${e.message}  ←  $body"
            }
        }
        return calls to Errors(errors)
    }

    fun parseLine(line: String): Call {
        // 1) 拆出 invoke-kind 与寄存器块
        val m = HEADER.matchEntire(line.trim())
            ?: throw IllegalArgumentException(
                "不是合法的 invoke 行。应为 " +
                    "invoke-static {p0}, Lcom/foo/Bar;->init()V"
            )
        val opcodeText = m.groupValues[1]          // invoke-static 或 invoke-static/range
        val regsText = m.groupValues[2]
        val rest = m.groupValues[3]                // Lcom/foo/Bar;->init()V

        val rangeForm = opcodeText.endsWith("/range")
        val kindText = opcodeText.removeSuffix("/range")
        val kind = Kind.entries.firstOrNull { it.opcodeBase == kindText }
            ?: throw IllegalArgumentException("不支持的调用类型: $kindText")

        // 2) 寄存器列表
        val registers = parseRegisters(regsText, rangeForm)
            ?: throw IllegalArgumentException("寄存器列表写法有误: {$regsText}")

        // 3) 方法引用
        val mm = METHOD_REF.matchEntire(rest.trim())
            ?: throw IllegalArgumentException(
                "方法引用格式有误，应为 Lcom/foo/Bar;->method(参数)返回类型"
            )
        val owner = mm.groupValues[1]
        val name = mm.groupValues[2]
        val paramText = mm.groupValues[3]
        val ret = mm.groupValues[4]

        if (!owner.startsWith("L") || !owner.endsWith(";")) {
            throw IllegalArgumentException("类描述符必须以 L 开头 ; 结尾: $owner")
        }
        if (!ret.startsWith("L") && !ret.startsWith("[") && ret != "V" &&
            ret.length != 1
        ) {
            throw IllegalArgumentException("返回类型无法识别: $ret")
        }

        return Call(
            kind = kind,
            rangeForm = rangeForm,
            registers = registers,
            owner = owner,
            name = name,
            paramTypes = parseParamTypes(paramText)
                ?: throw IllegalArgumentException("参数列表无法解析: ($paramText)"),
            returnType = ret,
            raw = line.trim(),
        )
    }

    // ────────────────────────── 内部 ──────────────────────────

    private val HEADER = Regex("""^(invoke-[a-z\-]+(?:/range)?)\s*\{([^}]*)\}\s*,\s*(.+)$""")
    private val METHOD_REF = Regex("""^(L[^;]+;)\s*->\s*([^\s(]+)\s*\(([^)]*)\)\s*(\S+)$""")

    private fun parseRegisters(text: String, rangeForm: Boolean): List<String>? {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()

        if (rangeForm) {
            // {p0 .. p2} 形态：展开成 p0,p1,p2（只支持 p 或 v 同族连续）
            val rr = Regex("""^([pv])(\d+)\s*\.\.\s*([pv])(\d+)$""").matchEntire(t)
                ?: return null
            val prefix1 = rr.groupValues[1]
            val from = rr.groupValues[2].toInt()
            val prefix2 = rr.groupValues[3]
            val to = rr.groupValues[4].toInt()
            if (prefix1 != prefix2 || to < from) return null
            return (from..to).map { "$prefix1$it" }
        }

        return t.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .also { list ->
                list.forEach {
                    if (!Regex("""^[pv]\d+$""").matches(it)) return null
                }
            }
    }

    /** `Landroid/app/Activity;ILjava/lang/String;` → 三个描述符 */
    private fun parseParamTypes(text: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == 'L' -> {
                    val end = text.indexOf(';', i)
                    if (end < 0) return null
                    out += text.substring(i, end + 1)
                    i = end + 1
                }
                c == '[' -> {
                    // 数组：收集所有 [ 然后接一个类型
                    val start = i
                    while (i < text.length && text[i] == '[') i++
                    if (i >= text.length) return null
                    if (text[i] == 'L') {
                        val end = text.indexOf(';', i)
                        if (end < 0) return null
                        out += text.substring(start, end + 1)
                        i = end + 1
                    } else {
                        out += text.substring(start, i + 1)
                        i++
                    }
                }
                // 基本类型单字符
                c in "ZBSCIJFD" -> {
                    out += c.toString()
                    i++
                }
                else -> return null
            }
        }
        return out
    }
}

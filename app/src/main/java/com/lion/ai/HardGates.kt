package com.lion.ai

import org.json.JSONObject

/**
 * بوابات إلزامية تُنفَّذ في الكود لا في التعليمات، فتعمل بالتساوي مع أي مزود (Lion-1, Gemini, Groq ...).
 *
 *  1) لا تعديل لملف موجود قبل قراءته في هذه الجلسة.
 *  2) لا رفع إلى GitHub بينما كود عُدّل ولم يجتز فحصاً بعد آخر تعديل.
 *  3) حدّ أقصى للمحاولات الفاشلة المتطابقة ثم تسليم بشري مع تشخيص منظّم.
 *
 * الحالة هنا لكل تشغيل للوكيل، والمنطق صرف (بلا أندرويد) ليُختبر على JVM.
 */
internal class HardGateState {
    private val known = HashSet<String>()

    /** كود عُدّل منذ آخر فحص ناجح. */
    var dirty: Boolean = false
        private set

    /** آخر فحص أُجري فشل ولم يُعالَج بعد. */
    var lastVerifyFailed: Boolean = false
        private set

    /** تم تشغيل أي أداة فحص مرة واحدة على الأقل. */
    var verifiedOnce: Boolean = false
        private set

    var wroteCode: Boolean = false
        private set

    /** يُستدعى بعد كل أداة: يحدّث الحالة من النتيجة الفعلية. */
    fun observe(tool: String, args: JSONObject, ok: Boolean, output: String) {
        if (tool in READ_TOOLS && ok) {
            readPaths(tool, args).forEach { known.add(norm(it)) }
        }
        if (tool in LOCAL_WRITE_TOOLS && ok) {
            val path = args.optString("path")
            if (path.isNotBlank()) known.add(norm(path)) // ما كتبه الوكيل بنفسه يعرف محتواه
            if (isCodePath(path) || path.isBlank()) {
                dirty = true
                wroteCode = true
            }
        }
        if (tool in VERIFY_TOOLS) {
            verifiedOnce = true
            val passed = ok && !looksFailed(output)
            lastVerifyFailed = !passed
            if (passed) dirty = false
        }
    }

    /** null = مسموح، وإلا نص رفض يُعاد للنموذج كنتيجة أداة فاشلة. */
    fun check(tool: String, args: JSONObject): String? {
        if (tool in EDIT_EXISTING_TOOLS) {
            val path = args.optString("path").trim()
            if (path.isNotBlank() && norm(path) !in known) {
                return "⛔ بوابة القراءة أولاً: لم تقرأ «$path» في هذه الجلسة. " +
                    "استدعِ file_read على الملف نفسه (أو file_read_many) ثم أعد التعديل بنص الملف الحالي حرفياً."
            }
        }
        if (tool in PUSH_TOOLS && !args.optBoolean("dry_run", false)) {
            if (dirty && !verifiedOnce) {
                return "⛔ بوابة الفحص قبل الرفع: عُدّل كود ولم يُشغَّل أي فحص. " +
                    "شغّل code_doctor أو gate_run (أو file_verify) أولاً، ثم أعد الرفع."
            }
            if (dirty) {
                return "⛔ بوابة الفحص قبل الرفع: عُدّلت ملفات بعد آخر فحص. أعد تشغيل code_doctor أو gate_run ثم ارفع."
            }
            if (lastVerifyFailed) {
                return "⛔ بوابة الفحص قبل الرفع: آخر فحص فشل. أصلح السبب الجذري ثم أعد نفس الفحص حتى يجتاز، ثم ارفع."
            }
        }
        return null
    }

    /** فجوات التحقق الصادقة التي تُعرض في ملخص النهاية. */
    fun gaps(): List<String> = buildList {
        if (wroteCode && !verifiedOnce) add("عُدّل كود ولم يُشغَّل أي فحص (code_doctor/gate_run).")
        else if (dirty) add("عُدّلت ملفات بعد آخر فحص ناجح.")
        if (lastVerifyFailed) add("آخر فحص فشل ولم يُعالَج.")
    }

    companion object {
        /** عدد مرات تكرار نفس الفشل (نفس الأداة والسبب) قبل التسليم البشري. */
        const val MAX_SAME_FAILURE = 3

        internal val READ_TOOLS = setOf("file_read", "read_file", "file_read_many")
        internal val EDIT_EXISTING_TOOLS = setOf("file_edit", "file_apply_patch", "code_patch")
        internal val LOCAL_WRITE_TOOLS = setOf("file_write", "file_append", "file_edit", "file_apply_patch", "code_patch")
        internal val PUSH_TOOLS = setOf("github_push_workspace", "github_commit_files")
        internal val VERIFY_TOOLS = setOf(
            "code_doctor", "gate_run", "file_verify", "code_analyze_file", "dependency_audit", "build_diagnose"
        )
        private val CODE_EXT = setOf(
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "xml", "gradle", "json", "yml", "yaml",
            "sh", "rs", "go", "dart", "c", "cpp", "h", "swift", "toml", "properties"
        )

        internal fun norm(path: String): String =
            path.trim().replace('\\', '/').removePrefix("./").removePrefix("/")

        internal fun isCodePath(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in CODE_EXT

        internal fun looksFailed(output: String): Boolean {
            val head = output.trimStart().take(80)
            return head.startsWith("⛔") || head.startsWith("❌") || output.contains("⛔ فشلت البوابة")
        }

        private fun readPaths(tool: String, args: JSONObject): List<String> {
            if (tool == "file_read_many") {
                val arr = args.optJSONArray("paths") ?: return emptyList()
                return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
            return listOf(args.optString("path")).filter { it.isNotBlank() }
        }

        /** تشخيص منظّم لكل توقف: ما جُرّب / النتيجة / السبب / الخطوة التالية. */
        fun diagnosis(tried: List<String>, result: String, cause: String, next: String): String = buildString {
            append("**التشخيص**\n")
            append("- ما جُرّب: ").append(tried.takeLast(4).joinToString("، ").ifBlank { "لا شيء مسجّل" }).append('\n')
            append("- النتيجة: ").append(result.ifBlank { "غير معروفة" }.take(200)).append('\n')
            append("- السبب: ").append(cause.ifBlank { "غير محدد" }.take(200)).append('\n')
            append("- الخطوة التالية: ").append(next.ifBlank { "راجع السجل وأعطني توجيهاً محدداً" }.take(200))
        }
    }
}

/**
 * خطّاف ما بعد التعديل (PostToolUse): فحص خفيف لتوازن الأقواس يُلحَق بنتيجة التعديل،
 * فيرى المزود الخطأ في الحال بدل اكتشافه عند البناء. استشاري ولا يمنع شيئاً.
 */
internal object QuickSyntax {
    private val CHECKED = setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "json", "gradle")

    fun supports(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in CHECKED

    /** null = سليم أو غير مدعوم، وإلا وصف أول خلل. */
    fun balanceProblem(path: String, text: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in CHECKED) return null
        val jsLike = ext in setOf("js", "ts", "tsx", "jsx", "json")
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                c == '\n' -> { line++; i++ }
                ext != "json" && c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                ext != "json" && c == '/' && next == '*' -> {
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                        if (text[i] == '\n') line++
                        i++
                    }
                    i += 2
                }
                c == '"' && text.startsWith("\"\"\"", i) && !jsLike -> {
                    i += 3
                    while (i < n && !text.startsWith("\"\"\"", i)) {
                        if (text[i] == '\n') line++
                        i++
                    }
                    i += 3
                }
                c == '"' || (jsLike && (c == '\'' || c == '`')) -> {
                    val quote = c
                    i++
                    while (i < n && text[i] != quote) {
                        if (text[i] == '\\') i++
                        else if (text[i] == '\n') {
                            line++
                            if (quote != '`') break
                        }
                        i++
                    }
                    i++
                }
                c == '\'' && !jsLike -> {
                    i++
                    while (i < n && text[i] != '\'' && text[i] != '\n') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                }
                c == '(' || c == '[' || c == '{' -> { stack.addLast(c to line); i++ }
                c == ')' || c == ']' || c == '}' -> {
                    val open = stack.removeLastOrNull()
                        ?: return "«$c» زائد في السطر $line بلا فتح مقابل"
                    val expected = when (open.first) { '(' -> ')'; '[' -> ']'; else -> '}' }
                    if (c != expected) {
                        return "«$c» في السطر $line لا يطابق «${open.first}» المفتوح في السطر ${open.second}"
                    }
                    i++
                }
                else -> i++
            }
        }
        val left = stack.lastOrNull() ?: return null
        return "«${left.first}» المفتوح في السطر ${left.second} لم يُغلق"
    }
}

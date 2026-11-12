package com.lion.ai

/**
 * بروتوكول الأدوات ([[TOOL]]، [[ASK]]، نتائج الأدوات…) تفصيل داخلي: لا يُعرض للمستخدم أبداً،
 * بل يظهر نشاط الأدوات الفعلي في «مسار التنفيذ» (قراءة ملف، كتابة، انتظار…).
 * يعالج أيضاً الصيغ المعطوبة التي ترسلها النماذج الضعيفة (إغلاق بـ [[TOOL]] بدل [[/TOOL]]، أو بلا إغلاق).
 */
object ToolMarkup {
    private val CLOSED = Regex("\\[\\[TOOL\\]\\].*?\\[\\[/?TOOL\\]\\]", RegexOption.DOT_MATCHES_ALL)
    private val FENCED_EMPTY = Regex("```[a-zA-Z-]*\\s*```")
    private val STRAY = Regex("\\[\\[/?TOOL\\]\\]")
    private val TOOL_RESULTS_HEADER = Regex("^نتائج الأدوات.*$", RegexOption.MULTILINE)

    /** أول JSON متوازن يبدأ عند أول `{` أو `[` بعد [from]، أو null. */
    fun balancedJson(text: String, from: Int): String? {
        var i = from
        while (i < text.length && text[i] != '{' && text[i] != '[') {
            if (!text[i].isWhitespace() && text[i] != '`') return null
            i++
        }
        if (i >= text.length) return null
        val open = text[i]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var esc = false
        var j = i
        while (j < text.length) {
            val c = text[j]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> { depth--; if (depth == 0) return text.substring(i, j + 1) }
            }
            j++
        }
        return null
    }

    /** نص خالٍ من كل أثر لكتل الأدوات (مغلقة أو معطوبة أو غير مغلقة). */
    fun strip(text: String): String {
        if (!text.contains("[[")) return text
        var out = text.replace(CLOSED, "")
        // كتلة بلا إغلاق: نزيل [[TOOL]] مع JSON المتوازن الذي يليها.
        var guard = 0
        while (guard++ < 20) {
            val idx = out.indexOf("[[TOOL]]")
            if (idx < 0) break
            val json = balancedJson(out, idx + "[[TOOL]]".length)
            val end = if (json != null) out.indexOf(json, idx) + json.length else out.length
            out = out.removeRange(idx, end.coerceAtLeast(idx + "[[TOOL]]".length))
        }
        return out.replace(STRAY, "").replace(FENCED_EMPTY, "").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** هل في النص أثر لبروتوكول الأدوات؟ */
    fun hasMarkup(text: String): Boolean = text.contains("[[TOOL]]") || text.contains("[[/TOOL]]")
}

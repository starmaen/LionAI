package com.lion.ai

/**
 * حالات تقنية تُعرض في المحادثة بأيقونات متجهية حديثة بدل الرموز التعبيرية التقليدية
 * (الدماغ، القلم الملوّن، المطرقة...). هذا الملف منطق صرف بلا Compose كي يُختبر على JVM؛
 * الرسم نفسه في StateGlyphIcon.kt.
 */
internal enum class StateGlyph {
    THINKING, WRITING, FILE, SEARCH, BUILD, UPLOAD, TERMINAL,
    SUCCESS, ERROR, WARNING, WAITING, SWITCH, SYNC, ASK, WEB, LIVE, GENERIC
}

internal object StateGlyphs {
    private fun symbols(glyph: StateGlyph, vararg codePoints: Int): List<Pair<Int, StateGlyph>> =
        codePoints.map { it to glyph }

    /** الرمز التعبيري التقليدي ← الحالة التقنية التي يمثّلها. */
    private val SYMBOLS: Map<Int, StateGlyph> = listOf(
        symbols(StateGlyph.THINKING, 0x1F9E0, 0x1F9E9, 0x1F9ED, 0x2728),
        symbols(StateGlyph.WRITING, 0x270F, 0x1F58A, 0x1F58B, 0x270D, 0x1F4DD),
        symbols(StateGlyph.FILE, 0x1F4C4, 0x1F4C3, 0x1F4D1, 0x1F4C1, 0x1F4C2, 0x1F4CE, 0x1F5C2, 0x1F4CB),
        symbols(StateGlyph.SEARCH, 0x1F50D, 0x1F50E),
        symbols(StateGlyph.BUILD, 0x1F6E0, 0x1F527, 0x1F528, 0x2699, 0x1F9F0),
        symbols(StateGlyph.UPLOAD, 0x1F4E4, 0x2B06, 0x1F680),
        symbols(StateGlyph.TERMINAL, 0x1F4BB, 0x2328, 0x1F5A5),
        symbols(StateGlyph.SUCCESS, 0x2705, 0x2714, 0x2713),
        symbols(StateGlyph.ERROR, 0x274C, 0x2716, 0x26D4, 0x1F6AB),
        symbols(StateGlyph.WARNING, 0x26A0),
        symbols(StateGlyph.WAITING, 0x23F3, 0x231B, 0x23F8, 0x23F1),
        symbols(StateGlyph.SWITCH, 0x21AA, 0x1F500),
        symbols(StateGlyph.SYNC, 0x1F504, 0x1F501, 0x25B6),
        symbols(StateGlyph.ASK, 0x1F4AC),
        symbols(StateGlyph.WEB, 0x1F310),
        symbols(StateGlyph.LIVE, 0x1F534, 0x1F7E2)
    ).flatten().toMap()

    /** كلمات تحدد طبيعة العملية؛ الترتيب مهم (الأخص أولاً). */
    private val KEYWORDS: List<Pair<StateGlyph, List<String>>> = listOf(
        StateGlyph.THINKING to listOf("يخطط", "تخطيط", "يفكر", "تفكير", "thinking", "planning"),
        StateGlyph.SWITCH to listOf("مزود بديل", "بديل", "تحويل", "switch", "failover"),
        StateGlyph.ASK to listOf("بانتظار ردك", "بانتظار قرارك", "بانتظار إذنك", "بانتظار إعداد"),
        StateGlyph.TERMINAL to listOf("shell", "exec", "command", "bash", "terminal", "code_run"),
        StateGlyph.UPLOAD to listOf("push", "commit", "upload", "release", "merge", "pull_request", "رفع"),
        StateGlyph.BUILD to listOf(
            "build", "workflow", "gradle", "dispatch", "wait_run", "run_status", "actions",
            "بناء", "البناء", "diagnose", "doctor", "heal"
        ),
        StateGlyph.WAITING to listOf("بانتظار", "مشغول", "أعيد المحاولة", "retry", "waiting", "مؤقتاً"),
        StateGlyph.WRITING to listOf(
            "write", "edit", "patch", "replace", "create", "save", "append",
            "كتاب", "تعديل", "عدّل", "أنشئ"
        ),
        StateGlyph.SEARCH to listOf("search", "grep", "find", "بحث", "ابحث"),
        StateGlyph.FILE to listOf(
            "read", "view", "tree", "list", "open", "glob", "file", "قراءة", "اقرأ", "ملف", "فحص"
        ),
        StateGlyph.WEB to listOf("http", "web", "صفحة", "موقع")
    )

    private fun isInvisible(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3

    /** رموز تعبيرية/تصويرية فقط، بلا الأسهم العادية (←) ولا علامات الترقيم. */
    fun isPictograph(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x23E9..0x23FF || cp == 0x231A || cp == 0x231B || cp == 0x2328 ||
            cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE || cp == 0x21A9 || cp == 0x21AA

    /** في بداية السطر نزيل أيضاً الأسهم والرموز التقنية العامة (↪ ⏸ ⌛). */
    private fun isLeadingSymbol(cp: Int): Boolean =
        isPictograph(cp) || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF

    /** رموز بداية السطر بالترتيب (تُتجاوز المسافات والمحددات غير المرئية بينها). */
    fun leadingSymbols(line: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            if (isInvisible(cp) || Character.isWhitespace(cp)) {
                // تُتجاوز
            } else if (isLeadingSymbol(cp)) {
                out.add(cp)
            } else {
                break
            }
            i += Character.charCount(cp)
        }
        return out
    }

    /** السطر بلا الرموز التعبيرية في بدايته: «⏳ 🛠️ shell_exec» ← «shell_exec». */
    fun clean(line: String): String {
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            if (isInvisible(cp) || Character.isWhitespace(cp) || isLeadingSymbol(cp)) {
                i += Character.charCount(cp)
            } else {
                break
            }
        }
        return line.substring(i).trim()
    }

    /** يزيل الرموز التعبيرية من أي موضع ويُبقي الأسهم وعلامات الترقيم. */
    fun removePictographs(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isPictograph(cp) && !isInvisible(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** حالة من نص حر (اسم أداة أو وصف خطوة)، أو null إن لم تُعرف. */
    fun forText(text: String): StateGlyph? {
        val lower = text.lowercase()
        return KEYWORDS.firstOrNull { (_, words) -> words.any { lower.contains(it) } }?.first
    }

    fun forTool(tool: String): StateGlyph = when (tool) {
        "provider" -> StateGlyph.SWITCH
        "note" -> StateGlyph.ASK
        else -> forText(tool) ?: StateGlyph.GENERIC
    }

    /**
     * أيقونة سطر تقدّم: حالة النجاح/الفشل/التحذير في الرمز الأول تغلب؛ وإلا فكلمات العملية؛
     * وإلا الرمز الأول نفسه.
     */
    fun forLine(line: String): StateGlyph {
        val first = leadingSymbols(line).mapNotNull { SYMBOLS[it] }.firstOrNull()
        if (first != null &&
            (first == StateGlyph.SUCCESS || first == StateGlyph.ERROR || first == StateGlyph.WARNING)
        ) return first
        return forText(clean(line)) ?: first ?: StateGlyph.GENERIC
    }
}

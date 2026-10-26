package com.lion.ai

import java.util.Calendar

/**
 * ضوابط جودة الردود: ترفض الجداول غير المطلوبة، والتكرار، والتواريخ المستقبلية،
 * والردود التي لا تشبه لغة السؤال. كما تنظّف Markdown قبل عرضه في الفقاعة.
 */
object AnswerGuard {
    private val TABLE_SEPARATOR = Regex(
        "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$",
        RegexOption.MULTILINE
    )
    private val PLACEHOLDER = Regex(
        "\\[(مختصر|اسم|سنة|سنه|ملخص|year)]|TBD|N/A",
        RegexOption.IGNORE_CASE
    )
    private val YEAR = Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b")
    private val ELLIPSIS = Regex("\\.{3,}|…")
    private val TABLE_CELL_SEPARATOR = Regex(":?-{3,}:?")
    private val UNCERTAIN = Regex("لست متأكد|لا أعرف|لا اعرف|لا أملك|لا املك|لا أمتلك|لا امتلك")
    private val FUTURE_CONTEXT = Regex("رؤية|خطة|مستقبل|متوقع|سيتم|سيكون|سيناريو|خطه|انتخابات|أولمبياد")

    fun isLowQuality(reply: String, kind: TaskKind, query: String = ""): Boolean {
        val t = reply.trim()
        if (t.isBlank()) return true
        if (LlmClient.isErrorReply(t)) return true
        if (t.contains('\uFFFD')) return true

        val allowsTable = query.contains("جدول") || query.contains("table", ignoreCase = true)
        if (!allowsTable && (kind == TaskKind.FACTUAL || kind == TaskKind.LONG_DOC)) {
            if (t.count { it == '|' } >= 4) return true
            if (TABLE_SEPARATOR.containsMatchIn(t)) return true
        }
        val strict = kind == TaskKind.FACTUAL || kind == TaskKind.LONG_DOC
        if (strict && PLACEHOLDER.containsMatchIn(t)) return true

        if (strict) {
            val lines = t.lines().map { it.trim() }.filter { it.length >= 8 }
            if (lines.size >= 3 && lines.groupingBy { it }.eachCount().values.any { it >= 3 }) return true
        }
        if (strict || kind == TaskKind.GENERAL) {
            if (looksLikeGibberish(t)) return true
            if (hasLoop(t)) return true
        }

        if (UNCERTAIN.containsMatchIn(t)) return false

        if (strict) {
            if (!FUTURE_CONTEXT.containsMatchIn(t)) {
                val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 2
                if (YEAR.findAll(t).any { it.value.toInt() > maxYear }) return true
            }
            if (query.any { it in '\u0600'..'\u06FF' } && t.none { it in '\u0600'..'\u06FF' }) return true
            if (ELLIPSIS.findAll(t).count() >= 2) return true
        }
        return false
    }

    /** نص مليء بالرموز أو بلا حروف كافية: غالباً هلوسة أو رد مبتور. */
    private fun looksLikeGibberish(t: String): Boolean {
        if (t.count { it == '|' } >= 4) return false
        val visible = t.filterNot { it.isWhitespace() }
        if (visible.length < 25) return false
        val letters = visible.count { it.isLetter() }
        if (letters * 2 < visible.length) return true
        val symbols = visible.count {
            !it.isLetterOrDigit() && it !in "،؛:!?.,()[]{}«»\"'%-/|•—…" &&
                it !in '\u064B'..'\u065F'
        }
        return symbols * 4 > visible.length
    }

    /** تكرار نفس أربع كلمات ثلاث مرات = حلقة نماذج شائعة. */
    private fun hasLoop(t: String): Boolean {
        val words = TextNorm.forMatch(t).split(Regex("\\s+")).filter { it.length > 2 }
        if (words.size < 12) return false
        val counts = HashMap<String, Int>()
        val n = 4
        for (i in 0..words.size - n) {
            val key = words.subList(i, i + n).joinToString(" ")
            val count = (counts[key] ?: 0) + 1
            if (count >= 3) return true
            counts[key] = count
        }
        return false
    }

    /** يحوّل جداول Markdown إلى نقاط، ويزيل علامات التنسيق التي لا يعرضها التطبيق. */
    fun sanitizeForDisplay(input: String): String {
        val out = mutableListOf<String>()
        var inTable = false
        for (raw in input.lines()) {
            val line = raw.trim()
            if (line.count { it == '|' } >= 2) {
                val cells = line.trim('|').split('|').map { it.trim() }
                if (cells.all { TABLE_CELL_SEPARATOR.matches(it) }) continue
                if (!inTable) {
                    inTable = true
                    continue
                }
                val parts = cells.filter { it.isNotBlank() }
                if (parts.isNotEmpty()) out.add("• " + parts.joinToString(" — "))
                continue
            }
            inTable = false
            out.add(raw)
        }
        return out.joinToString("\n")
            .replace(Regex("\\*\\*(.*?)\\*\\*")) { it.groupValues[1] }
            .replace(Regex("__(.*?)__")) { it.groupValues[1] }
            .replace(Regex("`([^`]*)`")) { it.groupValues[1] }
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) {
                it.groupValues[1] + " (" + it.groupValues[2] + ")"
            }
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("^\\s*[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
            .replace(Regex("~~(.*?)~~"), "$1")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

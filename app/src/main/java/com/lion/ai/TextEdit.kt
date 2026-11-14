package com.lion.ai

/**
 * تعديل نصي بالاستبدال (old ← new): أوثق بكثير من unified diff لنماذج صفحات الويب التي لا تستطيع
 * إعادة سياق الأسطر وأرقامها بدقة. يطابق حرفياً أولاً، ثم بتطبيع المسافات والإزاحة سطراً سطراً،
 * ويفشل برسالة تدل على أقرب موضع بدل «فشل hunk».
 */
object TextEdit {
    data class Result(val text: String, val count: Int)

    fun replace(text: String, old: String, new: String, all: Boolean = false): Result {
        require(old.isNotBlank()) { "النص القديم (old) فارغ؛ ضع النص الحالي بالضبط الذي تريد استبداله." }
        val src = text.replace("\r\n", "\n")
        val oldN = old.replace("\r\n", "\n")
        val newN = new.replace("\r\n", "\n")
        val exact = countOf(src, oldN)
        if (exact == 1 || (exact > 1 && all)) {
            return Result(src.replace(oldN, newN), exact)
        }
        require(exact == 0) {
            "النص القديم يتكرر $exact مرات؛ أضف سطراً مجاوراً ليصير فريداً أو مرّر replace_all=true."
        }
        // مطابقة بتطبيع المسافات سطراً سطراً.
        val lines = src.split("\n")
        val oldLines = oldN.trim('\n').split("\n").map { it.trim() }
        val hits = (0..lines.size - oldLines.size).filter { i ->
            oldLines.indices.all { j -> lines[i + j].trim() == oldLines[j] }
        }
        if (hits.size == 1 || (hits.size > 1 && all)) {
            val out = lines.toMutableList()
            // من الأخير للأول كي لا تتغير الفهارس.
            for (i in hits.sortedDescending()) {
                val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
                val replacement = reindent(newN.trim('\n').split("\n"), indent)
                repeat(oldLines.size) { out.removeAt(i) }
                out.addAll(i, replacement)
            }
            return Result(out.joinToString("\n"), hits.size)
        }
        if (hits.isEmpty()) {
            // آخر محاولة: تجاهل كل المسافات والأسطر الجديدة (النماذج كثيراً ما تغيّر التفاف السطور).
            val keep = ArrayList<Int>(src.length)
            val sb = StringBuilder(src.length)
            src.forEachIndexed { i, ch -> if (!ch.isWhitespace()) { keep.add(i); sb.append(ch) } }
            val needle = oldN.filter { !it.isWhitespace() }
            if (needle.length >= 12) {
                val first = sb.indexOf(needle)
                if (first >= 0 && sb.indexOf(needle, first + needle.length) < 0) {
                    val from = keep[first]
                    val to = keep[first + needle.length - 1] + 1
                    return Result(src.substring(0, from) + newN + src.substring(to), 1)
                }
            }
        }
        require(hits.isEmpty()) { "النص القديم يطابق ${hits.size} مواضع بعد تطبيع المسافات؛ أضف سطراً مجاوراً ليصير فريداً." }
        throw IllegalArgumentException(notFoundHint(lines, oldLines))
    }

    private fun countOf(text: String, part: String): Int {
        var n = 0
        var i = text.indexOf(part)
        while (i >= 0) { n++; i = text.indexOf(part, i + part.length) }
        return n
    }

    private fun reindent(newLines: List<String>, targetIndent: String): List<String> {
        val minIndent = newLines.filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return newLines.map { l -> if (l.isBlank()) "" else targetIndent + l.drop(minIndent) }
    }

    private fun notFoundHint(lines: List<String>, oldLines: List<String>): String {
        val first = oldLines.firstOrNull { it.length >= 6 }.orEmpty()
        // مطابقة تقريبية: نجرّب بادئات أقصر تدريجياً حتى نجد أقرب سطر.
        val at = listOf(30, 20, 14, 10).asSequence()
            .map { first.take(it) }.filter { it.length >= 8 }.distinct()
            .map { key -> lines.indexOfFirst { l -> l.contains(key) } }
            .firstOrNull { it >= 0 } ?: -1
        val near = if (at >= 0) {
            val from = (at - 1).coerceAtLeast(0)
            val to = (at + 4).coerceAtMost(lines.size - 1)
            "\nأقرب موضع (السطر ${at + 1}):\n" + (from..to).joinToString("\n") { "${it + 1}: ${lines[it].take(140)}" }
        } else "\nلم أجد حتى أول سطر منه؛ اقرأ الملف (file_read بنطاق أسطر) وانسخ النص الحالي حرفياً."
        return "النص القديم غير موجود في الملف كما كتبته.$near"
    }
}

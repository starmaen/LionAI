package com.lion.ai

/** سجل نتائج حيّ: يضمن أن أي توقف يحمل معه ما عُرف حتى تلك اللحظة. */
internal class FindingsLedger {
    private class Cover(var shown: Int, var total: Int)

    private val coverage = LinkedHashMap<String, Cover>()
    private val facts = ArrayList<String>()
    private val problems = ArrayList<String>()
    private val range = Regex("\\(الأسطر\\s*(\\d+)[–-](\\d+)\\s*من\\s*(\\d+)\\)")

    fun record(tool: String, target: String, ok: Boolean, output: String) {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
        if (!ok) {
            problems.add("$tool $target: " + lines.firstOrNull().orEmpty().take(120))
            if (problems.size > 20) problems.removeAt(0)
            return
        }
        val m = range.find(output.take(400))
        if (m != null && target.isNotBlank()) {
            val (_, end, total) = m.destructured
            val c = coverage.getOrPut(target) { Cover(0, 0) }
            c.shown = maxOf(c.shown, end.toInt())
            c.total = total.toInt()
            return
        }
        facts.add("$tool $target: " + lines.take(3).joinToString(" | ").take(220))
        if (facts.size > 40) facts.removeAt(0)
    }

    fun render(): String {
        if (coverage.isEmpty() && facts.isEmpty() && problems.isEmpty()) return ""
        val sb = StringBuilder("\n\n**ما توصّل إليه الفحص حتى الآن**\n")
        if (coverage.isNotEmpty()) {
            sb.append("- التغطية: ").append(
                coverage.entries.toList().takeLast(8).joinToString("، ") { (p, c) ->
                    p.takeLast(40) + " " + c.shown + "/" + c.total + if (c.shown >= c.total) " ✓" else ""
                }
            ).append('\n')
        }
        facts.takeLast(8).forEach { sb.append("- وُجد: ").append(it).append('\n') }
        problems.takeLast(4).forEach { sb.append("- تعذّر: ").append(it).append('\n') }
        val partial = coverage.filterValues { it.shown < it.total }.keys
        if (partial.isNotEmpty()) {
            sb.append("- لم يُقرأ بالكامل: ").append(partial.take(4).joinToString("، "))
                .append(" — المتابعة بـ start_line التالي\n")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        const val REPORT_NOW =
            "توقف عن جمع المزيد واكتب تقرير الفحص بالعربية الآن: " +
                "١) ما وجدته (مع الملف والسطر) ٢) ما تأكدت أنه غير موجود ٣) ما لم تفحصه بعد ولماذا " +
                "٤) الخطوة التالية المقترحة. لا تستدعِ أداة إلا إذا كان بند واحد ناقصاً ويمنعك من الحكم."
    }
}

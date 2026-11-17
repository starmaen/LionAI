package com.lion.ai

/**
 * خريطة المشروع: أسماء الملفات مع تواقيع الأصناف والدوال فقط (بلا أجسام)،
 * فيفهم المزود بنية مشروع كبير بآلاف الأسطر بكلفة رموز قليلة ثم يقرأ نطاق أسطر محدداً عند الحاجة.
 * مستقلة عن أندرويد وقابلة للاختبار؛ أداة repo_map تغذّيها بنصوص الملفات.
 */
internal object RepoMap {
    private const val MAX_SIGNATURES_PER_FILE = 60
    private const val MAX_SIGNATURE_CHARS = 110

    private val KOTLIN_JAVA = Regex(
        "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|internal|override|open|abstract|data|sealed|enum|" +
            "inline|suspend|static|final|companion|fun)\\s+)*(?:class|interface|object|fun|typealias)\\s+\\S.*$"
    )
    private val PYTHON = Regex("^\\s*(?:async\\s+)?(?:class|def)\\s+\\w+.*$")
    private val SCRIPT = Regex(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?(?:async\\s+)?(?:function\\*?|class|interface|enum|type)\\s+\\w+.*$"
    )

    fun supports(path: String): Boolean = extension(path) in SUPPORTED

    private val SUPPORTED = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx")

    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase()

    /** تواقيع ملف واحد مع مسافة بادئة مبسّطة (مستوى واحد لكل 4 مسافات، حدّها الأقصى 3). */
    fun signatures(path: String, text: String): List<String> {
        val pattern = when (extension(path)) {
            "kt", "kts", "java" -> KOTLIN_JAVA
            "py" -> PYTHON
            "js", "jsx", "ts", "tsx" -> SCRIPT
            else -> return emptyList()
        }
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            if (out.size >= MAX_SIGNATURES_PER_FILE) break
            if (!pattern.matches(line)) continue
            val indent = (line.length - line.trimStart().length) / 4
            out.add("  ".repeat(indent.coerceIn(0, 3)) + clip(line.trim()))
        }
        return out
    }

    private fun clip(signature: String): String {
        val brace = signature.indexOf('{')
        var head = if (brace > 0) signature.substring(0, brace) else signature
        val expressionBody = head.indexOf(") =")
        if (expressionBody > 0) head = head.substring(0, expressionBody + 1)
        val trimmed = head.trimEnd(' ', ':')
        return if (trimmed.length > MAX_SIGNATURE_CHARS) trimmed.take(MAX_SIGNATURE_CHARS) + "…" else trimmed
    }

    /** يجمع الخريطة داخل وسم <project_map> ضمن ميزانية أحرف، مع إشارة صريحة لما لم يتّسع. */
    fun render(entries: List<Entry>, maxChars: Int): String {
        val sb = StringBuilder("<project_map files=\"${entries.size}\">\n")
        var shown = 0
        for (entry in entries) {
            val block = StringBuilder(entry.path).append(" (").append(entry.lines).append(" سطر)\n")
            entry.signatures.forEach { block.append("  ").append(it).append('\n') }
            if (sb.length + block.length > maxChars) break
            sb.append(block)
            shown++
        }
        if (shown < entries.size) sb.append("… لم تتسع ${entries.size - shown} ملفات؛ ضيّق path أو ارفع max_chars.\n")
        sb.append("</project_map>")
        return sb.toString()
    }

    class Entry(val path: String, val lines: Int, val signatures: List<String>)
}

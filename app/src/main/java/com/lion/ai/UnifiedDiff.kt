package com.lion.ai

/**
 * تطبيق patches بصيغة unified diff داخل مساحة العمل.
 * لا يعتمد على أي مكتبة خارجية، ويرفض التطبيق إذا لم تتطابق السياقات
 * بدلاً من إنتاج ملف تالف.
 */
object UnifiedDiff {
    data class Applied(val text: String, val added: Int, val removed: Int, val hunks: Int)

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<String>
    )

    private val HUNK_HEADER = Regex("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$")

    fun apply(original: String, patch: String): Applied {
        val originalNorm = normalize(original)
        val patchNorm = normalize(patch)
        val hadTrailingNewline = originalNorm.endsWith("\n") || originalNorm.isEmpty()
        val source = when {
            originalNorm.isEmpty() -> emptyList()
            hadTrailingNewline -> originalNorm.dropLast(1).split("\n")
            else -> originalNorm.split("\n")
        }
        val hunks = parse(patchNorm)
        if (hunks.isEmpty()) throw IllegalArgumentException("لا يوجد أي hunk في الـ patch")

        val output = ArrayList<String>(source.size + 16)
        var cursor = 0
        var added = 0
        var removed = 0

        hunks.forEachIndexed { index, hunk ->
            val target = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            if (target < cursor) {
                throw IllegalArgumentException("hunk رقم ${index + 1} يتداخل مع ما قبله")
            }
            if (target > source.size) {
                throw IllegalArgumentException("hunk رقم ${index + 1} يبدأ بعد نهاية الملف")
            }
            while (cursor < target) output.add(source[cursor++])

            hunk.lines.forEach { raw ->
                when {
                    raw.startsWith("+") -> {
                        output.add(raw.substring(1))
                        added++
                    }
                    raw.startsWith("-") -> {
                        val expected = raw.substring(1)
                        if (cursor >= source.size || source[cursor] != expected) {
                            throw mismatch(index + 1, cursor, expected, source.getOrNull(cursor))
                        }
                        cursor++
                        removed++
                    }
                    raw.startsWith(" ") || raw.isEmpty() -> {
                        val expected = raw.removePrefix(" ")
                        if (cursor >= source.size || source[cursor] != expected) {
                            throw mismatch(index + 1, cursor, expected, source.getOrNull(cursor))
                        }
                        output.add(source[cursor++])
                    }
                    raw.startsWith("\\") -> Unit
                    else -> throw IllegalArgumentException("سطر غير مفهوم في hunk رقم ${index + 1}: $raw")
                }
            }
        }
        while (cursor < source.size) output.add(source[cursor++])

        val text = output.joinToString("\n") + if (output.isNotEmpty() && hadTrailingNewline) "\n" else ""
        return Applied(text, added, removed, hunks.size)
    }

    private fun mismatch(hunk: Int, lineIndex: Int, expected: String, actual: String?): IllegalArgumentException {
        val shown = actual?.take(160) ?: "<نهاية الملف>"
        return IllegalArgumentException(
            "فشل hunk رقم $hunk عند السطر ${lineIndex + 1}: المتوقع «${expected.take(160)}» والموجود «$shown»"
        )
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun parse(patch: String): List<Hunk> {
        val lines = patch.split("\n")
        val hunks = mutableListOf<Hunk>()
        var index = 0
        while (index < lines.size) {
            val header = HUNK_HEADER.find(lines[index])
            if (header == null) {
                index++
                continue
            }
            val oldStart = header.groupValues[1].toIntOrNull() ?: 0
            val oldCount = header.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: 1
            val newStart = header.groupValues[3].toIntOrNull() ?: 0
            val newCount = header.groupValues[4].ifBlank { "1" }.toIntOrNull() ?: 1
            index++
            val body = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith("@@")) {
                val line = lines[index]
                if (!isPatchBody(line)) break
                body.add(line)
                index++
            }
            // احذف آخر سطر فارغ ناتج عن نهاية النص.
            while (body.isNotEmpty() && body.last().isEmpty() && index >= lines.size) body.removeAt(body.size - 1)
            hunks.add(Hunk(oldStart, oldCount, newStart, newCount, body))
        }
        return hunks
    }

    private fun isPatchBody(line: String): Boolean =
        line.isEmpty() || line.startsWith(" ") || line.startsWith("+") ||
            line.startsWith("-") || line.startsWith("\\")

    /** يبني patch مبسّطاً لمقارنة نصين (للعرض والمراجعة قبل الدفع). */
    fun makePatch(path: String, before: String, after: String): String {
        if (before == after) return ""
        val a = normalize(before).split("\n")
        val b = normalize(after).split("\n")
        val builder = StringBuilder()
        builder.append("--- a/").append(path).append('\n')
        builder.append("+++ b/").append(path).append('\n')
        builder.append("@@ -1,").append(a.size).append(" +1,").append(b.size).append(" @@\n")
        a.forEach { builder.append('-').append(it).append('\n') }
        b.forEach { builder.append('+').append(it).append('\n') }
        return builder.toString()
    }
}

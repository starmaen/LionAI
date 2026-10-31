package com.lion.ai

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** أدوات مساعدة محلية صغيرة تُستخدم من واجهة أدوات المطوّر ومن الذكاء الاصطناعي. */
object UtilTools {

    // ---------------- Cron ----------------

    private fun matches(field: String, value: Int): Boolean {
        val text = field.trim().lowercase()
        if (text == "*" || text == "?") return true
        text.split(',').forEach { part ->
            val stepSplit = part.split('/')
            val range = stepSplit[0]
            val step = stepSplit.getOrNull(1)?.toIntOrNull()
            val bounds = when {
                range == "*" -> 0 to Int.MAX_VALUE
                range.contains('-') -> {
                    val a = range.substringBefore('-').toIntOrNull() ?: return@forEach
                    val b = range.substringAfter('-').toIntOrNull() ?: return@forEach
                    a to b
                }
                else -> {
                    val n = range.toIntOrNull() ?: return@forEach
                    n to n
                }
            }
            if (value in bounds.first..bounds.second) {
                if (step == null || step <= 0) return true
                if ((value - bounds.first) % step == 0) return true
            }
        }
        return false
    }

    private fun matchesDow(field: String, value: Int): Boolean {
        // 0 و7 كلاهما الأحد
        if (matches(field, value)) return true
        return value == 0 && matches(field, 7)
    }

    fun nextCron(fields: List<String>, from: LocalDateTime = LocalDateTime.now()): String? {
        if (fields.size != 5) return null
        var time = from.withSecond(0).withNano(0).plusMinutes(1)
        val limit = time.plusDays(366)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")
        while (time.isBefore(limit)) {
            if (matches(fields[3], time.monthValue) &&
                matches(fields[2], time.dayOfMonth) &&
                matchesDow(fields[4], time.dayOfWeek.value % 7) &&
                matches(fields[1], time.hour) &&
                matches(fields[0], time.minute)
            ) {
                return time.format(formatter)
            }
            time = time.plusMinutes(1)
        }
        return null
    }

    fun describeCron(fields: List<String>): String {
        if (fields.size != 5) return "تعبير Cron غير صالح"
        val minute = fields[0]
        val hour = fields[1]
        val dom = fields[2]
        val month = fields[3]
        val dow = fields[4]
        val time = when {
            minute == "*" && hour == "*" -> "كل دقيقة"
            minute.startsWith("*/") -> "كل ${minute.removePrefix("*/")} دقيقة"
            hour == "*" -> "كل ساعة عند الدقيقة $minute"
            else -> "الساعة $hour:${minute.padStart(2, '0')}"
        }
        val day = when {
            dom != "*" && dow != "*" -> "يوم $dom من الشهر أو يوم الأسبوع $dow"
            dom != "*" -> "يوم $dom من الشهر"
            dow != "*" -> "يوم الأسبوع $dow (0=الأحد)"
            else -> "كل يوم"
        }
        val monthText = if (month == "*") "" else " في الشهر $month"
        return "$time، $day$monthText"
    }

    // ---------------- Diff ----------------

    fun diffText(first: String, second: String, maxLines: Int = 1200): String {
        val left = first.lines()
        val right = second.lines()
        if (left.size > maxLines || right.size > maxLines) {
            return "النصان كبيران (${left.size} × ${right.size} سطر)."
        }
        val n = left.size
        val m = right.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (left[i] == right[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = StringBuilder()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                left[i] == right[j] -> { out.append("  ").append(left[i]).append('\n'); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.append("- ").append(left[i]).append('\n'); i++ }
                else -> { out.append("+ ").append(right[j]).append('\n'); j++ }
            }
        }
        while (i < n) { out.append("- ").append(left[i]).append('\n'); i++ }
        while (j < m) { out.append("+ ").append(right[j]).append('\n'); j++ }
        return out.toString().ifBlank { "لا فروق." }
    }

    // ---------------- CSV ----------------

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { row.add(cell.toString()); cell.clear() }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (cell.isNotEmpty() || row.isNotEmpty()) {
                        row.add(cell.toString())
                        rows.add(row)
                        row = mutableListOf()
                        cell.clear()
                    }
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    fun csv(text: String, query: String = ""): String {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return "CSV فارغ."
        val header = rows.first()
        val data = rows.drop(1)
        val normalized = query.lowercase()
        val limit = Regex("limit\\s+(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val selected = if (normalized.contains("*") || normalized.isBlank()) header.indices.toList() else {
            val requested = normalized.substringAfter("select", "").substringBefore("from")
                .split(',').map { it.trim() }.filter { it.isNotBlank() }
            requested.mapNotNull { name -> header.indexOfFirst { it.equals(name, true) }.takeIf { it >= 0 } }
                .ifEmpty { header.indices.toList() }
        }
        val builder = StringBuilder()
        builder.append("الصفوف: ${data.size} | الأعمدة: ${header.size}\n")
        builder.append(selected.joinToString(" | ") { header.getOrElse(it) { "" } }).append('\n')
        builder.append("-".repeat(60)).append('\n')
        data.take(limit.coerceIn(1, 500)).forEach { row ->
            builder.append(selected.joinToString(" | ") { row.getOrElse(it) { "" } }).append('\n')
        }
        if (data.size > limit) builder.append("… عُرض $limit من ${data.size} صفاً")
        return builder.toString()
    }

    // ---------------- Color ----------------

    fun color(raw: String): String {
        var value = raw.trim().removePrefix("#")
        if (value.length == 3) value = value.map { "$it$it" }.joinToString("")
        if (value.length != 6 || value.any { it !in "0123456789abcdefABCDEF" }) {
            throw IllegalArgumentException("لون HEX غير صالح. مثال: #FFB800")
        }
        val r = value.substring(0, 2).toInt(16)
        val g = value.substring(2, 4).toInt(16)
        val b = value.substring(4, 6).toInt(16)
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2
        val d = max - min
        val s = if (d == 0.0) 0.0 else d / (1 - Math.abs(2 * l - 1))
        val h = when {
            d == 0.0 -> 0.0
            max == rf -> 60 * (((gf - bf) / d) % 6)
            max == gf -> 60 * ((bf - rf) / d + 2)
            else -> 60 * ((rf - gf) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return "HEX: #${value.uppercase()}\nRGB: rgb($r, $g, $b)\nHSL: hsl(${h.toInt()}, ${(s * 100).toInt()}%, ${(l * 100).toInt()}%)\n" +
            "ARGB: #FF${value.uppercase()}\nInt: ${(0xFF000000L or (r shl 16).toLong() or (g shl 8).toLong() or b.toLong())}"
    }

    // ---------------- Misc ----------------

    fun timestamp(timezone: String = ""): String {
        val zone = runCatching { ZoneId.of(timezone.ifBlank { ZoneId.systemDefault().id }) }
            .getOrDefault(ZoneId.systemDefault())
        val now = LocalDateTime.now(zone)
        return "ISO: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\n" +
            "السنة: ${now.year} | الشهر: ${now.monthValue} | اليوم: ${now.dayOfMonth}\n" +
            "الوقت: ${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}\n" +
            "المنطقة: $zone"
    }
}

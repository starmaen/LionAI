package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * update_plan (نمط Codex): خطة خطوات بحالة واحدة «قيد التنفيذ» كحد أقصى.
 * تُحفظ لكل محادثة، فيتابعها مزود بديل من النقطة نفسها.
 */
internal object PlanTool {
    data class Step(val text: String, val status: String)

    private val STATUSES = setOf("pending", "in_progress", "completed")
    private fun key(chatId: String) = "plan_$chatId"

    /** يعيد (الخطوات، رسالة خطأ). */
    fun parse(args: JSONObject): Pair<List<Step>, String?> {
        val arr = args.optJSONArray("plan") ?: return emptyList<Step>() to "«plan» مطلوب: مصفوفة من {step,status}"
        if (arr.length() == 0) return emptyList<Step>() to "الخطة فارغة؛ أرسل خطوة واحدة على الأقل"
        val steps = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i)
            Step(o?.optString("step").orEmpty().trim(), (o?.optString("status").orEmpty().ifBlank { "pending" }).lowercase())
        }
        steps.forEachIndexed { i, s ->
            if (s.text.isBlank()) return emptyList<Step>() to "الخطوة ${i + 1} بلا نص"
            if (s.status !in STATUSES) return emptyList<Step>() to "حالة غير صالحة «${s.status}» في الخطوة ${i + 1}: pending أو in_progress أو completed"
        }
        if (steps.count { it.status == "in_progress" } > 1) {
            return emptyList<Step>() to "يُسمح بخطوة واحدة فقط in_progress في أي وقت"
        }
        return steps to null
    }

    fun render(steps: List<Step>): String = steps.mapIndexed { i, s ->
        val mark = when (s.status) { "completed" -> "✅"; "in_progress" -> "▶️"; else -> "⬜" }
        "$mark ${i + 1}. ${s.text}"
    }.joinToString("\n")

    fun save(prefs: SharedPreferences, chatId: String, steps: List<Step>) {
        if (chatId.isBlank()) return
        val arr = JSONArray()
        steps.forEach { arr.put(JSONObject().put("step", it.text).put("status", it.status)) }
        prefs.edit().putString(key(chatId), arr.toString()).apply()
    }

    fun read(prefs: SharedPreferences, chatId: String): List<Step> {
        if (chatId.isBlank()) return emptyList()
        val raw = prefs.getString(key(chatId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Step(arr.getJSONObject(it).optString("step"), arr.getJSONObject(it).optString("status")) }
        }.getOrDefault(emptyList())
    }

    fun openCount(steps: List<Step>): Int = steps.count { it.status != "completed" }
}

/** file_glob / file_grep (نمط Claude Code وCodex): بحث بالأنماط والتعابير النمطية مع سياق الأسطر. */
internal object SearchTools {
    private val SKIP_DIRS = setOf(".git", "build", "node_modules", ".gradle", ".idea")

    /** يحوّل نمط glob (** و * و ? و {a,b}) إلى تعبير نمطي على مسار نسبي بفواصل /. */
    fun globToRegex(glob: String): Regex {
        val g = glob.trim().replace('\\', '/').removePrefix("./")
        val sb = StringBuilder()
        var i = 0
        var braces = 0
        while (i < g.length) {
            val c = g[i]
            when {
                c == '*' && i + 1 < g.length && g[i + 1] == '*' -> {
                    i++
                    if (i + 1 < g.length && g[i + 1] == '/') { i++; sb.append("(?:.*/)?") } else sb.append(".*")
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '{' -> { braces++; sb.append("(?:") }
                c == '}' && braces > 0 -> { braces--; sb.append(")") }
                c == ',' && braces > 0 -> sb.append("|")
                c in "\\.[]()+^\$|" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        // نمط بلا / يطابق اسم الملف في أي مجلد (سلوك glob المعتاد في الأدوات).
        val body = if (!g.contains('/')) "(?:.*/)?$sb" else sb.toString()
        return Regex("^$body$")
    }

    private fun files(base: File): Sequence<File> =
        if (base.isFile) sequenceOf(base)
        else base.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS && !it.name.startsWith(".lion-") }
            .filter { it.isFile }

    private fun rel(root: File, f: File): String =
        runCatching { f.canonicalFile.relativeTo(root.canonicalFile).path.replace('\\', '/') }.getOrDefault(f.name)

    fun glob(root: File, base: File, pattern: String, max: Int): List<String> {
        val re = globToRegex(pattern)
        return files(base).map { rel(root, it) }.filter { re.matches(it) }.sorted().take(max).toList()
    }

    data class Hit(val path: String, val line: Int, val text: String, val isMatch: Boolean)

    fun grep(
        root: File, base: File, pattern: String, glob: String = "",
        context: Int = 0, max: Int = 50, ignoreCase: Boolean = true
    ): List<Hit> {
        val re = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        val globRe = glob.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val out = ArrayList<Hit>()
        var matches = 0
        for (file in files(base)) {
            if (matches >= max) break
            val path = rel(root, file)
            if (globRe != null && !globRe.matches(path)) continue
            if (file.length() > 512_000) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.take(2048).any { it.toInt() == 0 }) continue
            val lines = String(bytes, Charsets.UTF_8).lines()
            var lastShown = -1
            for ((idx, line) in lines.withIndex()) {
                if (matches >= max) break
                if (!re.containsMatchIn(line)) continue
                matches++
                val from = maxOf(0, idx - context, lastShown + 1)
                val to = minOf(lines.size - 1, idx + context)
                for (j in from..to) out.add(Hit(path, j + 1, lines[j].take(240), j == idx))
                lastShown = to
            }
        }
        return out
    }

    fun renderGrep(hits: List<Hit>): String {
        if (hits.isEmpty()) return "لا نتائج."
        val sb = StringBuilder()
        var prev: Pair<String, Int>? = null
        hits.forEach { h ->
            if (prev != null && (prev!!.first != h.path || h.line != prev!!.second + 1)) sb.append("--\n")
            sb.append(h.path).append(if (h.isMatch) ":" else "-").append(h.line).append(if (h.isMatch) ": " else "- ").append(h.text).append('\n')
            prev = h.path to h.line
        }
        return sb.toString().trimEnd()
    }
}

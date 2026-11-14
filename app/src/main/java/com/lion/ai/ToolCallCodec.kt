package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject

/** استدعاء أداة واحد بعد التحليل: الاسم المعياري + الوسائط + النص الخام. */
data class ToolCall(val name: String, val args: JSONObject, val raw: String = "")

/**
 * محلّل استدعاءات الأدوات الموحّد (صيغة نصية تعمل مع كل المزودات).
 *
 * يقبل الأسماء المتكافئة بلا رفض: `name` و`tool` و`tool_name` وأشباهها بأي حالة أحرف،
 * والوسائط تحت `args`/`arguments`/`parameters`/`input`، وكذلك شكل OpenAI
 * (`{"type":"function","function":{"name":…,"arguments":…}}`).
 * ويصلح الصيغ المعطوبة الشائعة: إغلاق بـ [[TOOL]] بدل [[/TOOL]]، أو كتلة بلا إغلاق،
 * أو JSON عارٍ بلا سياج.
 *
 * لا يعتمد على أندرويد ولا على حالة التطبيق، فهو قابل للاختبار على JVM مباشرة.
 */
object ToolCallCodec {
    private val MARKER = Regex("\\[\\[TOOL\\]\\](.*?)\\[\\[/TOOL\\]\\]", RegexOption.DOT_MATCHES_ALL)
    private val TOOL_TAG = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val FENCE = Regex("```(?:tool|json|ai-tool)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)

    // صيغة DSML (DeepSeek وأشباهه): <｜DSML｜invoke name="x"><｜DSML｜parameter name="args">{json}</｜DSML｜parameter>
    // الفواصل | أو ｜ أو │ وقد تتخللها مسافات أو سمات إضافية، لذلك نطابق بتسامح.
    private val DSML_INVOKE = Regex(
        "<[^<>a-zA-Z/]*DSML[^<>a-zA-Z]*invoke\\s+[^>]*?name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</[^<>a-zA-Z]*DSML[^<>a-zA-Z]*invoke\\s*>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val DSML_PARAM = Regex(
        "<[^<>a-zA-Z/]*DSML[^<>a-zA-Z]*parameter\\s+[^>]*?name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</[^<>a-zA-Z]*DSML[^<>a-zA-Z]*parameter\\s*>",
        RegexOption.DOT_MATCHES_ALL
    )
    /** مفاتيح نصية حرة يجب ألا تُحوَّل إلى JSONObject/JSONArray حتى لو احتوت JSON خاماً بداخلها. */
    private val RAW_TEXT_KEYS = setOf(
        "content", "text", "old", "new", "old_text", "new_text", "code", "patch",
        "body", "json", "sql", "message", "query", "pattern", "replacement", "find", "replace"
    )

    private fun parseDsml(text: String, known: Set<String>): List<ToolCall> {
        if (!text.contains("DSML")) return emptyList()
        val out = mutableListOf<ToolCall>()
        DSML_INVOKE.findAll(text).forEach { m ->
            val name = normalizeName(m.groupValues[1], known)
            if (name.isEmpty()) return@forEach
            val args = JSONObject()
            DSML_PARAM.findAll(m.groupValues[2]).forEach { pm ->
                val key = pm.groupValues[1].trim()
                val rawVal = pm.groupValues[2]
                val trimmed = rawVal.trim()
                val asObj = if (trimmed.startsWith("{")) runCatching { JSONObject(trimmed) }.getOrNull() else null
                val asArr = if (trimmed.startsWith("[")) runCatching { JSONArray(trimmed) }.getOrNull() else null
                when {
                    (key.equals("args", true) || key.equals("arguments", true) || key.equals("parameters", true)) && asObj != null ->
                        asObj.keys().forEach { k -> args.put(k, asObj.get(k)) }
                    key.lowercase() in RAW_TEXT_KEYS ->
                        args.put(key, rawVal.removePrefix("\n").removeSuffix("\n"))
                    asArr != null -> args.put(key, asArr)
                    asObj != null -> args.put(key, asObj)
                    trimmed.equals("true", true) -> args.put(key, true)
                    trimmed.equals("false", true) -> args.put(key, false)
                    else -> args.put(key, trimmed)
                }
            }
            if (out.none { it.name == name && it.args.toString() == args.toString() }) out.add(ToolCall(name, args, m.value))
        }
        return out
    }

    private val NAME_KEYS = arrayOf("tool", "name", "tool_name", "toolname", "action", "call")
    private val ARG_KEYS = arrayOf("args", "arguments", "parameters", "params", "input")

    /** يحوّل اسم الأداة الخام إلى اسم مسجّل معياري، أو "" إذا لم يُعرف. */
    internal fun normalizeName(raw: String, known: Set<String>): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val spaced = trimmed.replace(' ', '_').replace('-', '_')
        return when {
            known.contains(spaced) -> spaced
            known.contains(trimmed) -> trimmed
            else -> known.firstOrNull { it.equals(spaced, ignoreCase = true) } ?: ""
        }
    }

    private fun pick(json: JSONObject, aliases: Array<String>): Any? = json.keys().asSequence()
        .firstOrNull { key -> aliases.any { it.equals(key, ignoreCase = true) } }
        ?.let { key -> json.opt(key) }

    private fun asArgs(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    /**
     * يحلّل نص الرد إلى استدعاءات معروفة فقط، بلا تكرار (نفس الاسم ونفس الوسائط مرة واحدة).
     * [known] = أسماء الأدوات المسجلة.
     */
    fun parse(text: String, known: Set<String>): List<ToolCall> {
        if (text.isBlank() || known.isEmpty()) return emptyList()
        parseDsml(text, known).takeIf { it.isNotEmpty() }?.let { return it }
        val blocks = mutableListOf<String>()
        MARKER.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        TOOL_TAG.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        FENCE.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        // صيغ معطوبة: إغلاق بـ [[TOOL]] بدل [[/TOOL]]، أو بلا إغلاق.
        if (blocks.isEmpty()) {
            var from = text.indexOf("[[TOOL]]")
            while (from >= 0) {
                ToolMarkup.balancedJson(text, from + "[[TOOL]]".length)?.let { blocks.add(it) }
                from = text.indexOf("[[TOOL]]", from + 8)
            }
        }
        if (blocks.isEmpty()) {
            // بعض النماذج ترسل JSON عارياً بلا سياج (واحداً أو أكثر وسط النص).
            var cursor = text.indexOf('{')
            while (cursor >= 0 && cursor < text.length) {
                val balanced = ToolMarkup.balancedJson(text, cursor)
                if (balanced != null) {
                    blocks.add(balanced)
                    cursor = text.indexOf('{', cursor + balanced.length)
                } else {
                    cursor = text.indexOf('{', cursor + 1)
                }
            }
        }

        val found = mutableListOf<ToolCall>()
        blocks.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            val candidates = mutableListOf<JSONObject>()
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { candidates.add(it) }
            runCatching { JSONArray(trimmed) }.getOrNull()?.let { array ->
                for (index in 0 until array.length()) array.optJSONObject(index)?.let { candidates.add(it) }
            }
            candidates.forEach { json ->
                val function = pick(json, arrayOf("function")) as? JSONObject
                val functionName = function
                    ?.let { fn -> fn.keys().asSequence().firstOrNull { it.equals("name", true) || it.equals("tool", true) }?.let { fn.optString(it) } }
                    .orEmpty()
                val rawName = (pick(json, NAME_KEYS) as? String)?.takeIf { it.isNotBlank() } ?: functionName
                val rawArgs = pick(json, ARG_KEYS) ?: function
                    ?.let { fn -> fn.keys().asSequence().firstOrNull { it.equals("arguments", true) || it.equals("parameters", true) || it.equals("args", true) }?.let { fn.opt(it) } }
                val name = normalizeName(rawName, known)
                if (name.isEmpty()) return@forEach
                val args = asArgs(rawArgs)
                val duplicate = found.any { it.name == name && it.args.toString() == args.toString() }
                if (!duplicate) found.add(ToolCall(name, args, trimmed))
            }
        }
        return found
    }
}

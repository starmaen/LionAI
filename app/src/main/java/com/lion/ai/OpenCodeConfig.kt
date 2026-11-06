package com.lion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * إعداد OpenCode العام (opencode.json) داخل بيئة Alpine: خوادم MCP والوكلاء المخصصون.
 * الدوال النقية (بلا ملفات) قابلة للاختبار؛ التطبيق على القرص يمر دائماً عبر [applyAndVerify]
 * الذي يعيد تشغيل الخادم ويستعيد النسخة السابقة تلقائياً إن رفض OpenCode الإعداد الجديد.
 */
object OpenCodeConfig {
    private const val SCHEMA = "https://opencode.ai/config.json"
    private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")

    data class McpEntry(val name: String, val type: String, val target: String, val enabled: Boolean)
    data class AgentEntry(val name: String, val description: String)

    fun validName(name: String): Boolean = NAME.matches(name.trim().lowercase())

    // ───────────── دوال نقية ─────────────

    /** رابط http(s) = خادم بعيد، وإلا أمر محلي يُقسَّم على المسافات. */
    fun addMcp(config: JSONObject, name: String, target: String): JSONObject {
        val id = name.trim().lowercase()
        require(validName(id)) { "اسم غير صالح: أحرف إنجليزية صغيرة وأرقام و - _ فقط" }
        val clean = target.trim()
        require(clean.isNotBlank()) { "أدخل الأمر أو الرابط" }
        val entry = JSONObject().put("enabled", true)
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            entry.put("type", "remote").put("url", clean)
        } else {
            val parts = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            entry.put("type", "local").put("command", JSONArray(parts))
        }
        return withSection(config, "mcp") { it.put(id, entry) }
    }

    fun removeMcp(config: JSONObject, name: String): JSONObject = withoutKey(config, "mcp", name.trim().lowercase())

    fun mcpList(config: JSONObject): List<McpEntry> {
        val mcp = config.optJSONObject("mcp") ?: return emptyList()
        return mcp.keys().asSequence().sorted().map { key ->
            val e = mcp.optJSONObject(key) ?: JSONObject()
            val type = e.optString("type", "local")
            val target = if (type == "remote") e.optString("url") else {
                val cmd = e.optJSONArray("command")
                if (cmd == null) "" else (0 until cmd.length()).joinToString(" ") { cmd.optString(it) }
            }
            McpEntry(key, type, target, e.optBoolean("enabled", true))
        }.toList()
    }

    fun addAgent(config: JSONObject, name: String, description: String, prompt: String): JSONObject {
        val id = name.trim().lowercase()
        require(validName(id)) { "اسم غير صالح: أحرف إنجليزية صغيرة وأرقام و - _ فقط" }
        require(description.isNotBlank()) { "اكتب وصفاً قصيراً للوكيل" }
        require(prompt.isNotBlank()) { "اكتب تعليمات الوكيل" }
        val entry = JSONObject()
            .put("description", description.trim())
            .put("mode", "subagent")
            .put("prompt", prompt.trim())
        return withSection(config, "agent") { it.put(id, entry) }
    }

    fun removeAgent(config: JSONObject, name: String): JSONObject = withoutKey(config, "agent", name.trim().lowercase())

    fun agentList(config: JSONObject): List<AgentEntry> {
        val agents = config.optJSONObject("agent") ?: return emptyList()
        return agents.keys().asSequence().sorted().map {
            AgentEntry(it, agents.optJSONObject(it)?.optString("description").orEmpty())
        }.toList()
    }

    private fun copyOf(config: JSONObject): JSONObject = JSONObject(config.toString())

    private fun withSection(config: JSONObject, section: String, edit: (JSONObject) -> Unit): JSONObject {
        val out = copyOf(config)
        if (!out.has("\$schema")) out.put("\$schema", SCHEMA)
        val sec = out.optJSONObject(section) ?: JSONObject()
        edit(sec)
        out.put(section, sec)
        return out
    }

    private fun withoutKey(config: JSONObject, section: String, key: String): JSONObject {
        val out = copyOf(config)
        out.optJSONObject(section)?.let { sec ->
            sec.remove(key)
            if (sec.length() == 0) out.remove(section) else out.put(section, sec)
        }
        return out
    }

    // ───────────── القرص والتحقق ─────────────

    fun file(context: Context): File =
        File(LinuxEnv.rootfs(context), "root/.config/opencode/opencode.json")

    fun load(context: Context): JSONObject {
        val f = file(context)
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    /**
     * يكتب الإعداد، يعيد تشغيل الخادم، ويتحقق أنه يعمل. إن فشل يستعيد الإعداد السابق ويعيد التشغيل.
     * يُستدعى من خيط خلفي (قد يستغرق حتى دقيقتين تحت proot).
     */
    fun applyAndVerify(context: Context, mutate: (JSONObject) -> JSONObject): String {
        val f = file(context)
        val previous = if (f.exists()) f.readText() else null
        val next = mutate(load(context))
        f.parentFile?.mkdirs()
        f.writeText(next.toString(2))
        OpenCodeLocal.stop()
        if (OpenCodeLocal.isRunning()) {
            return "✅ حُفظ الإعداد، لكن خادم OpenCode يعمل خارج التطبيق (الطرفية): أعد تشغيله ليقرأ التغيير."
        }
        val failure = runCatching { OpenCodeLocal.start() }.exceptionOrNull()
        if (failure == null) {
            runCatching { OpenCodeLocal.syncModels() }
            return "✅ حُفظ الإعداد وأعيد تشغيل الخادم بنجاح."
        }
        if (previous == null) f.delete() else f.writeText(previous)
        runCatching { OpenCodeLocal.stop(); OpenCodeLocal.start() }
        return "❌ رفض OpenCode الإعداد الجديد فاستُعيد السابق:\n" + (failure.message ?: failure.javaClass.simpleName).take(600)
    }

    // ───────────── قواعد المشروع AGENTS.md ─────────────

    private fun rulesFile(context: Context) = File(FileTools.root(context), "AGENTS.md")

    fun loadRules(context: Context): String = rulesFile(context).takeIf { it.exists() }?.readText().orEmpty()

    fun saveRules(context: Context, text: String) {
        val f = rulesFile(context)
        if (text.isBlank()) f.delete() else f.writeText(text)
    }
}

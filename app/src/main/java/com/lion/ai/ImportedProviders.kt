package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * مزودو الخادم المحلي المستوردون من الإنترنت.
 *
 * يُخزَّنون كـ JSON في prefs تحت [KEY] ويظهرون كأزرار اختيار داخل بطاقة
 * الخادم المحلي، فيختار المستخدم المزود بضغطة بدل كتابة العنوان يدوياً.
 */
object ImportedProviders {

    const val KEY = "imported_backends_json"
    private const val ID_PREFIX = "imp_"

    data class Entry(
        val id: String,
        val label: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String
    )

    fun list(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val baseUrl = obj.optString("baseUrl", "").trim()
                    if (id.isBlank() || baseUrl.isBlank()) continue
                    add(
                        Entry(
                            id = id,
                            label = obj.optString("label", id).ifBlank { id },
                            baseUrl = baseUrl,
                            model = obj.optString("model", ""),
                            apiKey = obj.optString("apiKey", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** خريطة id → baseUrl لدمجها في [LlmClient.reloadOverrides]. */
    fun endpoints(prefs: SharedPreferences): Map<String, String> =
        list(prefs).associate { it.id to it.baseUrl }

    /**
     * يدمج قائمة مزودين بصيغة JSON:
     * مصفوفة عناصر، أو كائن فيه مفتاح providers/backends/data.
     * الحقول المقبولة لكل عنصر: id, label/name, baseUrl/endpoint/url, model, apiKey/key.
     *
     * @return عدد المزودين الذين أُضيفوا فعلياً (دون المكرر).
     */
    fun importFrom(prefs: SharedPreferences, rawJson: String): Int {
        val text = rawJson.trim()
        if (text.isBlank()) return 0
        val items = parseItems(text)
        if (items.isEmpty()) return 0

        val existing = list(prefs).associateBy { it.id }.toMutableMap()
        var added = 0
        val editor = prefs.edit()

        items.forEach { obj ->
            val label = firstNonBlank(
                obj.optString("label", ""),
                obj.optString("name", ""),
                obj.optString("title", "")
            )
            val baseUrl = firstNonBlank(
                obj.optString("baseUrl", ""),
                obj.optString("endpoint", ""),
                obj.optString("url", ""),
                obj.optString("base_url", "")
            )
            if (baseUrl.isBlank()) return@forEach

            val rawId = obj.optString("id", "").trim()
            val id = normalizeId(rawId.ifBlank { label.ifBlank { baseUrl } })
            val model = firstNonBlank(
                obj.optString("model", ""),
                obj.optString("defaultModel", ""),
                obj.optString("default_model", "")
            )
            val apiKey = firstNonBlank(
                obj.optString("apiKey", ""),
                obj.optString("key", ""),
                obj.optString("api_key", "")
            )
            val entry = Entry(
                id = id,
                label = label.ifBlank { id.removePrefix(ID_PREFIX) },
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey
            )
            if (existing.containsKey(id)) return@forEach
            existing[id] = entry
            added++
            editor.putString("endpoint_$id", baseUrl)
            editor.putString("model_$id", model)
            if (apiKey.isNotBlank()) editor.putString("api_key_$id", apiKey)
        }

        if (added == 0) return 0
        editor.putString(KEY, toJson(existing.values.toList()))
        editor.apply()
        LlmClient.reloadOverrides(prefs)
        return added
    }

    fun remove(prefs: SharedPreferences, id: String) {
        val remaining = list(prefs).filterNot { it.id == id }
        prefs.edit()
            .putString(KEY, toJson(remaining))
            .remove("endpoint_$id")
            .remove("model_$id")
            .remove("api_key_$id")
            .apply()
        LlmClient.reloadOverrides(prefs)
    }

    private fun parseItems(text: String): List<JSONObject> = runCatching {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            val candidate = root.optJSONArray("providers")
                ?: root.optJSONArray("backends")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("servers")
            candidate ?: return@runCatching emptyList()
        }
        buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())

    private fun normalizeId(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_')
        val safe = cleaned.ifBlank { "provider" + System.currentTimeMillis() }
        return if (safe.startsWith(ID_PREFIX)) safe else ID_PREFIX + safe
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun toJson(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("label", entry.label)
                    put("baseUrl", entry.baseUrl)
                    put("model", entry.model)
                    put("apiKey", entry.apiKey)
                }
            )
        }
        return array.toString()
    }
}

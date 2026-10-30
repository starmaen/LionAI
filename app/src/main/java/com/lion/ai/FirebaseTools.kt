package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * أدوات Firebase عبر REST فقط (بلا SDK): Firestore + Storage.
 * تعتمد على إعدادات محلية: firebase_project / firebase_api_key / firebase_id_token / firebase_bucket.
 * الأمان الحقيقي في قواعد Firestore/Storage، لا في المفتاح العام.
 */
object FirebaseTools {
    private const val FIRESTORE = "https://firestore.googleapis.com/v1/projects"
    private const val STORAGE = "https://storage.googleapis.com/storage/v1/b"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun project(prefs: SharedPreferences, explicit: String = ""): String =
        explicit.trim().ifBlank { (prefs.getString("firebase_project", "") ?: "").trim() }

    fun apiKey(prefs: SharedPreferences): String =
        (prefs.getString("firebase_api_key", "") ?: "").trim()

    fun idToken(prefs: SharedPreferences, explicit: String = ""): String =
        explicit.trim().ifBlank { (prefs.getString("firebase_id_token", "") ?: "").trim() }

    fun bucket(prefs: SharedPreferences, explicit: String = ""): String =
        explicit.trim().ifBlank { (prefs.getString("firebase_bucket", "") ?: "").trim() }

    private fun requireProject(prefs: SharedPreferences, explicit: String): String {
        val value = project(prefs, explicit)
        if (value.isBlank()) {
            throw IllegalStateException("أضف Firebase Project ID من «الإعدادات ← أدوات الذكاء والتطوير» أو مرّر project.")
        }
        return value
    }

    private fun documents(prefs: SharedPreferences, explicitProject: String): String =
        "$FIRESTORE/${requireProject(prefs, explicitProject)}/databases/(default)/documents"

    private fun headers(token: String): Map<String, String> =
        if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    private fun docUrl(prefs: SharedPreferences, explicitProject: String, path: String): String {
        val clean = path.trim().trimStart('/')
        if (clean.isBlank()) throw IllegalArgumentException("مرّر مسار المستند، مثل users/abc")
        var url = documents(prefs, explicitProject) + "/" + clean
        val key = apiKey(prefs)
        if (key.isNotBlank()) url += "?key=" + enc(key)
        return url
    }

    fun status(prefs: SharedPreferences): String = buildString {
        append("Firebase Project: ").append(project(prefs).ifBlank { "غير مضبوط ❌" }).append('\n')
        append("Web API Key: ").append(if (apiKey(prefs).isBlank()) "غير مضبوط" else "مضبوط ✅").append('\n')
        append("ID Token: ").append(if (idToken(prefs).isBlank()) "غير مضبوط (قواعد عامة فقط)" else "مضبوط ✅").append('\n')
        append("Storage Bucket: ").append(bucket(prefs).ifBlank { "غير مضبوط (مطلوب لـ storage_list)" })
    }

    fun get(prefs: SharedPreferences, projectArg: String, path: String, tokenArg: String): String {
        val raw = WebTools.httpRequest(
            docUrl(prefs, projectArg, path),
            headers = headers(idToken(prefs, tokenArg))
        )
        val json = JSONObject(raw)
        val fields = json.optJSONObject("fields")
        val out = JSONObject()
        out.put("id", json.optString("name").substringAfterLast('/'))
        out.put("data", decodeFields(fields))
        return out.toString(2).take(12_000)
    }

    fun query(
        prefs: SharedPreferences,
        projectArg: String,
        collection: String,
        limit: Int,
        pageToken: String,
        tokenArg: String
    ): String {
        val clean = collection.trim().trimStart('/')
        if (clean.isBlank()) throw IllegalArgumentException("مرّر اسم المجموعة، مثل users")
        var url = documents(prefs, projectArg) + "/" + clean + "?pageSize=" + limit.coerceIn(1, 100)
        if (pageToken.isNotBlank()) url += "&pageToken=" + enc(pageToken)
        val key = apiKey(prefs)
        if (key.isNotBlank()) url += "&key=" + enc(key)
        val json = JSONObject(WebTools.httpRequest(url, headers = headers(idToken(prefs, tokenArg))))
        val documents = json.optJSONArray("documents")
        val out = JSONArray()
        if (documents != null) {
            for (index in 0 until documents.length()) {
                val doc = documents.optJSONObject(index) ?: continue
                val item = JSONObject()
                item.put("id", doc.optString("name").substringAfterLast('/'))
                item.put("data", decodeFields(doc.optJSONObject("fields")))
                out.put(item)
            }
        }
        return if (out.length() == 0) "لا مستندات في $clean" else out.toString(2).take(14_000)
    }

    fun set(
        prefs: SharedPreferences,
        projectArg: String,
        path: String,
        data: JSONObject,
        merge: Boolean,
        tokenArg: String
    ): String {
        if (data.length() == 0) throw IllegalArgumentException("مرّر data ككائن JSON غير فارغ")
        val token = idToken(prefs, tokenArg)
        if (token.isBlank() && apiKey(prefs).isBlank()) {
            throw IllegalStateException("الكتابة تحتاج قواعد تسمح بالوصول العام، أو أضف firebase_id_token من مستخدم Auth.")
        }
        var url = docUrl(prefs, projectArg, path)
        if (merge) {
            val mask = data.keys().asSequence().joinToString("&") { "updateMask.fieldPaths=" + enc(it) }
            url += (if (url.contains('?')) "&" else "?") + mask
        }
        val body = JSONObject().put("fields", encodeObject(data)).toString()
        val raw = WebTools.httpRequest(url, "PATCH", body, headers = headers(token))
        val updated = runCatching { JSONObject(raw) }.getOrNull()
        return buildString {
            append("✅ حُدّث ").append(path.trim()).append(if (merge) " (دمج)" else " (استبدال)")
            if (updated != null) append("\n").append(decodeFields(updated.optJSONObject("fields")).toString(2).take(2000))
        }
    }

    fun storageList(
        prefs: SharedPreferences,
        bucketArg: String,
        prefix: String,
        limit: Int,
        tokenArg: String
    ): String {
        val name = bucket(prefs, bucketArg)
        if (name.isBlank()) throw IllegalStateException("أضف Storage Bucket (مثل my-app.appspot.com) في الإعدادات.")
        val token = idToken(prefs, tokenArg)
        if (token.isBlank()) throw IllegalStateException("Storage REST يحتاج firebase_id_token من مستخدم Auth.")
        var url = "$STORAGE/" + enc(name) + "/o?maxResults=" + limit.coerceIn(1, 100)
        if (prefix.isNotBlank()) url += "&prefix=" + enc(prefix)
        val json = JSONObject(WebTools.httpRequest(url, headers = headers(token)))
        val items = json.optJSONArray("items")
        if (items == null || items.length() == 0) return "لا ملفات في $name" + if (prefix.isBlank()) "" else " ضمن $prefix"
        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            lines.add("• " + item.optString("name") + " (" + item.optString("size", "?") + " بايت)")
        }
        return lines.joinToString("\n")
    }

    fun decodeFields(fields: JSONObject?): JSONObject {
        val out = JSONObject()
        if (fields == null) return out
        fields.keys().forEach { name ->
            fields.optJSONObject(name)?.let { out.put(name, decodeValue(it)) }
        }
        return out
    }

    private fun decodeValue(value: JSONObject): Any = when {
        value.has("stringValue") -> value.optString("stringValue")
        value.has("integerValue") -> value.optString("integerValue").toLongOrNull() ?: 0L
        value.has("doubleValue") -> value.optDouble("doubleValue")
        value.has("booleanValue") -> value.optBoolean("booleanValue")
        value.has("timestampValue") -> value.optString("timestampValue")
        value.has("nullValue") -> JSONObject.NULL
        value.has("arrayValue") -> {
            val array = JSONArray()
            val values = value.optJSONObject("arrayValue")?.optJSONArray("values")
            if (values != null) {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let { array.put(decodeValue(it)) }
                }
            }
            array
        }
        value.has("mapValue") -> decodeFields(value.optJSONObject("mapValue")?.optJSONObject("fields"))
        else -> value.toString()
    }

    fun encodeObject(data: JSONObject): JSONObject {
        val fields = JSONObject()
        data.keys().forEach { key -> fields.put(key, encodeValue(data.opt(key))) }
        return fields
    }

    private fun encodeValue(value: Any?): JSONObject = when (value) {
        null, JSONObject.NULL -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", value)
        is Int -> JSONObject().put("integerValue", value.toString())
        is Long -> JSONObject().put("integerValue", value.toString())
        is Number -> JSONObject().put("doubleValue", value.toDouble())
        is JSONObject -> JSONObject().put("mapValue", JSONObject().put("fields", encodeObject(value)))
        is JSONArray -> {
            val values = JSONArray()
            for (index in 0 until value.length()) values.put(encodeValue(value.opt(index)))
            JSONObject().put("arrayValue", JSONObject().put("values", values))
        }
        else -> JSONObject().put("stringValue", value.toString())
    }
}

package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONArray

object ModelCatalog {
    fun cached(prefs: SharedPreferences, providerId: String): List<String> {
        val raw = prefs.getString("models_cache_$providerId", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCache(prefs: SharedPreferences, providerId: String, models: List<String>) {
        val array = JSONArray()
        models.forEach { array.put(it) }
        prefs.edit().putString("models_cache_$providerId", array.toString()).apply()
    }

    /** القائمة الجاهزة + ما جُلب من الإنترنت + الافتراضي، بدون تكرار. */
    fun options(info: ProviderInfo, cached: List<String>): List<String> =
        (info.models + cached + info.defaultModel).distinct()
}

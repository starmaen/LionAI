package com.lion.ai.core

import android.content.Context
import android.content.SharedPreferences

class WebProvidersPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("web_providers_prefs", Context.MODE_PRIVATE)

    fun isWebEnabled(): Boolean = prefs.getBoolean("web_enabled", true)
    fun setWebEnabled(enabled: Boolean) = prefs.edit().putBoolean("web_enabled", enabled).apply()

    fun getMode(): String = prefs.getString("web_mode", "balanced") ?: "balanced"
    fun setMode(mode: String) = prefs.edit().putString("web_mode", mode).apply()

    fun isProviderEnabled(id: String): Boolean = prefs.getBoolean("provider_$id", true)
    fun setProviderEnabled(id: String, enabled: Boolean) = prefs.edit().putBoolean("provider_$id", enabled).apply()

    // هذا اللي كان يسبب Unresolved reference 'getEnabled'
    fun getEnabled(all: List<WebProvider>): List<WebProvider> {
        return all.filter { isProviderEnabled(it.id) }
    }

    fun getStatus(all: List<WebProvider>): String {
        val enabled = getEnabled(all).size
        return "$enabled/${all.size} مفعل"
    }
}

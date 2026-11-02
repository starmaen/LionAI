package com.lion.ai.core
import android.content.SharedPreferences
import com.lion.ai.PROVIDERS
import com.lion.ai.ProviderGroup
import com.lion.ai.Route
data class WebProvider(val id: String, val displayName: String, val url: String, val icon: String = "🌐")
object WebProviderAdapter {
    fun allWebProviders(): List<WebProvider> {
        return PROVIDERS.filter { it.group == ProviderGroup.WEB }.map {
            WebProvider(it.id, it.label, it.webviewUrl ?: "", "🌐")
        }
    }
    fun toLionRoutes(prefs: SharedPreferences): List<Route> {
        if (!WebProvidersPrefs.isLionEnabled(prefs)) return emptyList()
        val tabs = WebProvidersPrefs.getEnabledTabs(prefs, allWebProviders().map { it.id })
        if (tabs.isEmpty()) return emptyList()
        return listOf(Route("lion_chat", "lion-brain-v1"))
    }
}

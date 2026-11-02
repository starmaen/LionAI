package com.lion.ai.core
import android.content.Context
class WebProvidersPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("web_providers", Context.MODE_PRIVATE)
    fun isWebEnabled(): Boolean = prefs.getBoolean("web_enabled", true)
    fun setWebEnabled(enabled: Boolean) = prefs.edit().putBoolean("web_enabled", enabled).apply()
    fun isProviderEnabled(id: String): Boolean = prefs.getBoolean("provider_$id", true)
    fun setProviderEnabled(id: String, enabled: Boolean) = prefs.edit().putBoolean("provider_$id", enabled).apply()
    fun getEnabledProvidersCount(): Int {
        var count = 0
        listOf("arena","claude-web","deepseek-chat","chatgpt-web","gemini-web","qwen-web","kimi-web","perplexity-web","poe-web","huggingface-web").forEach {
            if(isProviderEnabled(it)) count++
        }
        return count
    }
    fun getStatus(): String = if(isWebEnabled()) "مفعل - ${getEnabledProvidersCount()} صفحات" else "معطل"
}

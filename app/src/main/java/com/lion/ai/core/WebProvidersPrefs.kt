package com.lion.ai.core
import android.content.Context
import android.content.SharedPreferences
object WebProvidersPrefs {
    private const val PREF_NAME = "web_providers_prefs"
    private const val KEY_PROVIDER_PREFIX = "provider_"
    private const val KEY_LION_ENABLED = "lion_chat_enabled"
    private const val KEY_LION_CENTRAL = "lion_central_enabled"
    private const val KEY_LION_FILES = "lion_files"
    private const val KEY_LION_GITHUB = "lion_github"
    private const val KEY_LION_EMAIL = "lion_email"
    private const val KEY_LION_TOOLS = "lion_tools"
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    fun isWebEnabled(context: Context): Boolean = prefs(context).getBoolean("web_enabled", true)
    fun getMode(context: Context): String = prefs(context).getString("web_mode", "auto") ?: "auto"
    fun isProviderEnabled(context: Context, id: String): Boolean = prefs(context).getBoolean(KEY_PROVIDER_PREFIX + id, true)
    fun setProviderEnabled(context: Context, id: String, v: Boolean) { prefs(context).edit().putBoolean(KEY_PROVIDER_PREFIX + id, v).apply() }
    fun isLionEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_ENABLED, true)
    fun setLionEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_ENABLED, v).apply() }
    fun isCentralEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_CENTRAL, true)
    fun setCentralEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_CENTRAL, v).apply() }
    fun isFilesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_FILES, true)
    fun setFilesEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_FILES, v).apply() }
    fun isGitHubEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_GITHUB, true)
    fun setGitHubEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_GITHUB, v).apply() }
    fun isEmailEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_EMAIL, true)
    fun setEmailEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_EMAIL, v).apply() }
    fun isToolsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_TOOLS, true)
    fun setToolsEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LION_TOOLS, v).apply() }
}

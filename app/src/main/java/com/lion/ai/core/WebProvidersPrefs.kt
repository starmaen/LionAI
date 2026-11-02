package com.lion.ai.core
import android.content.SharedPreferences
object WebProvidersPrefs {
    private const val PREFIX = "web_tab_"
    private const val KEY_MASTER = "lion_chat_enabled"
    private const val KEY_CENTRAL = "lion_chat_central"
    private const val KEY_TOOLS = "lion_chat_tools"
    private const val KEY_GITHUB = "lion_chat_github"
    private const val KEY_EMAIL = "lion_chat_email"
    private const val KEY_FILES = "lion_chat_files"
    fun isLionEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_MASTER, true)
    fun setLionEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_MASTER, v).apply()
    fun isCentralEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_CENTRAL, true)
    fun setCentralEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_CENTRAL, v).apply()
    fun isToolsEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_TOOLS, true)
    fun setToolsEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_TOOLS, v).apply()
    fun isGitHubEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_GITHUB, true)
    fun setGitHubEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_GITHUB, v).apply()
    fun isEmailEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_EMAIL, true)
    fun setEmailEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_EMAIL, v).apply()
    fun isFilesEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_FILES, true)
    fun setFilesEnabled(p: SharedPreferences, v: Boolean) = p.edit().putBoolean(KEY_FILES, v).apply()
    fun isTabEnabled(p: SharedPreferences, id: String): Boolean = p.getBoolean(PREFIX + id, true)
    fun setTabEnabled(p: SharedPreferences, id: String, v: Boolean) = p.edit().putBoolean(PREFIX + id, v).apply()
    fun getEnabledTabs(p: SharedPreferences, allIds: List<String>): List<String> = allIds.filter { isTabEnabled(p, it) }
    fun setAllTabs(p: SharedPreferences, allIds: List<String>, enabled: Boolean) {
        val e = p.edit()
        allIds.forEach { e.putBoolean(PREFIX + it, enabled) }
        e.apply()
    }
}

package com.lion.ai.core

import android.content.Context
import android.content.SharedPreferences

object WebProvidersPrefs {
    private const val PREF_NAME = "web_providers_prefs"
    private const val KEY_WEB_ENABLED = "web_enabled"
    private const val KEY_MODE = "web_mode"
    private const val KEY_PROVIDER_PREFIX = "provider_"
    
    // Lion Chat master
    private const val KEY_LION_ENABLED = "lion_chat_enabled"
    private const val KEY_LION_CENTRAL = "lion_central_enabled"
    private const val KEY_LION_FILES = "lion_files"
    private const val KEY_LION_GITHUB = "lion_github"
    private const val KEY_LION_EMAIL = "lion_email"
    private const val KEY_LION_TOOLS = "lion_tools"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // Called from SettingsScreen as WebProvidersPrefs.isWebEnabled(...)
    fun isWebEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_ENABLED, true)

    fun setWebEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_WEB_ENABLED, enabled).apply()

    // Called as getMode
    fun getMode(context: Context): String =
        prefs(context).getString(KEY_MODE, "auto") ?: "auto"

    fun setMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_MODE, mode).apply()

    fun isProviderEnabled(context: Context, providerId: String): Boolean =
        prefs(context).getBoolean(KEY_PROVIDER_PREFIX + providerId, true)

    fun setProviderEnabled(context: Context, providerId: String, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_PROVIDER_PREFIX + providerId, enabled).apply()

    // Aliases used by adapter
    fun getEnabled(context: Context, providerId: String): Boolean = isProviderEnabled(context, providerId)
    fun setEnabled(context: Context, providerId: String, enabled: Boolean) = setProviderEnabled(context, providerId, enabled)

    fun getStatus(context: Context, providerId: String): Boolean = isProviderEnabled(context, providerId)

    fun getAllEnabled(context: Context): Map<String, Boolean> {
        val all = prefs(context).all
        return all.filterKeys { it.startsWith(KEY_PROVIDER_PREFIX) }
            .mapKeys { it.key.removePrefix(KEY_PROVIDER_PREFIX) }
            .mapValues { it.value as? Boolean ?: true }
    }

    // Lion Chat methods
    fun isLionEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_ENABLED, true)
    fun setLionEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_ENABLED, v).apply()
    fun isCentralEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_CENTRAL, true)
    fun setCentralEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_CENTRAL, v).apply()
    fun isFilesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_FILES, true)
    fun setFilesEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_FILES, v).apply()
    fun isGitHubEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_GITHUB, true)
    fun setGitHubEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_GITHUB, v).apply()
    fun isEmailEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_EMAIL, true)
    fun setEmailEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_EMAIL, v).apply()
    fun isToolsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LION_TOOLS, true)
    fun setToolsEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_LION_TOOLS, v).apply()
}

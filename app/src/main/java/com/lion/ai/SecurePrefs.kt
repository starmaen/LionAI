package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

object SecurePrefs {
    private const val SECURE_FILE = "lion_secure"
    private const val LEGACY_FILE = "lion_prefs"
    private const val MIGRATED = "secure_migrated_v1"
    private const val OPEN_ATTEMPTS = 3
    private const val RETRY_COOLDOWN_MS = 30_000L

    @Volatile
    var usingPlaintextFallback: Boolean = false
        private set

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var lastFailureAt: Long = 0L

    private val lock = Any()

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val app = context.applicationContext ?: context
            val now = System.currentTimeMillis()
            val coolingDown = lastFailureAt != 0L && now - lastFailureAt < RETRY_COOLDOWN_MS
            if (!coolingDown) {
                val secure = openSecure(app)
                if (secure != null) {
                    usingPlaintextFallback = false
                    lastFailureAt = 0L
                    runCatching { mergeLegacy(app, secure) }
                    cached = secure
                    return secure
                }
                lastFailureAt = System.currentTimeMillis()
            }
            usingPlaintextFallback = true
            return app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun openSecure(context: Context): SharedPreferences? {
        for (attempt in 0 until OPEN_ATTEMPTS) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    SECURE_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                if (attempt < OPEN_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(150L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun mergeLegacy(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val items = legacy.all
        if (items.isEmpty()) return

        val editor = secure.edit()
        items.forEach { (key, value) ->
            if (key == MIGRATED) return@forEach
            when (value) {
                is String -> {
                    val existing = runCatching { secure.getString(key, null) }.getOrNull()
                    editor.putString(
                        key,
                        if (existing.isNullOrEmpty()) value else mergeJsonLists(existing, value)
                    )
                }
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.putBoolean(MIGRATED, true)
        if (editor.commit()) {
            legacy.edit().clear().commit()
        }
    }

    private fun mergeJsonLists(existing: String, incoming: String): String {
        val oldList = runCatching { JSONArray(existing) }.getOrNull() ?: return incoming
        val newList = runCatching { JSONArray(incoming) }.getOrNull() ?: return incoming
        val seen = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until newList.length()) {
            val obj = newList.optJSONObject(i) ?: return incoming
            val id = obj.optString("id", "")
            if (id.isBlank()) return incoming
            seen.add(id)
            out.put(obj)
        }
        for (i in 0 until oldList.length()) {
            val obj = oldList.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank() || id in seen) continue
            out.put(obj)
        }
        return out.toString()
    }
}

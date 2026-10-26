package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val SECURE_FILE = "lion_secure"
    private const val LEGACY_FILE = "lion_prefs"
    private const val MIGRATED = "secure_migrated_v1"

    /** يصبح true إذا تعذّر إنشاء التخزين المشفّر واضطررنا للتخزين العادي. */
    @Volatile
    var usingPlaintextFallback: Boolean = false
        private set

    fun get(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrate(context, secure)
            secure
        } catch (e: Exception) {
            usingPlaintextFallback = true
            context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun migrate(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty() || secure.getBoolean(MIGRATED, false)) return

        val editor = secure.edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val stringSet = value as Set<String>
                    editor.putStringSet(key, stringSet)
                }
            }
        }
        editor.putBoolean(MIGRATED, true).apply()
        legacy.edit().clear().apply()
    }
}

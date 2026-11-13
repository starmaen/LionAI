package com.lion.ai

import android.content.SharedPreferences

/** سجل صحة المزودات: يُحفظ في prefs فيبقى بعد إغلاق التطبيق، ويتحدّث من الإجابات الفعلية. */
object ProviderHealth {
    data class Entry(val ok: Boolean, val atMs: Long, val note: String)

    private fun key(id: String) = "health_$id"

    fun record(prefs: SharedPreferences, id: String, ok: Boolean, note: String = "") {
        if (id.isBlank()) return
        val clean = note.replace('\n', ' ').replace('|', '/').take(160)
        prefs.edit()
            .putString(key(id), (if (ok) "1" else "0") + "|" + System.currentTimeMillis() + "|" + clean)
            .apply()
    }

    /** للاستدعاء من طبقة الشبكة بلا prefs في اليد. آمن في اختبارات JVM (بلا سياق لا يفعل شيئاً). */
    fun recordAuto(id: String, ok: Boolean, note: String = "") {
        runCatching {
            val ctx = ContextHolder.peek() ?: return
            record(SecurePrefs.get(ctx), id, ok, note)
        }
    }

    fun get(prefs: SharedPreferences, id: String): Entry? {
        val raw = runCatching { prefs.getString(key(id), null) }.getOrNull() ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size < 2) return null
        val at = parts[1].toLongOrNull() ?: return null
        return Entry(parts[0] == "1", at, parts.getOrElse(2) { "" })
    }

    fun ageLabel(atMs: Long): String {
        val s = (System.currentTimeMillis() - atMs).coerceAtLeast(0L) / 1000
        return when {
            s < 60 -> "الآن"
            s < 3600 -> "منذ ${s / 60} د"
            s < 86400 -> "منذ ${s / 3600} س"
            else -> "منذ ${s / 86400} يوم"
        }
    }
}

object ProviderSelection {
    /** يثبّت المزود/الوضع الحاليين في علامة التبويب النشطة، فلا يعيدهما openChat() إلى قيمة قديمة بعد إعادة التشغيل. */
    fun syncActiveTab(prefs: SharedPreferences) {
        val tabs = TabsStore.load(prefs)
        if (tabs.isEmpty()) return
        val active = TabsStore.loadActive(prefs)
        val provider = (prefs.getString("provider", "gemini") ?: "gemini").trim().ifBlank { "gemini" }
        val auto = prefs.getBoolean("auto_route", true)
        TabsStore.save(
            prefs,
            tabs.map { if (it.id == active) it.copy(provider = provider, autoRoute = auto) else it }
        )
    }
}

package com.lion.ai

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * حذف أي مزود/منصة/صفحة ويب/وسائط دون أي أثر جانبي، فيبقى التطبيق مرناً ونظيفاً.
 *
 * - المنصات التي أضافها المستخدم (usr_*) والمزودون المستوردون (imp_*): حذف فعلي كامل (بياناتها ومفاتيحها).
 * - المدمجون في التطبيق: لا يمكن حذف كودهم وقت التشغيل، فيُسجَّلون كـ«محذوفين» ويختفون كلياً من كل مكان
 *   ([PROVIDERS] نفسها تستبعدهم): الإعدادات، المنتقي، الراوتر التلقائي، الإجماع، الوكيل ومحوّلات الويب.
 *   تبقى مفاتيحهم محفوظة ليمكن استعادتهم بضغطة واحدة دون إعادة إدخال شيء.
 * - أي مكان يحمل معرّف مزود محذوف (المحدد حالياً، تبويب قديم) يُحوَّل تلقائياً إلى بديل صالح عبر [resolve].
 */
object ProviderRemoval {
    const val KEY = "removed_providers"

    /** حالة Compose: قراءتها داخل PROVIDERS تجعل كل واجهة تعرض المزودين تتحدث فور الحذف/الاستعادة. */
    private val versionState = mutableIntStateOf(0)
    private fun touch() { versionState.intValue = versionState.intValue + 1 }

    @Volatile private var cacheRaw: String? = null
    @Volatile private var cache: Set<String> = emptySet()

    /** تُقرأ ديناميكياً من PROVIDERS؛ آمنة في اختبارات JVM (تعيد مجموعة فارغة بلا سياق). */
    fun removedIds(): Set<String> {
        versionState.intValue // اشتراك Compose
        val prefs = runCatching { ContextHolder.peek()?.let { SecurePrefs.get(it) } }.getOrNull() ?: return emptySet()
        val raw = runCatching { prefs.getString(KEY, "") ?: "" }.getOrDefault("")
        if (raw == cacheRaw) return cache
        cache = parse(raw)
        cacheRaw = raw
        return cache
    }

    internal fun parse(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

    internal fun serialize(ids: Set<String>): String = ids.sorted().joinToString(",")

    fun isRemoved(id: String): Boolean = id in removedIds()

    /** مزود دردشة صالح بديل (أول مزود API متاح غير الوكيل المحلي). */
    fun fallbackId(): String =
        PROVIDERS.firstOrNull { it.group == ProviderGroup.API && it.id != "local_proxy" }?.id
            ?: PROVIDERS.firstOrNull()?.id
            ?: "gemini"

    /** يحوّل معرّفاً قد يكون محذوفاً إلى معرّف صالح. */
    fun resolve(id: String): String = if (id.isBlank() || isRemoved(id)) fallbackId() else id

    /** المدمجون المحذوفون (للعرض في قائمة الاستعادة). */
    fun removedBuiltins(): List<ProviderInfo> {
        val ids = removedIds()
        return BUILTIN_PROVIDERS.filter { it.id in ids }
    }

    /**
     * يحذف المزود. يعيد false (ولا يغيّر شيئاً) إن كان الحذف سيترك التطبيق بلا أي مزود دردشة API،
     * فلا يمكن أن يصير التطبيق في حالة معطوبة بالحذف.
     */
    fun remove(prefs: SharedPreferences, id: String): Boolean {
        if (id.isBlank()) return false
        val isApiChat = PROVIDERS.firstOrNull { it.id == id }?.group == ProviderGroup.API
        if (isApiChat && PROVIDERS.none { it.id != id && it.group == ProviderGroup.API && it.id != "local_proxy" }) return false
        when {
            id.startsWith(UserPlatforms.PREFIX) -> UserPlatforms.remove(prefs, id)
            id.startsWith("imp_") -> ImportedProviders.remove(prefs, id)
            else -> {
                prefs.edit().putString(KEY, serialize(parse(prefs.getString(KEY, "") ?: "") + id)).apply()
                cacheRaw = null
            }
        }
        touch()
        if ((prefs.getString("provider", "") ?: "").trim() == id) {
            prefs.edit().putString("provider", fallbackId()).apply()
        }
        return true
    }

    fun restore(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(KEY, serialize(parse(prefs.getString(KEY, "") ?: "") - id)).apply()
        cacheRaw = null
        touch()
    }

    fun restoreAll(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
        cacheRaw = null
        touch()
    }
}

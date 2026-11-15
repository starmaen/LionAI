package com.lion.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * تصنيف أخطاء المزود وتبريده زمنياً.
 *
 * المشكلة التي يعالجها: عند نفاد حصة المزود في منتصف مهمة، كان الرد يُعدّ «تقدّماً» فلا ينتقل العمل لمزود آخر،
 * وكانت المحاولات تُهدر على مزودات معروفة النفاد. الآن: كل خطأ يُصنَّف، والمزود المستنفد يُبرَّد فيُستبعد من
 * الترشيح حتى ينتهي تبريده، والخطأ العابر يُعاد قبل التبديل.
 */
object QuotaGuard {
    enum class Kind { RATE, QUOTA, AUTH, TRANSIENT, OTHER }

    private val until = ConcurrentHashMap<String, Long>()
    private val kinds = ConcurrentHashMap<String, Kind>()

    /** قابل للاستبدال في الاختبارات. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val RATE_WORDS = listOf("429", "rate limit", "rate_limit", "too many requests", "حد الاستخدام", "حد الطلبات", "تجاوزت حد")
    private val QUOTA_WORDS = listOf(
        "quota", "exceeded", "insufficient", "credit", "billing", "freeusagelimit", "payment required", "402",
        "نفد", "نفدت", "رصيد", "الحصة", "حصتك"
    )
    private val AUTH_WORDS = listOf("401", "403", "invalid api key", "unauthorized", "المفتاح غير صالح", "توكن", "غير مقبول")
    private val TRANSIENT_WORDS = listOf(
        "timeout", "timed out", "مهلة", "502", "503", "504", "overloaded", "unavailable", "connection",
        "unable to resolve", "reset", "eof", "network", "الشبكة", "الاتصال"
    )

    fun classify(reply: String): Kind {
        val t = reply.trim().lowercase()
        if (t.isEmpty()) return Kind.TRANSIENT
        return when {
            t.startsWith("💳") || t.startsWith("🆓") -> Kind.QUOTA
            t.startsWith("🔑") -> Kind.AUTH
            t.startsWith("⏳") -> Kind.RATE
            RATE_WORDS.any { it in t } -> Kind.RATE
            QUOTA_WORDS.any { it in t } -> Kind.QUOTA
            AUTH_WORDS.any { it in t } -> Kind.AUTH
            TRANSIENT_WORDS.any { it in t } -> Kind.TRANSIENT
            else -> Kind.OTHER
        }
    }

    /** مدة التبريد: الحد المؤقت دقيقة ونصف، النفاد ست ساعات (الحصص المجانية تتجدد يومياً)، والمفتاح الخاطئ نصف ساعة. */
    fun cooldownMs(kind: Kind): Long = when (kind) {
        Kind.RATE -> 90_000L
        Kind.QUOTA -> 6L * 60 * 60 * 1000
        Kind.AUTH -> 30L * 60 * 1000
        Kind.TRANSIENT -> 20_000L
        Kind.OTHER -> 0L
    }

    /** هل يستحق الخطأ إعادة المحاولة على المزود نفسه قبل التبديل؟ (عابر أو حد قصير فقط) */
    fun retryable(kind: Kind): Boolean = kind == Kind.TRANSIENT || kind == Kind.RATE

    /** انتظار مناسب قبل إعادة المحاولة رقم [attempt] (يبدأ من 1)، بسقف 20 ثانية. */
    fun backoffMs(kind: Kind, attempt: Int): Long =
        (if (kind == Kind.RATE) 8_000L else 2_000L).times(attempt.coerceAtLeast(1)).coerceAtMost(20_000L)

    fun mark(provider: String, kind: Kind) {
        if (provider.isBlank()) return
        val ms = cooldownMs(kind)
        if (ms <= 0L) return
        until[provider] = clock() + ms
        kinds[provider] = kind
    }

    fun isCooling(provider: String): Boolean {
        val u = until[provider] ?: return false
        if (clock() >= u) {
            until.remove(provider); kinds.remove(provider)
            return false
        }
        return true
    }

    fun remainingMs(provider: String): Long = ((until[provider] ?: 0L) - clock()).coerceAtLeast(0L)

    fun kindOf(provider: String): Kind? = if (isCooling(provider)) kinds[provider] else null

    /** نجاح فعلي أو مفتاح جديد يلغي التبريد. */
    fun clear(provider: String) {
        until.remove(provider); kinds.remove(provider)
    }

    /** نص قصير للمستخدم يشرح لماذا استُبعد المزود. */
    fun describe(provider: String): String {
        val k = kindOf(provider) ?: return ""
        val mins = (remainingMs(provider) / 60_000L).coerceAtLeast(1)
        return when (k) {
            Kind.QUOTA -> "نفدت حصته (يعود بعد نحو ${if (mins >= 60) "${mins / 60} س" else "$mins د"})"
            Kind.RATE -> "حد طلبات مؤقت (يعود بعد $mins د)"
            Kind.AUTH -> "المفتاح غير صالح"
            else -> "خطأ عابر"
        }
    }

    internal fun resetForTests() {
        until.clear(); kinds.clear(); clock = { System.currentTimeMillis() }
    }
}

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
    enum class Kind { RATE, QUOTA, AUTH, TRANSIENT, CONTEXT, OTHER }

    private val until = ConcurrentHashMap<String, Long>()
    private val kinds = ConcurrentHashMap<String, Kind>()
    private val strikes = ConcurrentHashMap<String, Int>()

    /** قابل للاستبدال في الاختبارات. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // أكواد HTTP تُطابَق ككلمة مستقلة فقط (\b429\b) كي لا تُلتقط من أرقام عشوائية (معرّفات/أحجام).
    private val RATE_CODE = Regex("(?<![\\w.])429(?![\\w])")
    private val QUOTA_CODE = Regex("(?<![\\w.])402(?![\\w])")
    private val AUTH_CODE = Regex("(?<![\\w.])40[13](?![\\w])")
    private val TRANSIENT_CODE = Regex("(?<![\\w.])50[234](?![\\w])")
    private val CONTEXT_CODE = Regex("(?<![\\w.])413(?![\\w])")
    private val RATE_WORDS = listOf("rate limit", "rate_limit", "too many requests", "حد الاستخدام", "حد الطلبات", "تجاوزت حد")
    /** تجاوز طول السياق/حجم الطلب: ليس نفاد حصة، علاجه ضغط السجل لا حظر المزود. يُفحص قبل QUOTA. */
    private val CONTEXT_WORDS = listOf(
        "context length", "context_length", "context window", "maximum context", "max context", "prompt is too long",
        "prompt too long", "too many tokens", "token limit", "tokens exceed", "request too large", "payload too large",
        "request entity too large", "input is too long", "reduce the length",
        "طول السياق", "السياق طويل", "الطلب كبير", "حجم الطلب", "تجاوز الحد الأقصى للرموز"
    )
    // عبارات حصة صريحة فقط: حُذفت الكلمات العامة «exceeded» و«credit» لأنها تظهر في أخطاء أخرى كثيرة.
    private val QUOTA_WORDS = listOf(
        "quota", "insufficient_quota", "insufficient credit", "credit balance", "out of credits", "billing",
        "freeusagelimit", "payment required", "daily limit", "monthly limit",
        "نفدت حصت", "نفد رصيد", "رصيد غير كاف", "لا يوجد رصيد", "حصتك"
    )
    private val AUTH_WORDS = listOf("invalid api key", "incorrect api key", "unauthorized", "forbidden", "المفتاح غير صالح", "مفتاح غير صالح")
    private val TRANSIENT_WORDS = listOf(
        "timeout", "timed out", "مهلة", "overloaded", "unavailable", "connection",
        "unable to resolve", "reset", "eof", "network", "الشبكة", "الاتصال"
    )

    fun classify(reply: String): Kind {
        val t = reply.trim().lowercase()
        if (t.isEmpty()) return Kind.TRANSIENT
        return when {
            // الأيقونة تُحترم أولاً، لكن «تجاوز السياق» يغلب حتى لو غُلِّف بأيقونة الحصة.
            CONTEXT_CODE.containsMatchIn(t) || CONTEXT_WORDS.any { it in t } -> Kind.CONTEXT
            t.startsWith("💳") || t.startsWith("🆓") -> Kind.QUOTA
            t.startsWith("🔑") -> Kind.AUTH
            t.startsWith("⏳") -> Kind.RATE
            RATE_CODE.containsMatchIn(t) || RATE_WORDS.any { it in t } -> Kind.RATE
            QUOTA_CODE.containsMatchIn(t) || QUOTA_WORDS.any { it in t } -> Kind.QUOTA
            AUTH_CODE.containsMatchIn(t) || AUTH_WORDS.any { it in t } -> Kind.AUTH
            TRANSIENT_CODE.containsMatchIn(t) || TRANSIENT_WORDS.any { it in t } -> Kind.TRANSIENT
            else -> Kind.OTHER
        }
    }

    /**
     * مدة التبريد. النفاد كان ست ساعات ثابتة فيُحظر المزود يوماً عمل كاملاً بخطأ واحد؛ الآن يتصاعد
     * 15 دقيقة ← ساعة ← 6 ساعات بحسب تكرار الفشل المتتالي، ويُلغى فوراً عند أي نجاح أو فحص سليم.
     * تجاوز السياق لا يُبرَّد أبداً (مشكلة الطلب لا المزود).
     */
    fun cooldownMs(kind: Kind, strikes: Int = 1): Long = when (kind) {
        Kind.RATE -> 90_000L
        Kind.QUOTA -> when {
            strikes <= 1 -> 15L * 60 * 1000
            strikes == 2 -> 60L * 60 * 1000
            else -> 6L * 60 * 60 * 1000
        }
        Kind.AUTH -> 30L * 60 * 1000
        Kind.TRANSIENT -> 20_000L
        Kind.CONTEXT, Kind.OTHER -> 0L
    }

    /** هل يستحق الخطأ إعادة المحاولة على المزود نفسه قبل التبديل؟ (عابر أو حد قصير فقط) */
    fun retryable(kind: Kind): Boolean = kind == Kind.TRANSIENT || kind == Kind.RATE

    /** انتظار مناسب قبل إعادة المحاولة رقم [attempt] (يبدأ من 1)، بسقف 20 ثانية. */
    fun backoffMs(kind: Kind, attempt: Int): Long =
        (if (kind == Kind.RATE) 8_000L else 2_000L).times(attempt.coerceAtLeast(1)).coerceAtMost(20_000L)

    fun mark(provider: String, kind: Kind) {
        if (provider.isBlank()) return
        if (kind != Kind.QUOTA) strikes.remove(provider)
        val n = if (kind == Kind.QUOTA) (strikes[provider] ?: 0) + 1 else 1
        val ms = cooldownMs(kind, n)
        if (ms <= 0L) return
        if (kind == Kind.QUOTA) strikes[provider] = n
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
        until.remove(provider); kinds.remove(provider); strikes.remove(provider)
    }

    /** نص قصير للمستخدم يشرح لماذا استُبعد المزود. */
    fun describe(provider: String): String {
        val k = kindOf(provider) ?: return ""
        val mins = (remainingMs(provider) / 60_000L).coerceAtLeast(1)
        return when (k) {
            Kind.QUOTA -> "نفدت حصته (يعود بعد نحو ${if (mins >= 60) "${mins / 60} س" else "$mins د"})"
            Kind.RATE -> "حد طلبات مؤقت (يعود بعد $mins د)"
            Kind.AUTH -> "المفتاح غير صالح"
            Kind.CONTEXT -> "السياق أطول من المسموح (يُضغط السجل ويُعاد)"
            else -> "خطأ عابر"
        }
    }

    internal fun resetForTests() {
        until.clear(); kinds.clear(); strikes.clear(); clock = { System.currentTimeMillis() }
    }
}

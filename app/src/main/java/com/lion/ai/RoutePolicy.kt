package com.lion.ai

import android.content.SharedPreferences

/**
 * مصدر الحقيقة الوحيد لتوجيه الطلبات.
 *
 * تستخدمه شاشة المحادثة والخادم المحلي معاً، حتى لا يختلف قرار «اليدوي/التلقائي»
 * أو المزود الفعلي بين الواجهة والطلب المرسل. القاعدة:
 * - اليدوي: المزود المختار فقط، بلا تبديل.
 * - اليدوي + مساعدة: المزود المختار أولاً، ثم بدائل العقل المركزي عند فشله.
 * - التلقائي: العقل المركزي يختار الأنسب ثم يوسّع البدائل عند الفشل.
 */
object RoutePolicy {
    const val LOCAL_PROXY = "local_proxy"
    const val CUSTOM = "custom"

    fun selectedProvider(prefs: SharedPreferences): String {
        val raw = (prefs.getString("provider", "gemini") ?: "gemini").trim().ifBlank { "gemini" }
        val info = PROVIDERS.firstOrNull { it.id == raw }
        return if (info != null && info.group == ProviderGroup.API) raw else "gemini"
    }

    fun isManual(prefs: SharedPreferences): Boolean = !prefs.getBoolean("auto_route", true)

    fun assistEnabled(prefs: SharedPreferences): Boolean =
        isManual(prefs) && prefs.getBoolean("manual_assist", false)

    fun modelFor(prefs: SharedPreferences, provider: String): String =
        (prefs.getString("model_$provider", "") ?: "").trim()

    /** المسار اليدوي كما تراه شاشة المحادثة: قد يكون الخادم المحلي نفسه. */
    fun appManualRoute(prefs: SharedPreferences): Route {
        val provider = selectedProvider(prefs)
        return Route(provider, modelFor(prefs, provider))
    }

    /** المزود الفعلي خلف الخادم المحلي، أو المزود المختار مباشرة. */
    fun effectiveProvider(prefs: SharedPreferences): String {
        val provider = selectedProvider(prefs)
        return if (provider == LOCAL_PROXY) LocalAIProxy.backendProvider(prefs) else provider
    }

    /** اسم يوضح المزود الفعلي في الواجهة، مع إبقاء صفة الخادم المحلي. */
    fun effectiveLabel(prefs: SharedPreferences): String {
        val provider = selectedProvider(prefs)
        if (provider != LOCAL_PROXY) return shortName(provider)
        return shortName(LocalAIProxy.backendProvider(prefs)) + " · محلي"
    }

    fun shortName(provider: String): String =
        if (provider == "openai") "ChatGPT" else providerInfo(provider).shortName

    /** المزود الخلفي الذي ينفّذ الطلب فعلياً داخل الخادم المحلي. */
    fun serverBackendRoute(prefs: SharedPreferences): Route {
        val provider = LocalAIProxy.backendProvider(prefs)
        return Route(provider, modelFor(prefs, provider))
    }

    /** خطة شاشة المحادثة. */
    fun appPlan(prefs: SharedPreferences, kind: TaskKind, mode: AnswerMode): List<Route> {
        val chosen = appManualRoute(prefs)
        // عند اختيار الخادم المحلي في الوضع التلقائي نترك الاختيار للخادم نفسه،
        // لأنه هو من يدير المزودات ويطبّق نفس سياسة الأوضاع. البدائل المباشرة تبقى شبكة أمان في runChat.
        if (!isManual(prefs) && chosen.provider == LOCAL_PROXY) return listOf(chosen)
        val central = SmartRouter.plan(prefs, kind, mode)
        return combine(
            manual = isManual(prefs),
            assist = assistEnabled(prefs),
            chosen = chosen,
            central = central,
            rescue = emptyList(),
            limit = 3
        )
    }

    /**
     * خطة الخادم المحلي. التلقائي يستخدم العقل المركزي، واليدوي يبقى يدوياً،
     * ومساعدة العقل المركزي تضيف البدائل فقط عند فشل المزود المختار.
     */
    fun serverPlan(
        prefs: SharedPreferences,
        kind: TaskKind,
        mode: AnswerMode,
        chatMode: ChatMode
    ): List<Route> {
        val chosen = serverBackendRoute(prefs)
        val central = SmartRouter.plan(prefs, kind, mode)
            .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        val expand = CentralBrain.policy(kind, chatMode, central = true).expandFallbacks
        val rescue = if ((!isManual(prefs) || assistEnabled(prefs)) && expand) {
            CentralBrain.rescueRoutes(prefs, kind, central, image = false)
                .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        } else {
            emptyList()
        }
        return combine(
            manual = isManual(prefs),
            assist = assistEnabled(prefs),
            chosen = chosen,
            central = central,
            rescue = rescue,
            limit = 6
        ).filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
    }

    /**
     * دمج المسارات مع الحفاظ على الترتيب ومنع التكرار.
     * منفصل عن SharedPreferences ليكون قابلاً للاختبار مباشرة.
     */
    fun combine(
        manual: Boolean,
        assist: Boolean,
        chosen: Route,
        central: List<Route>,
        rescue: List<Route>,
        limit: Int = 6
    ): List<Route> {
        val routes = when {
            !manual -> central.ifEmpty { listOf(chosen) } + rescue
            !assist -> listOf(chosen)
            else -> listOf(chosen) + central + rescue
        }
        return routes.distinctBy { it.provider }.take(limit.coerceAtLeast(1))
    }
}

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

    /** اسم المزود الفعلي المعروض في شاشة المحادثة. */
    fun effectiveName(prefs: SharedPreferences): String {
        val provider = selectedProvider(prefs)
        val actual = if (provider == LOCAL_PROXY) LocalAIProxy.backendProvider(prefs) else provider
        return shortName(actual)
    }

    /** وصف قصير تحت صندوق الكتابة: محلي / مجاني / مدفوع. */
    fun effectiveNote(prefs: SharedPreferences): String {
        val provider = selectedProvider(prefs)
        if (provider == LOCAL_PROXY) return "محلي"
        val info = providerInfo(provider)
        return if (info.keyless || info.free) "مجاني" else "مدفوع"
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

    /**
     * خطة شاشة المحادثة.
     * - التلقائي: العقل المركزي يختار مباشرة من المزودات (لا يفرض الخادم المحلي).
     * - اليدوي: المزود المختار أولاً، ثم بدائل تلقائية فقط إذا فشل. لا يتدخل
     *   العقل المركزي في الاختيار طالما المزود المختار يرد.
     */
    fun appPlan(prefs: SharedPreferences, kind: TaskKind, mode: AnswerMode): List<Route> {
        val chosen = appManualRoute(prefs)
        if (isManual(prefs)) {
            val rescue = CentralBrain.rescueRoutes(prefs, kind, listOf(chosen), image = false)
            return combine(
                manual = true,
                assist = true,
                chosen = chosen,
                central = emptyList(),
                rescue = rescue,
                limit = 6
            )
        }
        val direct = SmartRouter.plan(prefs, kind, mode)
            .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        // التلقائي: الخادم المحلي هو المنفّذ، والعقل المركزي يختار أفضل مزود داخله.
        // إن لم يكن الخادم جاهزاً نعود للتوجيه المباشر بلا أي تغيير في السلوك.
        val server = if (LocalAIProxy.getUrl().isNotBlank()) Route(LOCAL_PROXY, "") else null
        val central = (if (server != null) listOf(server) else emptyList()) + direct
        return combine(
            manual = false,
            assist = false,
            chosen = direct.firstOrNull() ?: chosen,
            central = central,
            rescue = emptyList(),
            limit = 6
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
        val manual = isManual(prefs)
        val chosen = serverBackendRoute(prefs)
        val central = SmartRouter.plan(prefs, kind, mode)
            .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        val expand = CentralBrain.policy(kind, chatMode, central = true).expandFallbacks
        // اليدوي: المزود المختار أولاً ثم بدائل عند فشله. التلقائي: العقل المركزي ثم البدائل.
        val rescue = if (manual || expand) {
            CentralBrain.rescueRoutes(prefs, kind, if (manual) listOf(chosen) else central, image = false)
                .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        } else {
            emptyList()
        }
        return combine(
            manual = manual,
            assist = true,
            chosen = chosen,
            central = if (manual) emptyList() else central,
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

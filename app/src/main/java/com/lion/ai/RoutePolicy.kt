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
    fun appPlan(
        prefs: SharedPreferences,
        kind: TaskKind,
        mode: AnswerMode,
        chatId: String = ActiveChat.id
    ): List<Route> {
        val chosen = appManualRoute(prefs)
        if (isManual(prefs)) {
            ProviderStick.clear(prefs, chatId)
            // اليدوي لا يبدّل المزود أبداً إلا إذا فعّل المستخدم «مساعدة العقل المركزي».
            val assist = assistEnabled(prefs)
            val rescue = if (assist) {
                CentralBrain.rescueRoutes(prefs, kind, listOf(chosen), image = false)
            } else {
                emptyList()
            }
            return combine(
                manual = true,
                assist = assist,
                chosen = chosen,
                central = emptyList(),
                rescue = rescue,
                limit = 6
            )
        }
        val direct = applySticky(
            prefs, kind, chatId,
            SmartRouter.plan(prefs, kind, mode).filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        )
        // التلقائي: الخادم المحلي هو المنفّذ، والعقل المركزي يختار أفضل مزود داخله.
        // إن لم يكن الخادم جاهزاً نعود للتوجيه المباشر بلا أي تغيير في السلوك.
        val server = if (LocalAIProxy.getUrl().isNotBlank() && providerEnabled(prefs, LOCAL_PROXY)) {
            Route(LOCAL_PROXY, "")
        } else {
            null
        }
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
     * ثبات المزود: في التلقائي، المزود الذي أجاب آخر مرة في هذه المحادثة يبقى هو المزود طالما يرد،
     * فلا يبدّله العقل المركزي من تلقاء نفسه. يتغير فقط عند فشله أو عند تغيير المستخدم الاختيار يدوياً
     * (يتغير توقيع الإعدادات فيبطل الثبات). أسئلة المجاملة السريعة والصور تُستثنى.
     */
    fun applySticky(prefs: SharedPreferences, kind: TaskKind, chatId: String, routes: List<Route>): List<Route> {
        if (chatId.isBlank() || isManual(prefs)) return routes
        if (kind == TaskKind.FAST || kind == TaskKind.VISION || kind == TaskKind.IMAGE_EDIT) return routes
        val stick = ProviderStick.get(prefs, chatId) ?: return routes
        if (stick == LOCAL_PROXY || stick == CUSTOM) return routes
        if (!SmartRouter.available(prefs, stick) || SmartRouter.failureCount(stick) >= 2) return routes
        val existing = routes.firstOrNull { it.provider == stick }
        val route = existing ?: Route(
            stick,
            SmartRouter.candidates(kind).firstOrNull { it.provider == stick }?.model
                ?.takeIf { it.isNotBlank() } ?: modelFor(prefs, stick)
        )
        return listOf(route) + routes.filter { it.provider != stick }
    }

    /**
     * خطة الخادم المحلي. التلقائي يستخدم العقل المركزي، واليدوي يبقى يدوياً،
     * ومساعدة العقل المركزي تضيف البدائل فقط عند فشل المزود المختار.
     */
    fun serverPlan(
        prefs: SharedPreferences,
        kind: TaskKind,
        mode: AnswerMode,
        chatMode: ChatMode,
        chatId: String = ActiveChat.id
    ): List<Route> {
        val manual = isManual(prefs)
        val assist = assistEnabled(prefs)
        val chosen = serverBackendRoute(prefs)
        if (manual) ProviderStick.clear(prefs, chatId)
        val central = applySticky(
            prefs, kind, chatId,
            SmartRouter.plan(prefs, kind, mode).filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        )
        val expand = CentralBrain.policy(kind, chatMode, central = true).expandFallbacks
        // اليدوي: المزود المختار أولاً ثم بدائل عند فشله فقط إن فعّل المستخدم المساعدة.
        // التلقائي: العقل المركزي ثم البدائل.
        val rescue = if ((manual && assist) || (!manual && expand)) {
            CentralBrain.rescueRoutes(prefs, kind, if (manual) listOf(chosen) else central, image = false)
                .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
        } else {
            emptyList()
        }
        return combine(
            manual = manual,
            assist = if (manual) assist else true,
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

/** معرّف المحادثة النشطة (تضبطه شاشة المحادثة قبل الإرسال ويقرؤه الخادم المحلي داخل التطبيق). */
object ActiveChat {
    @Volatile
    var id: String = ""
}

/** يحفظ لكل محادثة المزود الذي نجح آخر مرة في التلقائي، مع توقيع الإعدادات لإبطاله عند أي تغيير يدوي. */
object ProviderStick {
    private fun key(chatId: String) = "route_stick_$chatId"

    private fun signature(prefs: SharedPreferences): String =
        RoutePolicy.isManual(prefs).toString() + "|" + RoutePolicy.selectedProvider(prefs) + "|" +
            LocalAIProxy.backendProvider(prefs)

    fun get(prefs: SharedPreferences, chatId: String): String? {
        if (chatId.isBlank()) return null
        val raw = prefs.getString(key(chatId), null) ?: return null
        val provider = raw.substringBefore('|')
        val sig = raw.substringAfter('|', "")
        return if (provider.isNotBlank() && sig == signature(prefs)) provider else null
    }

    fun set(prefs: SharedPreferences, chatId: String, provider: String) {
        if (chatId.isBlank() || provider.isBlank()) return
        if (provider == RoutePolicy.LOCAL_PROXY || provider == RoutePolicy.CUSTOM) return
        if (RoutePolicy.isManual(prefs)) return
        val value = provider + "|" + signature(prefs)
        if (prefs.getString(key(chatId), null) != value) prefs.edit().putString(key(chatId), value).apply()
    }

    fun clear(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank() && prefs.contains(key(chatId))) prefs.edit().remove(key(chatId)).apply()
    }
}

/** آخر مزود أجاب فعلياً في المحادثة، لعرض المزود العامل أسفل صندوق الكتابة. */
object LastRoute {
    private fun key(chatId: String) = "route_last_$chatId"

    fun get(prefs: SharedPreferences, chatId: String): Route? {
        if (chatId.isBlank()) return null
        val raw = prefs.getString(key(chatId), null) ?: return null
        val provider = raw.substringBefore('|').trim()
        if (provider.isBlank()) return null
        return Route(provider, raw.substringAfter('|', "").trim())
    }

    fun set(prefs: SharedPreferences, chatId: String, route: Route) {
        if (chatId.isBlank() || route.provider.isBlank()) return
        val value = route.provider + "|" + route.model
        if (prefs.getString(key(chatId), null) != value) prefs.edit().putString(key(chatId), value).apply()
    }

    fun clear(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank() && prefs.contains(key(chatId))) prefs.edit().remove(key(chatId)).apply()
    }
}

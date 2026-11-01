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

    // ===== قنوات التنفيذ (مباشر / خادم خارجي / خادم محلي) =====
    //
    // الفكرة: اختيار المزود شيء، والطريق إليه شيء آخر. يمكن تشغيل المزود
    // مباشرة، أو عبر الخادم المحلي، أو عبر الخادم الخارجي، أو عبر أي تركيبة
    // منها — يجرّبها التطبيق بالترتيب حتى يرد أحدها، فلا ينقطع الرد بتعطّل
    // طريق واحد.

    private const val KEY_CHANNEL = "channel_"

    /** هل قناة التنفيذ مفعّلة؟ (مباشر افتراضياً، والخارجي إن كان مضبوطاً، والمحلي بطلب المستخدم). */
    fun channelEnabled(prefs: SharedPreferences, via: String): Boolean {
        val key = KEY_CHANNEL + via
        return when (via) {
            Channel.DIRECT -> prefs.getBoolean(key, true)
            Channel.GATEWAY -> if (prefs.contains(key)) {
                prefs.getBoolean(key, false)
            } else {
                // لم يضبطها المستخدم بعد: تعمل تلقائياً إن كان الخادم مضبوطاً ومفعّلاً.
                val config = WebGateway.load(prefs)
                config.enabled && config.mode != WebGateway.MODE_OFF && config.configured && config.token.isNotBlank()
            }
            Channel.LOCAL -> prefs.getBoolean(key, false)
            else -> true
        }
    }

    fun setChannelEnabled(prefs: SharedPreferences, via: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHANNEL + via, enabled).apply()
    }

    /** القنوات المفعّلة فعلاً: السليمة أولاً، ثم التي تعطّلت مؤقتاً كشبكة أمان أخيرة. */
    fun channels(prefs: SharedPreferences, provider: String): List<String> {
        WebGateway.load(prefs)
        val enabled = Channel.ORDER.filter { channelEnabled(prefs, it) }
        val healthy = enabled.filter { SmartRouter.channelFailures(provider, it) < 2 }
        return healthy + enabled.filterNot { it in healthy }
    }

    /**
     * بدائل قناة الوصول لمزود واحد: نفس المزود عبر كل قناة مفعّلة.
     * الخادم المحلي لا يتكرر (له بطاقة واحدة) ويظهر مرة واحدة كشبكة أمان.
     */
    fun channelVariants(
        prefs: SharedPreferences,
        route: Route,
        allowLocal: Boolean = true
    ): List<Route> {
        if (route.provider == LOCAL_PROXY) return listOf(Route(LOCAL_PROXY, "", Channel.LOCAL))
        if (route.provider == CUSTOM) return listOf(route.copy(via = Channel.DIRECT))
        return channels(prefs, route.provider).mapNotNull { via ->
            when (via) {
                Channel.GATEWAY -> route.copy(via = Channel.GATEWAY)
                    .takeIf { WebGateway.shouldRoute(route.provider) }
                Channel.LOCAL -> Route(LOCAL_PROXY, "", Channel.LOCAL)
                    .takeIf { allowLocal && LocalAIProxy.isRunning() }
                else -> route.copy(via = Channel.DIRECT)
            }
        }
    }

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
        chatId: String = ActiveChat.id,
        chatMode: ChatMode = ChatMode.GENERAL
    ): List<Route> {
        WebGateway.load(prefs)
        val chosen = appManualRoute(prefs)
        if (isManual(prefs)) {
            ProviderStick.clear(prefs, chatId)
            // اليدوي: مزودك المختار أولاً عبر القنوات التي فعّلتها (مباشر/خارجي/محلي).
            // لا يبدّل العقل المركزي مزودك إلا إذا فعّلت «مساعدة العقل المركزي».
            val assist = assistEnabled(prefs)
            // إن أغلق المستخدم كل القنوات نبقي المباشر مفعّلاً حتى لا تتعطل المحادثة.
            val chain = channelVariants(prefs, chosen).ifEmpty { listOf(chosen.copy(via = Channel.DIRECT)) }
            val rescue = if (assist) {
                CentralBrain.rescueRoutes(prefs, kind, listOf(chosen), image = false)
                    .map { it.copy(via = Channel.DIRECT) }
                    .filterNot { route -> chain.any { it.provider == route.provider } }
            } else {
                emptyList()
            }
            return (chain + rescue)
                .distinctBy { it.provider + "|" + it.via }
                .take(8)
        }
        // التلقائي: العقل المركزي يختار المزود الأنسب، ثم يوسّعه عبر كل قناة مفعّلة
        // قبل الانتقال إلى مزود آخر — فلا يُستبدل مزود قوي لمجرّد تعطّل طريق واحد إليه.
        val ranked = applySticky(
            prefs, kind, chatId,
            CentralBrain.rankRoutes(
                prefs, kind,
                SmartRouter.plan(prefs, kind, mode)
                    .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM },
                chatMode
            )
        )
        val central = mutableListOf<Route>()
        for (candidate in ranked.take(3)) {
            central += channelVariants(prefs, candidate, allowLocal = false)
        }
        // الخادم المحلي شبكة الأمان الأخيرة: يدير داخله سلسلة البدائل كاملة.
        if (channelEnabled(prefs, Channel.LOCAL) && LocalAIProxy.isRunning()) {
            central += Route(LOCAL_PROXY, "", Channel.LOCAL)
        }
        return central
            .distinctBy { it.provider + "|" + it.via }
            .take(8)
            .ifEmpty { listOf(chosen.copy(via = Channel.DIRECT)) }
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
        WebGateway.load(prefs)
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
        // داخل الخادم المحلي: القنوات المفعّلة تُطبَّق على المزود المختار أيضاً،
        // باستثناء القناة المحلية نفسها حتى لا يعود الطلب إلى الخادم في حلقة.
        val manualChain = if (manual) {
            channelVariants(prefs, chosen, allowLocal = false)
                .ifEmpty { listOf(chosen.copy(via = Channel.DIRECT)) }
        } else {
            emptyList()
        }
        return (combine(
            manual = manual,
            assist = if (manual) assist else true,
            chosen = chosen,
            central = if (manual) emptyList() else central,
            rescue = rescue,
            limit = 6
        ) + manualChain)
            .distinctBy { it.provider + "|" + it.via }
            .filterNot { it.provider == LOCAL_PROXY || it.provider == CUSTOM }
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
        // الصيغة: provider|model|via (القيم القديمة بلا via تُقرأ كقناة تلقائية).
        val rest = raw.substringAfter('|', "")
        val model = rest.substringBefore('|').trim()
        val via = rest.substringAfter('|', "").trim()
        return Route(provider, model, via)
    }

    fun set(prefs: SharedPreferences, chatId: String, route: Route) {
        if (chatId.isBlank() || route.provider.isBlank()) return
        val value = route.provider + "|" + route.model + "|" + route.via
        if (prefs.getString(key(chatId), null) != value) prefs.edit().putString(key(chatId), value).apply()
    }

    fun clear(prefs: SharedPreferences, chatId: String) {
        if (chatId.isNotBlank() && prefs.contains(key(chatId))) prefs.edit().remove(key(chatId)).apply()
    }
}

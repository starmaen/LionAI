package com.lion.ai

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Lion-1 («الأسد»): مزود مدمج يعتمد على نفسه أولاً.
 *
 * ترتيب المسارات (كل مسار يحمل مفتاحه الخاص حتى لا تتكرر الانتظارات):
 *   1) **المحرّك المدمج** — llama.cpp (llama-server) داخل بيئة Alpine/proot التي يملكها التطبيق،
 *      على 127.0.0.1:44045، بنموذج GGUF منزَّل في مساحة العمل. يعمل بلا إنترنت وبلا أي مفتاح،
 *      ويدعم tools الأصلية (function calling) فيستعمل أدوات التطبيق مباشرة.
 *   2) **رابط المستخدم** (Base URL في الإعدادات) — نموذجك المضبوط على خادمك.
 *   3) **الخادم المحلي 44045** — إن شغّلت llama.cpp بنفسك على الجهاز/الشبكة دون تفعيل المحرّك.
 *   4) **البوابة الخارجية** — آخر مسار احتياطي فقط (يبقى معطّلاً ما لم يفعّله المستخدم).
 *
 * الهوية وتعليمات المهام تُحقنان في التطبيق، فلا تعتمد على إصدار البوابة.
 * آخر مسار نجح يُجرَّب أولاً، والمسار الفاشل يُتجاوز 60 ثانية.
 */
internal object LionOne {
    const val ID = "lion1"
    @Volatile var warmed: Boolean = false
    private val expInstance: LionExperience by lazy { LionLearn.experienceStore() }
    fun experience(): LionExperience = expInstance
    const val LOCAL_URL = "http://127.0.0.1:44045/v1/chat/completions"
    private const val COOLDOWN_MS = 60_000L

    /** مسار = وجهة استدعاء واحدة. key ثابت للتمييز بين المسارات المتشابهة في الرابط. */
    class Route(val key: String, val url: String, val token: String, val model: String, val label: String)

    enum class Task(val temp: Double, val prompt: String) {
        CODE(0.2, "المهمة: برمجة. أعطِ كوداً كاملاً يعمل داخل كتل كود، ثم شرحاً قصيراً."),
        TEACH(0.4, "المهمة: تعليم. اشرح خطوة بخطوة بمثال عملي ثم اقترح تمريناً صغيراً."),
        WRITE(0.8, "المهمة: كتابة. اكتب نصاً سليماً ومنظماً يناسب الغرض."),
        REASON(0.3, "المهمة: تحليل. فكّر بخطوات واضحة وقارن الخيارات ثم قدّم خلاصة."),
        TRANSLATE(0.2, "المهمة: ترجمة. ترجم بدقة وبأسلوب طبيعي دون إضافات."),
        SUMMARY(0.3, "المهمة: تلخيص. لخّص النقاط الجوهرية فقط."),
        GENERAL(0.6, "المهمة: محادثة عامة. كن ودوداً ومباشراً.")
    }

    private const val IDENTITY =
        "الهوية: أنت «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria. " +
            "أنت تعمل داخل التطبيق نفسه على جهاز المستخدم. " +
            "لا تذكر اسم أي مزود أو نموذج خلفي؛ إن سُئلت عن هويتك فأنت Lion-1 من Star Syria."

    /** أسماء النماذج الظاهرة في التطبيق (النموذج الخلفي يُحدَّد بالمسار لا بالمستخدم). */
    val MODELS = listOf("lion-1") + Task.values().map { "lion-1-" + it.name.lowercase() }

    fun task(model: String, message: String): Task {
        val forced = model.trim().lowercase().removePrefix("lion-1").removePrefix("-")
        Task.values().firstOrNull { it.name.lowercase() == forced }?.let { return it }
        val t = message.lowercase()
        fun has(vararg k: String) = k.any { t.contains(it) }
        return when {
            has("```", "كود", "برمج", "خطأ", "error", "function", "kotlin", "python", "gradle", "bug") -> Task.CODE
            has("اشرح", "علمني", "تعلم", "explain", "teach") -> Task.TEACH
            has("ترجم", "translate") -> Task.TRANSLATE
            has("لخص", "تلخيص", "summar") -> Task.SUMMARY
            has("اكتب", "صغ", "رسالة", "مقال", "write", "email") -> Task.WRITE
            has("حلل", "قارن", "لماذا", "احسب", "analy", "why") -> Task.REASON
            else -> Task.GENERAL
        }
    }

    /**
     * يضيف الهوية دائماً. وصف المهمة يُضاف فقط للمحادثة العادية؛ تعليمات الوكيل/العناوين/الصور
     * تبقى كما هي حتى لا نكسر بروتوكول الأدوات.
     */
    fun systemFor(base: String, task: Task): String =
        if (base == LLM_SYSTEM_PROMPT) "$base\n\n$IDENTITY\n${task.prompt}" else "$base\n\n$IDENTITY"

    private val badUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGood: String? = null

    /**
     * بناء المسارات بترتيب الأولوية. دالة صرفة (تعتمد على حالة المحرّك والمسجَّل فقط)
     * لتكون قابلة للاختبار على JVM.
     */
    fun buildRoutes(
        userUrl: String,
        gateways: List<Route>,
        engineAvailable: Boolean
    ): List<Route> {
        val out = mutableListOf<Route>()
        if (engineAvailable) {
            out += Route("engine", LionEngine.CHAT_URL, "", "lion-1", "المحرّك المدمج")
        }
        val custom = userUrl.trim()
        if (custom.isNotEmpty() && LlmClient.isSafeEndpoint(LlmClient.normalizeCustomUrl(custom))) {
            out += Route("user", LlmClient.normalizeCustomUrl(custom), "", "lion-1", "خادمك")
        }
        // خادم llama.cpp الذي يشغّله المستخدم بنفسه على 44045 (قبل تفعيل المحرّك أو بدلاً منه).
        if (out.none { it.url == LOCAL_URL }) {
            out += Route("local", LOCAL_URL, "", "auto", "خادم محلي")
        }
        out += gateways
        return out
    }

    fun routes(userKey: String): List<Route> {
        val gateways = mutableListOf<Route>()
        val relays = WebGateway.relays()
        if (relays.isNotEmpty()) {
            relays.forEach { gateways += Route("gw:" + it.url, WebGateway.chatUrl(it.url), it.token, "auto", it.name) }
        } else {
            val cfg = WebGateway.current()
            val url = cfg.url.ifBlank { WebGateway.DEFAULT_URL }
            gateways += Route("gw:$url", WebGateway.chatUrl(url), cfg.token, "auto", "البوابة")
        }
        // المحرّك يُدرَج في المسارات إذا كان يعمل أو جاهزاً للتشغيل (يبدأ عند أول طلب).
        val engine = LionEngine.isRunning() ||
            (LionEngine.phase == LionEngine.Phase.READY) ||
            (LionEngine.phase == LionEngine.Phase.STARTING)
        val built = buildRoutes(LlmClient.endpointOverrides[ID].orEmpty(), gateways, engine)
        // مفتاح المستخدم يُستخدم لمسار البوابة إن لم يكن للخادم مفتاح مسجَّل.
        val withKey = built.map {
            if (it.key.startsWith("gw:") && it.token.isBlank() && userKey.isNotBlank())
                Route(it.key, it.url, userKey, it.model, it.label) else it
        }
        val ordered = order(withKey)
        return if (offlineFirst) ordered else preferRemote(ordered)
    }

    /**
     * وضع «أولاً بلا إنترنت»: يُبقي المحرّك المحلي في الصدارة (للاستخدام دون شبكة).
     * الافتراضي false: نموذج 1.5B على الهاتف أبطأ وأضعف من مزودات البوابة، فتُجرَّب البوابة
     * أولاً (إن كان لها مفتاح) ويبقى المحرّك المحلي احتياطاً عند انقطاع الشبكة أو فشل المزودات.
     */
    @Volatile var offlineFirst: Boolean = false

    /**
     * هل تسبق مزوداتُك القوية (البوابة بمفتاح ثم «العقل» = مزوداتك المضبوطة) المحرّك المحلي؟
     * نعم افتراضياً؛ يتوقف فقط في وضع «أولاً بلا إنترنت». يحكم كذلك مسار الأدوات وميزانية التعليمات.
     */
    fun remoteFirstActive(userKey: String = ""): Boolean = !offlineFirst

    /**
     * يقدّم مسارات البوابة السليمة (غير المتجاوَزة مؤقتاً) على المحلية، ويحفظ الترتيب النسبي فيما عداها.
     * دالة صرفة لتُختبر على JVM.
     */
    internal fun preferRemote(ordered: List<Route>, nowMs: Long = System.currentTimeMillis()): List<Route> {
        fun remoteOk(r: Route) = r.key.startsWith("gw:") && r.token.isNotBlank() && (badUntil[r.key] ?: 0L) <= nowMs
        return ordered.filter(::remoteOk) + ordered.filterNot(::remoteOk)
    }

    /** آخر ناجح أولاً، ثم الباقي؛ المتجاوَز مؤقتاً يُؤخَّر إلى النهاية بدل حذفه. */
    internal fun order(routes: List<Route>): List<Route> {
        val now = System.currentTimeMillis()
        val (ok, cooling) = routes.partition { (badUntil[it.key] ?: 0L) <= now }
        val first = ok.sortedBy { if (it.key == lastGood) 0 else 1 }
        return first + cooling
    }

    /** فحص سريع (0.5ث) لمنفذ محلي: المرفوض يُتجاوز فوراً دون إعادة محاولات الشبكة البطيئة. */
    fun reachable(url: String): Boolean = try {
        val u = java.net.URI(url)
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 500); true }
    } catch (e: Exception) { false }

    /** يضغط التاريخ ضمن سياق المحرّك المدمج (نموذج صغير على الجهاز) مع ملخص لما حُذف. */
    fun fitHistory(history: List<ChatTurn>, system: String, message: String): List<ChatTurn> = runCatching {
        val ctx = ContextHolder.peek()?.let { LionEngine.ctx(SecurePrefs.get(it)) } ?: LionEngine.DEFAULT_CTX
        LionContext.fit(history, LionContext.historyBudget(ctx, system, message, 0))
    }.getOrDefault(history)

    fun markGood(r: Route) { lastGood = r.key; badUntil.remove(r.key) }
    fun markBad(r: Route) { badUntil[r.key] = System.currentTimeMillis() + COOLDOWN_MS; if (lastGood == r.key) lastGood = null }

    internal fun resetHealth() { badUntil.clear(); lastGood = null }
}

/**
 * المسار النصي: يجرّب المسارات بالترتيب، ويشغّل المحرّك المدمج عند الحاجة.
 * إن فشل كل شيء تُعاد رسالة واحدة تشرح ما ينقص فعلياً (لا خطأ شبكة غامض).
 */
internal fun LlmClient.callLionOne(
    message: String,
    apiKey: String,
    model: String,
    history: List<ChatTurn>,
    image: ImageAttachment?,
    systemPrompt: String
): String {
    val task = LionOne.task(model, message)
    // معارف تعلّمها Lion-1 من المزودين والمصادر (فارغة إن لم يوجد ما يناسب السؤال).
    val system = LionOne.systemFor(systemPrompt, task) + LionLearn.contextFor(message)
    val errors = mutableListOf<String>()

    /** محاولة مسار واحد من مسارات Lion-1 نفسه. يعيد الرد أو null عند الفشل (ويسجّل السبب). */
    fun attempt(route: LionOne.Route): String? {
        if (route.key == "engine" && !LionEngine.ensureReady()) {
            errors += "المحرّك المدمج: ${LionEngine.lastError.ifBlank { LionEngine.phaseLabel(LionEngine.phase) }}"
            return null
        }
        if (route.url.startsWith("http://") && !route.key.startsWith("engine") && !LionOne.reachable(route.url)) {
            LionOne.markBad(route)
            errors += "${route.label}: غير متاح"
            return null
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val reply = callOpenAiCompatible(
                route.url, route.token, route.model,
                if (route.key == "engine" || route.key == "local") LionOne.fitHistory(history, system, message) else history,
                message,
                extraHeaders = if (route.token.isBlank()) emptyMap() else mapOf("X-Api-Key" to route.token),
                maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = system,
                temperature = task.temp
            )
            if (!isErrorReply(reply)) {
                LionOne.markGood(route)
                runCatching { LionOne.experience().recordRoute(route.key, true, System.currentTimeMillis() - startedAt) }
                recordSuccess(LionOne.ID, "lion-1 · ${route.label}")
                reply
            } else {
                runCatching { LionOne.experience().recordRoute(route.key, false, System.currentTimeMillis() - startedAt) }
                LionOne.markBad(route)
                errors += "${route.label}: ${reply.take(80)}"
                null
            }
        } catch (e: Exception) {
            LionOne.markBad(route)
            errors += "${route.label}: ${e.message.orEmpty().take(80)}"
            null
        }
    }

    // الترتيب: ١) بوابة بمفتاح ← ٢) «العقل»: مزوداتك ومفاتيحك المضبوطة في التطبيق (Gemini/Groq/…/جلسات الويب)
    // ← ٣) المحرّك/الخادم المحلي (للعمل دون إنترنت). بلا هذا الدور كان Lion-1 يتوقف تماماً
    // حين لا يكون المحرّك مثبّتاً ولا للبوابة مفتاح، رغم وجود مزودات تعمل في بقية التطبيق.
    val all = LionOne.routes(apiKey)
    val remote = if (LionOne.offlineFirst) emptyList() else
        all.filter { it.key.startsWith("gw:") && it.token.isNotBlank() }
    val local = all.filterNot { it in remote }
    for (r in remote) attempt(r)?.let { return it }
    if (!LionOne.offlineFirst) {
        callLionBrain(task, message, history, image, system, errors)?.let { return it }
    }
    for (r in local) attempt(r)?.let { return it }
    if (LionOne.offlineFirst) {
        callLionBrain(task, message, history, image, system, errors)?.let { return it }
    }
    return "⚠️ Lion-1: تعذّرت كل المسارات.\n" + errors.joinToString("\n") + "\n" +
        LionHint.nextStep()
}

/**
 * «عقل» Lion-1: يفوّض إلى مزوداتك الفعلية بخطة العقل المركزي نفسها (SmartRouter.plan)،
 * فيستفيد من كل مفتاح وجلسة ويب مفعّلة، ويتخطى المبرَّد/الفاشل، ويحافظ على سياق المحادثة.
 * يعيد الرد أو null (والأسباب تُضاف إلى [errors]).
 */
internal fun LlmClient.callLionBrain(
    task: LionOne.Task,
    message: String,
    history: List<ChatTurn>,
    image: ImageAttachment?,
    system: String,
    errors: MutableList<String>
): String? {
    val prefs = runCatching { ContextHolder.peek()?.let { SecurePrefs.get(it) } }.getOrNull() ?: return null
    val kind = when {
        image != null -> TaskKind.VISION
        task == LionOne.Task.CODE -> TaskKind.CODE
        task == LionOne.Task.WRITE -> TaskKind.WRITING
        task == LionOne.Task.REASON -> TaskKind.REASONING
        else -> TaskKind.GENERAL
    }
    val plan = runCatching { SmartRouter.plan(prefs, kind) }.getOrDefault(emptyList())
        .filter { it.provider != LionOne.ID && it.provider != "local_proxy" }
    if (plan.isEmpty()) {
        errors += "المزودات: لا يوجد مزود مفعّل بمفتاح أو جلسة ويب"
        return null
    }
    for (route in plan.take(5)) {
        val startedAt = System.currentTimeMillis()
        val reply = try {
            ask(
                message = message,
                apiKey = prefs.getString("api_key_${route.provider}", "").orEmpty(),
                provider = route.provider,
                history = history,
                modelOverride = route.model,
                accountId = prefs.getString("cf_account_id", "").orEmpty(),
                image = image,
                systemPrompt = system,
                via = route.via
            )
        } catch (e: Exception) {
            "⚠️ ${e.message.orEmpty()}"
        }
        if (!isErrorReply(reply)) {
            QuotaGuard.clear(route.provider)
            runCatching { LionOne.experience().recordRoute("brain:" + route.provider, true, System.currentTimeMillis() - startedAt) }
            recordSuccess(LionOne.ID, "lion-1 · ${route.provider}")
            return reply
        }
        QuotaGuard.mark(route.provider, QuotaGuard.classify(reply))
        runCatching { LionOne.experience().recordRoute("brain:" + route.provider, false, System.currentTimeMillis() - startedAt) }
        errors += "${route.provider}: ${reply.take(80)}"
    }
    return null
}

/**
 * مسار الأدوات الأصلية (function calling) لمزود lion-1.
 *
 * المحرّك المدمج يدعم `tools` عبر llama-server مباشرة، فالوكيل يستدعي أدوات التطبيق
 * (ملفات/شل/ويب/GitHub…) بدل انتظار بروتوكول نصي. إن لم يدعم المسار الحالي الأدوات
 * نعود لرد نصي عادي، فيكمل ToolAgent ببروتوكول [[TOOL]] كما هو مصمَّم.
 */
internal fun LlmClient.callLionOneTools(
    message: String,
    apiKey: String,
    model: String,
    history: List<ChatTurn>,
    systemPrompt: String,
    tools: JSONArray
): LlmClient.ToolReply {
    val task = LionOne.task(model, message)
    val system = LionOne.systemFor(systemPrompt, task) + LionLearn.contextFor(message)
    // مزودات البوابة القوية تتبع بروتوكول [[TOOL]] النصي بثبات أكبر بكثير من نموذج 1.5B المحلي:
    // فإن وُجدت بوابة بمفتاح نذهب للرد النصي (الذي يقدّم البوابة)، والمحلي يبقى احتياطاً.
    if (LionOne.remoteFirstActive(apiKey)) {
        val remoteText = callLionOne(message, apiKey, model, history, null, systemPrompt)
        return LlmClient.ToolReply(remoteText, emptyList())
    }
    for (route in LionOne.routes(apiKey)) {
        // البوابة لا تعيد tool_calls موثوقة في كل مزوداتها؛ الأدوات تُطلب من المسارات المحلية.
        if (route.key.startsWith("gw:")) continue
        if (route.key == "engine" && !LionEngine.ensureReady()) {
            continue
        }
        try {
            val turn = callOpenAiTools(
                route.url, route.token, route.model, history, message, system, tools,
                extraHeaders = if (route.token.isBlank()) emptyMap() else mapOf("X-Api-Key" to route.token)
            )
            if (turn.calls.isNotEmpty() || turn.text.isNotBlank()) {
                LionOne.markGood(route)
                recordSuccess(LionOne.ID, "lion-1 · ${route.label} · tools")
                return turn
            }
        } catch (e: Exception) {
            LionOne.markBad(route)
        }
    }
    // لا مسار دعم الأدوات: نعود للرد النصي (فيحلّله ToolAgent ببروتوكول النص).
    val text = callLionOne(message, apiKey, model, history, null, systemPrompt)
    return LlmClient.ToolReply(text, emptyList())
}

/** رسالة إرشاد واحدة صريحة حسب ما ينقص فعلاً — تُعرض للمستخدم بدل أخطاء الشبكة الغامضة. */
internal object LionHint {
    fun nextStep(): String {
        val phase = LionEngine.phase
        val tail = when (phase) {
            LionEngine.Phase.LINUX_MISSING ->
                "فعّل بيئة Linux من «البيئة المحلية» ثم ثبّت المحرّك من بطاقة Lion-1."
            LionEngine.Phase.SERVER_MISSING ->
                "ثبّت محرّك llama.cpp من بطاقة Lion-1 (زر «تثبيت المحرّك»)."
            LionEngine.Phase.MODEL_MISSING ->
                "نزّل النموذج (GGUF) من بطاقة Lion-1 (زر «تنزيل النموذج»)."
            LionEngine.Phase.READY ->
                "المحرّك جاهز لكنه لم يبدأ: اضغط «تشغيل المحرّك» أو فعّل التشغيل التلقائي."
            LionEngine.Phase.STARTING ->
                "المحرّك يبدأ الآن؛ أعد الإرسال بعد لحظات."
            LionEngine.Phase.RUNNING ->
                "المحرّك يعمل لكنه لم يجب: راجع سجل المحرّك في بطاقة Lion-1."
            LionEngine.Phase.ERROR ->
                "راجع سجل المحرّك في بطاقة Lion-1 (الذاكرة المتاحة قد لا تكفي النموذج المختار)."
        }
        return "👉 الأسرع: $tail\n" +
            "بديل فوري: شغّل نموذجك على خادمك وضع رابطه في «البوابة/Base URL» داخل إعدادات lion-1، " +
            "أو فعّل بوابة الخادم الخارجي كمسار احتياطي."
    }
}

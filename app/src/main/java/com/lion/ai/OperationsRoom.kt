package com.lion.ai

import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * غرفة العمليات المركزية: الخيار الثالث بعد «يدوي» و«تلقائي».
 *
 * عند تفعيلها من الإعدادات يصبح «التلقائي» مركز تفاعل للمهام المعقدة فقط
 * (بناء تطبيق، تعديلات هامة، هندسة، مهام متعددة الخطوات). المهام العادية تبقى على
 * التلقائي المعتاد (العقل المركزي يختار المزود الأفضل الناجح).
 *
 * سير الاجتماع:
 * 1) مقترحات: كل مشارك يقدّم حله. مزودات API بالتوازي، ومزودات الويب (Mistral وGitHub Copilot
 *    وArena وDeepSeek…) في مسار متسلسل منفصل لأنها تشترك في متصفح واحد لا يقبل إلا طلباً واحداً.
 * 2) مراجعة: مشارك آخر ينقد المقترحات (اختياري، يُتجاوز إن ضاق الوقت أو فشل).
 * 3) قرار: رئيس الاجتماع يقيّم ويصدر حلاً واحداً معتمداً.
 * 4) لا يظهر اسم أي مزود تحت النتيجة؛ التوقيع «Lion AI» فقط.
 * 5) إن فشلت الغرفة كلها يعود التطبيق إلى التلقائي العادي، فلا يضيع الطلب.
 *
 * الاستمرارية بين الجولات تبقى على AgentSession؛ ولا نشغّل وكلاء أدوات متعددين على مساحة عمل واحدة.
 */
data class OpsCandidate(val route: Route, val reply: String, val ok: Boolean, val ms: Long)

data class OpsResult(
    val answer: String,
    val candidates: List<OpsCandidate>,
    val synthesizedBy: String?,
    /** مسار الرئيس الذي أصدر القرار (أو صاحب الإجابة الوحيدة)؛ للتسجيل الداخلي فقط. */
    val lead: Route? = null
) {
    val ok: Boolean get() = answer.isNotBlank() && !OperationsRoom.isFailure(answer)
}

object OperationsRoom {
    const val PREF_ENABLED = "ops_room_enabled"     // الخيار الثالث (افتراضياً مطفأ)
    const val PREF_CORE = "ops_room_core"           // المشاركون مفصولون بفاصلة (API + ويب)
    const val BRAND = "Lion AI"
    const val MAX_API = 3
    const val MAX_WEB = 2
    private const val API_DRAFT_MS = 120_000
    private const val WEB_DRAFT_MS = 100_000
    private const val PHASE_CAP_MS = 220_000L
    private const val CRITIQUE_MS = 60_000
    private const val DECISION_MS = 120_000

    /** مزودات الويب المتقدمة التي تُستخدم تلقائياً إن لم يحدد المستخدم مشاركين (بشرط نجاح جلستها سابقاً). */
    val PREFERRED_WEB = listOf("mistral_web", "github_copilot_web", "arena", "deepseek_web")

    private val BUILD_VERB = Regex(
        "(ابن|ابني|أنشئ|انشئ|صمم|صمّم|برمج|طور|طوّر|نفذ|نفّذ|عدل|عدّل|حدث|حدّث|أضف|اضف|أصلح|اصلح|حول|حوّل|هيكل|ارفع|" +
            "build|create|implement|develop|refactor|migrate|redesign|rewrite|design|fix|add)"
    )
    private val BUILD_TARGET = Regex(
        "(تطبيق|موقع|مشروع|نظام|لعبة|متصفح|واجهة|خادم|سيرفر|قاعدة بيانات|لوحة تحكم|منصة|إضافة|اضافة|ميزة|ميزات|معمار|بنية|" +
            "\\bapi\\b|backend|frontend|\\bapp\\b|website|system|project|architecture|database|dashboard|module|feature|workflow)"
    )
    private val MULTI_STEP = Regex("(^|\\n)\\s*(\\d+[.)\\-]|[-•*])\\s+")

    /** تعمل فقط في «تلقائي» (وليس يدوياً ولا موقعاً مختاراً صراحة) وبعد تفعيلها من الإعدادات. */
    fun enabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, false) &&
            !RoutePolicy.isManual(prefs) &&
            !WebChatRuntime.handles(RoutePolicy.selectedProvider(prefs))

    /** تصنيف «معقد/هام» مقابل «قياسي»: المعقد وحده يدخل الغرفة. */
    fun isComplex(prompt: String, kind: TaskKind, chatMode: ChatMode, agent: Boolean): Boolean {
        val t = prompt.trim()
        if (t.length < 25) return false
        if (kind == TaskKind.FAST || kind == TaskKind.IMAGE_EDIT || kind == TaskKind.VISION) return false
        if (SmartRouter.isSmallTalk(t) || SmartRouter.isQuickQuestion(t)) return false
        val low = t.lowercase()
        val verb = BUILD_VERB.containsMatchIn(low)
        val target = BUILD_TARGET.containsMatchIn(low)
        val steps = MULTI_STEP.findAll(t).count()
        return (verb && target && t.length >= 40) ||
            steps >= 3 ||
            (kind != TaskKind.LONG_DOC && t.length >= 1500) ||
            (agent && kind == TaskKind.DEV && t.length >= 200) ||
            (chatMode == ChatMode.CODE && kind == TaskKind.DEV && verb && t.length >= 120)
    }

    fun active(prefs: SharedPreferences, prompt: String, kind: TaskKind, chatMode: ChatMode, agent: Boolean): Boolean =
        enabled(prefs) && isComplex(prompt, kind, chatMode, agent)

    private fun chosenIds(prefs: SharedPreferences): List<String> =
        (prefs.getString(PREF_CORE, "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** مشاركو API: ما اختاره المستخدم، وإلا أفضل المتاح للتطوير. */
    fun apiMembers(prefs: SharedPreferences): List<String> {
        val chosen = chosenIds(prefs).filter { !WebChatRuntime.handles(it) && SmartRouter.available(prefs, it) }
        if (chosen.isNotEmpty()) return chosen.take(MAX_API)
        return SmartRouter.candidates(TaskKind.DEV).map { it.provider }
            .filter { !WebChatRuntime.handles(it) && SmartRouter.available(prefs, it) }
            .distinct().take(MAX_API)
    }

    /** مشاركو الويب: ما اختاره المستخدم، وإلا المتقدمة المذكورة التي نجحت جلساتها. */
    fun webMembers(prefs: SharedPreferences): List<String> {
        val chosen = chosenIds(prefs).filter { WebChatRuntime.isWeb(it) && SmartRouter.available(prefs, it) }
        if (chosen.isNotEmpty()) return chosen.take(MAX_WEB)
        return PREFERRED_WEB.filter {
            WebChatRuntime.isWeb(it) && SmartRouter.available(prefs, it) &&
                prefs.getBoolean("web_session_verified_$it", false)
        }.take(MAX_WEB)
    }

    private fun routeOf(prefs: SharedPreferences, id: String): Route =
        if (WebChatRuntime.handles(id)) Route(id, WebChatRuntime.model(prefs, id), Channel.WEB)
        else Route(id, prefs.getString("model_$id", "")?.takeIf { it.isNotBlank() } ?: LlmClient.defaultModel(id))

    /** ردّ فاشل: فارغ أو يبدأ بعلامة التحذير التي تعيدها LlmClient عند الأخطاء. */
    fun isFailure(reply: String): Boolean {
        val t = reply.trim()
        return t.isEmpty() || t.startsWith("⚠️") || t.startsWith("❌") || LlmClient.isErrorReply(t)
    }

    /**
     * اجتماع كامل لمهمة معقدة بلا أدوات: مقترحات ثم مراجعة ثم قرار واحد.
     * @param keyOf مفتاح المزود كما يستخدمه التطبيق عند استدعاء LlmClient.ask.
     * @param onLog سجل العمل الحي (بلا أسماء مزودين).
     */
    fun deliberate(
        prefs: SharedPreferences,
        message: String,
        history: List<ChatTurn>,
        systemPrompt: String,
        chatId: String,
        chatMode: ChatMode,
        keyOf: (String) -> String,
        onLog: (String) -> Unit = {}
    ): OpsResult {
        val apiRoutes = apiMembers(prefs).map { routeOf(prefs, it) }
        val webRoutes = webMembers(prefs).map { routeOf(prefs, it) }
        if (apiRoutes.isEmpty() && webRoutes.isEmpty()) {
            return OpsResult("⚠️ لا يوجد مشاركون متاحون لغرفة العمليات الآن.", emptyList(), null)
        }
        val rescue = CentralBrain.rescueRoutes(prefs, TaskKind.DEV, apiRoutes + webRoutes, image = false, limit = 4)
            .filterNot { WebChatRuntime.handles(it.provider) }.toMutableList()
        val candidates = mutableListOf<OpsCandidate>()
        val apiPool = Executors.newFixedThreadPool(MAX_API)
        val webPool = Executors.newSingleThreadExecutor() // مسار متسلسل: متصفح واحد لا يقبل طلبين معاً.
        val deadline = System.currentTimeMillis() + PHASE_CAP_MS
        try {
            onLog("🧠 غرفة العمليات: بدء التشاور (${apiRoutes.size + webRoutes.size} مشاركين)")
            val futures = mutableListOf<Pair<Route, Future<OpsCandidate>>>()
            apiRoutes.forEach { r ->
                futures += r to apiPool.submit(Callable {
                    ask(r, message, history, systemPrompt, chatId, chatMode, keyOf, API_DRAFT_MS)
                })
            }
            webRoutes.forEach { r ->
                futures += r to webPool.submit(Callable {
                    ask(r, message, history, systemPrompt, chatId, chatMode, keyOf, WEB_DRAFT_MS)
                })
            }
            for ((route, f) in futures) {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
                val c = runCatching { f.get(left, TimeUnit.MILLISECONDS) }
                    .getOrElse { f.cancel(true); OpsCandidate(route, "⚠️ انتهت المهلة", false, left) }
                candidates += c
                SmartRouter.record(c.route, c.ok, c.ms)
                onLog(if (c.ok) "✅ وصل مقترح (${c.ms / 1000}s)" else "❌ تعذّر مشارك، جارٍ التعويض")
            }
            // التعويض: لا اجتماع بلا مقترح واحد على الأقل.
            while (candidates.none { it.ok } && rescue.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val next = rescue.removeAt(0)
                onLog("🔁 مشارك بديل")
                val c = ask(next, message, history, systemPrompt, chatId, chatMode, keyOf, API_DRAFT_MS)
                candidates += c
                SmartRouter.record(c.route, c.ok, c.ms)
            }
        } finally {
            apiPool.shutdownNow()
            webPool.shutdownNow()
        }
        return decide(prefs, message, candidates, chatId, chatMode, keyOf, onLog)
    }

    /** المراجعة ثم القرار على المقترحات الواصلة. */
    internal fun decide(
        prefs: SharedPreferences,
        message: String,
        candidates: List<OpsCandidate>,
        chatId: String,
        chatMode: ChatMode,
        keyOf: (String) -> String,
        onLog: (String) -> Unit
    ): OpsResult {
        val good = candidates.filter { it.ok }
        if (good.isEmpty()) {
            return OpsResult("⚠️ تعذّر الحصول على إجابة من الغرفة الآن.", candidates, null)
        }
        if (good.size == 1) return OpsResult(good.first().reply, candidates, null, good.first().route)

        // الرئيس: أوثق مشارك API (ثابت ولا يزاحم المتصفح)، وإلا أوثق ناجح، وإلا الأول.
        val chair = good.firstOrNull {
            !WebChatRuntime.handles(it.route.provider) && SmartRouter.isReliableModel(it.route.provider, it.route.model)
        } ?: good.firstOrNull { !WebChatRuntime.handles(it.route.provider) }
            ?: good.firstOrNull { SmartRouter.isReliableModel(it.route.provider, it.route.model) }
            ?: good.first()

        // المراجعة: مشارك آخر غير الرئيس ينقد المقترحات (اختيارية).
        val critic = good.firstOrNull { it.route.provider != chair.route.provider }
        var critique = ""
        if (critic != null) {
            onLog("🔎 مراجعة المقترحات")
            val c = ask(critic.route, critiquePrompt(message, good), emptyList(), CRITIC_SYSTEM, chatId, chatMode, keyOf, CRITIQUE_MS)
            SmartRouter.record(c.route, c.ok, c.ms)
            if (c.ok) critique = c.reply.take(3000)
        }

        onLog("🧩 إصدار القرار")
        val decision = ask(
            chair.route, decisionPrompt(message, good, critique), emptyList(), CHAIR_SYSTEM,
            chatId, chatMode, keyOf, DECISION_MS
        )
        SmartRouter.record(decision.route, decision.ok, decision.ms)
        return if (decision.ok) OpsResult(decision.reply, candidates, chair.route.provider, chair.route)
        else OpsResult(good.maxByOrNull { it.reply.length }!!.reply, candidates, null, chair.route)
    }

    /**
     * وضع الأدوات: لا نشغّل عدة وكلاء أدوات على مساحة العمل نفسها (تعارض ملفات). الغرفة تستشير
     * المشاركين بلا أدوات وتصدر خطة واحدة تُحقن في طلب المنفّذ الوحيد. تعيد نصاً فارغاً إن لم تنجح.
     */
    fun brief(
        prefs: SharedPreferences,
        message: String,
        history: List<ChatTurn>,
        systemPrompt: String,
        chatId: String,
        chatMode: ChatMode,
        keyOf: (String) -> String,
        onLog: (String) -> Unit = {}
    ): String {
        val consultPrompt = "أعطِ خطة تنفيذ مختصرة ودقيقة (خطوات، ملفات متأثرة، مخاطر) للطلب التالي " +
            "دون تنفيذ ودون كود طويل:\n" + message.take(6000)
        val res = deliberate(prefs, consultPrompt, history.takeLast(6), systemPrompt, chatId, chatMode, keyOf, onLog)
        return if (!res.ok) "" else
            "\n\n=== 🧠 خطة غرفة العمليات (استرشد بها ونفّذ بأدواتك) ===\n" + res.answer.take(6000)
    }

    private const val CHAIR_SYSTEM =
        "أنت رئيس غرفة عمليات في Lion AI. تقيّم المقترحات وتُصدر قراراً واحداً معتمداً دقيقاً ومنسقاً. " +
            "لا تذكر المقترحات ولا المشاركين ولا الاجتماع ولا أسماء أي مزود أو نموذج."
    private const val CRITIC_SYSTEM =
        "أنت مراجع صارم في Lion AI. تحدّد الأخطاء والثغرات والمخاطر وما يجب أخذه من كل مقترح. بإيجاز ودقة."

    private fun ask(
        route: Route, message: String, history: List<ChatTurn>, systemPrompt: String,
        chatId: String, chatMode: ChatMode, keyOf: (String) -> String, timeoutMs: Int
    ): OpsCandidate {
        val t0 = System.currentTimeMillis()
        val reply = runCatching {
            LlmClient.ask(
                message = message,
                apiKey = if (WebChatRuntime.handles(route.provider)) "" else keyOf(route.provider),
                provider = route.provider,
                history = history,
                modelOverride = route.model,
                systemPrompt = systemPrompt,
                readTimeoutMs = timeoutMs,
                chatModeHint = chatMode,
                chatId = chatId
            )
        }.getOrElse { "⚠️ ${it.message ?: "خطأ"}" }
        return OpsCandidate(route, reply, !isFailure(reply), System.currentTimeMillis() - t0)
    }

    fun critiquePrompt(question: String, good: List<OpsCandidate>): String = buildString {
        append("سؤال المستخدم:\n").append(question.take(4000)).append("\n\n")
        good.forEachIndexed { i, c -> append("--- مقترح ${i + 1} ---\n").append(c.reply.take(5000)).append('\n') }
        append("\nانقد المقترحات: ما الصحيح؟ ما الخطأ أو الناقص؟ ما المخاطر؟ وما أفضل ما في كل مقترح؟ ")
        append("اكتب نقاطاً قصيرة دقيقة.")
    }

    fun decisionPrompt(question: String, good: List<OpsCandidate>, critique: String): String = buildString {
        append("سؤال المستخدم:\n").append(question.take(4000)).append("\n\n")
        append("المقترحات:\n")
        good.forEachIndexed { i, c -> append("--- مقترح ${i + 1} ---\n").append(c.reply.take(6000)).append('\n') }
        if (critique.isNotBlank()) append("\nملاحظات المراجعة:\n").append(critique).append('\n')
        append("\nقيّم المقترحات (الصحة والاكتمال والمخاطر)، اعتمد الأفضل وخذ من غيره ما يفيد، وصحّح التعارضات، ")
        append("ثم أصدر الحل النهائي المعتمد كاملاً. احتفظ بالأكواد كاملة. لا تدّعِ تنفيذاً لم يحدث. ")
        append("لا تذكر المقترحات ولا المراجعة ولا الاجتماع ولا أسماء المزودات أو النماذج؛ اكتب الجواب النهائي فقط.")
    }
}

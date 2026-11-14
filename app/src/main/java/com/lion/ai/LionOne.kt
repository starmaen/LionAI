package com.lion.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * Lion-1 («الأسد»): مزود افتراضي مدمج = هوية + توجيه مهام + تبديل تلقائي بين الخوادم.
 *
 * الهوية والمهمة تُحقنان في التطبيق نفسه، فتعمل مع أي خادم خلفي (نموذج مضبوط محلياً،
 * خادمك الداخلي، أو بوابة Cloudflare) دون اعتماد على إصدار البوابة.
 * المسارات بالترتيب: رابط المستخدم (الإعدادات) ← خادم 44045 المحلي ← خوادم البوابة المفعّلة.
 * آخر مسار نجح يُجرَّب أولاً، والمسار الفاشل يُتجاوز 60 ثانية، فلا تتكرر انتظارات الفشل.
 */
internal object LionOne {
    const val ID = "lion1"
    const val LOCAL_URL = "http://127.0.0.1:44045/v1/chat/completions"
    private const val COOLDOWN_MS = 60_000L

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
            "لا تذكر اسم أي مزود أو نموذج خلفي؛ إن سُئلت عن هويتك فأنت Lion-1 من Star Syria."

    /** أسماء النماذج الظاهرة في التطبيق (الاسم الخلفي يُحدَّد بالمسار لا بالمستخدم). */
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

    class Route(val key: String, val url: String, val token: String, val model: String, val label: String)

    private val badUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGood: String? = null

    fun routes(userKey: String): List<Route> {
        val out = mutableListOf<Route>()
        val custom = LlmClient.endpointOverrides[ID]?.trim().orEmpty()
        if (custom.isNotEmpty() && LlmClient.isSafeEndpoint(custom)) {
            out += Route("user", LlmClient.normalizeCustomUrl(custom), userKey, "lion-1", "مخصص")
        }
        out += Route("local", LOCAL_URL, "", "auto", "المحلي")
        val relays = WebGateway.relays()
        if (relays.isNotEmpty()) {
            relays.forEach { out += Route("gw:" + it.url, WebGateway.chatUrl(it.url), it.token, "auto", it.name) }
        } else {
            val cfg = WebGateway.current()
            val url = cfg.url.ifBlank { WebGateway.DEFAULT_URL }
            out += Route("gw:$url", WebGateway.chatUrl(url), cfg.token, "auto", "البوابة")
        }
        // آخر ناجح أولاً، ثم الباقي بالترتيب؛ المتجاوَزة مؤقتاً تؤخَّر إلى النهاية بدل حذفها.
        val now = System.currentTimeMillis()
        val (ok, cooling) = out.partition { (badUntil[it.key] ?: 0L) <= now }
        val first = ok.sortedBy { if (it.key == lastGood) 0 else 1 }
        return first + cooling
    }

    /** فحص سريع (0.5ث) لمنفذ محلي: المرفوض يُتجاوز فوراً دون إعادة محاولات الشبكة البطيئة. */
    fun reachable(url: String): Boolean = try {
        val u = java.net.URI(url)
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 500); true }
    } catch (e: Exception) { false }

    fun markGood(r: Route) { lastGood = r.key; badUntil.remove(r.key) }
    fun markBad(r: Route) { badUntil[r.key] = System.currentTimeMillis() + COOLDOWN_MS; if (lastGood == r.key) lastGood = null }
}

internal fun LlmClient.callLionOne(
    message: String,
    apiKey: String,
    model: String,
    history: List<ChatTurn>,
    image: ImageAttachment?,
    systemPrompt: String
): String {
    val task = LionOne.task(model, message)
    val system = LionOne.systemFor(systemPrompt, task)
    val errors = mutableListOf<String>()
    for (route in LionOne.routes(apiKey)) {
        if (route.url.startsWith("http://") && !LionOne.reachable(route.url)) {
            LionOne.markBad(route)
            errors += "${route.label}: غير متاح"
            continue
        }
        try {
            val reply = callOpenAiCompatible(
                route.url, route.token, route.model, history, message,
                extraHeaders = if (route.token.isBlank()) emptyMap() else mapOf("X-Api-Key" to route.token),
                maxTokens = LLM_OPENAI_MAX_TOKENS, image = image, systemPrompt = system,
                temperature = task.temp
            )
            if (!isErrorReply(reply)) {
                LionOne.markGood(route)
                recordSuccess(LionOne.ID, "lion-1 · ${route.label}")
                return reply
            }
            LionOne.markBad(route)
            errors += "${route.label}: ${reply.take(80)}"
        } catch (e: Exception) {
            LionOne.markBad(route)
            errors += "${route.label}: ${e.message.orEmpty().take(80)}"
        }
    }
    return "⚠️ Lion-1: تعذّرت كل المسارات.\n" + errors.joinToString("\n") +
        "\nشغّل خادمك المحلي، أو اضبط رابط البوابة والمفتاح من إعدادات الاتصال."
}

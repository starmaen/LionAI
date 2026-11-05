package com.lion.ai

import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * غرفة العمليات: العقل المركزي في وضع البرمجة التلقائي فقط.
 *
 * 1) يستقبل الطلب ويرسله بالتوازي إلى «المزودات الأساسية» التي اختارها المستخدم.
 * 2) أي مزود يتعطل أو ينتهي وقته يُستبدل فوراً ببديل من المزودات المتاحة (لا توقف).
 * 3) تعود الإجابات إلى الغرفة، فيدمجها منسّق واحد في إجابة نهائية واحدة.
 * 4) إن فشل الدمج تُعاد أفضل إجابة مرشحة بدل أي خطأ.
 * 5) لا يظهر اسم أي مزود في الإجابة ولا في السجل المعروض للمستخدم.
 *
 * الاستمرارية بين الجولات تبقى على AgentSession (continuationBlock + checkpoint).
 * الأدوات (ToolAgent) تُستدعى كما هي داخل كل مزود؛ الغرفة تنسّق فقط.
 */
data class OpsCandidate(val route: Route, val reply: String, val ok: Boolean, val ms: Long)

data class OpsResult(
    val answer: String,
    val candidates: List<OpsCandidate>,
    val synthesizedBy: String?
)

object OperationsRoom {
    const val PREF_AUTO = "ops_room_auto"          // الخيار التلقائي (افتراضياً مطفأ = يدوي)
    const val PREF_CORE = "ops_room_core"          // معرّفات المزودات الأساسية مفصولة بفاصلة
    private const val MAX_PARALLEL = 3
    private const val PER_PROVIDER_MS = 120_000L

    /** تعمل في وضع المطوّر عندما يكون الخيار الرئيسي «تلقائي» (وتتوقف تماماً في اليدوي). */
    fun enabled(prefs: SharedPreferences, mode: ChatMode): Boolean =
        mode == ChatMode.CODE && !RoutePolicy.isManual(prefs) && coreProviders(prefs).isNotEmpty()

    /** المزودات الأساسية التي اختارها المستخدم، وإن لم يختر فأفضل المتاح لمهام التطوير تلقائياً. */
    fun coreProviders(prefs: SharedPreferences): List<String> {
        val chosen = (prefs.getString(PREF_CORE, "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { SmartRouter.available(prefs, it) }
            .distinct()
        if (chosen.isNotEmpty()) return chosen
        return SmartRouter.candidates(TaskKind.DEV)
            .map { it.provider }
            .filter { SmartRouter.available(prefs, it) }
            .distinct()
            .take(MAX_PARALLEL)
    }

    private fun routeOf(prefs: SharedPreferences, id: String): Route {
        val model = prefs.getString("model_$id", "")?.takeIf { it.isNotBlank() } ?: LlmClient.defaultModel(id)
        return Route(id, model)
    }

    /** ردّ فاشل: فارغ أو يبدأ بعلامة التحذير التي تعيدها LlmClient عند الأخطاء. */
    fun isFailure(reply: String): Boolean {
        val t = reply.trim()
        return t.isEmpty() || t.startsWith("⚠️") || t.startsWith("❌") || LlmClient.isErrorReply(t)
    }

    /**
     * @param keyOf يعيد مفتاح المزود (نفس ما يستخدمه التطبيق اليوم عند استدعاء LlmClient.ask).
     * @param onLog سجل العمل الحي المعروض للمستخدم.
     */
    fun run(
        prefs: SharedPreferences,
        message: String,
        history: List<ChatTurn>,
        systemPrompt: String,
        chatId: String,
        keyOf: (String) -> String,
        onLog: (String) -> Unit = {}
    ): OpsResult {
        val core = coreProviders(prefs).map { routeOf(prefs, it) }
        val rescue = CentralBrain.rescueRoutes(prefs, TaskKind.DEV, core, image = false, limit = 4)
            .toMutableList()
        val pool = Executors.newFixedThreadPool(MAX_PARALLEL)
        val candidates = mutableListOf<OpsCandidate>()
        try {
            // التشغيل المتوازي للمزودات الأساسية.
            val wave = core.take(MAX_PARALLEL)
            onLog("🧠 غرفة العمليات: إرسال الطلب إلى ${wave.size} مزود")
            val futures = wave.map { route ->
                route to pool.submit(Callable { ask(route, message, history, systemPrompt, chatId, keyOf) })
            }
            futures.forEach { (route, f) ->
                val c = runCatching { f.get(PER_PROVIDER_MS, TimeUnit.MILLISECONDS) }
                    .getOrElse { f.cancel(true); OpsCandidate(route, "⚠️ انتهت المهلة", false, PER_PROVIDER_MS) }
                candidates += c
                SmartRouter.record(c.route, c.ok, c.ms)
                onLog(if (c.ok) "✅ وصلت إجابة (${c.ms / 1000}s)" else "❌ تعذّر مسار، جارٍ التعويض")
            }
            // الاستبدال الفوري: كل مزود فشل يحلّ محله بديل حتى نحصل على إجابة واحدة على الأقل.
            while (candidates.none { it.ok } && rescue.isNotEmpty()) {
                val next = rescue.removeAt(0)
                onLog("🔁 تشغيل مسار بديل")
                val c = ask(next, message, history, systemPrompt, chatId, keyOf)
                candidates += c
                SmartRouter.record(c.route, c.ok, c.ms)
            }
        } finally {
            pool.shutdownNow()
        }

        val good = candidates.filter { it.ok }
        if (good.isEmpty()) {
            return OpsResult("⚠️ تعذّر الحصول على إجابة الآن. أعد المحاولة بعد قليل.", candidates, null)
        }
        if (good.size == 1) return OpsResult(good.first().reply, candidates, null)

        // التنسيق: أوثق مزود ناجح يدمج الإجابات في إجابة واحدة.
        val judge = good.firstOrNull { SmartRouter.isReliableModel(it.route.provider, it.route.model) } ?: good.first()
        onLog("🧩 تنسيق ${good.size} إجابات في إجابة واحدة")
        val merged = ask(
            judge.route, mergePrompt(message, good), emptyList(),
            "أنت منسّق غرفة عمليات في Lion AI. أخرج إجابة نهائية واحدة دقيقة ومنسقة وواضحة.",
            chatId, keyOf
        )
        return if (merged.ok) OpsResult(merged.reply, candidates, judge.route.provider)
        else OpsResult(good.maxByOrNull { it.reply.length }!!.reply, candidates, null)
    }


    /**
     * وضع البرمجة بأدوات: لا نشغّل عدة وكلاء أدوات معاً على نفس مساحة العمل (تعارض ملفات).
     * الغرفة تستشير المزودات الأساسية بالتوازي (بلا أدوات)، تدمج خطة واحدة، وتُحقن في
     * طلب المنفّذ الوحيد الذي يملك الأدوات. يعيد نصاً فارغاً إن لم تتوفر استشارات.
     */
    fun brief(
        prefs: SharedPreferences,
        message: String,
        history: List<ChatTurn>,
        systemPrompt: String,
        chatId: String,
        keyOf: (String) -> String,
        onLog: (String) -> Unit = {}
    ): String {
        val consultPrompt = "أعطِ خطة تنفيذ مختصرة ودقيقة (خطوات، ملفات متأثرة، مخاطر) للطلب التالي " +
            "دون تنفيذ ودون كود طويل:\n" + message.take(6000)
        val res = run(prefs, consultPrompt, history.takeLast(6), systemPrompt, chatId, keyOf, onLog)
        val ok = res.candidates.any { it.ok }
        return if (!ok) "" else
            "\n\n=== 🧠 خطة غرفة العمليات (استرشد بها ونفّذ بأدواتك) ===\n" + res.answer.take(6000)
    }

    private fun ask(
        route: Route, message: String, history: List<ChatTurn>,
        systemPrompt: String, chatId: String, keyOf: (String) -> String
    ): OpsCandidate {
        val t0 = System.currentTimeMillis()
        val reply = runCatching {
            LlmClient.ask(
                message = message,
                apiKey = keyOf(route.provider),
                provider = route.provider,
                history = history,
                modelOverride = route.model,
                systemPrompt = systemPrompt,
                chatModeHint = ChatMode.CODE,
                chatId = chatId
            )
        }.getOrElse { "⚠️ ${it.message ?: "خطأ"}" }
        return OpsCandidate(route, reply, !isFailure(reply), System.currentTimeMillis() - t0)
    }

    fun mergePrompt(question: String, good: List<OpsCandidate>): String = buildString {
        append("سؤال المستخدم:\n").append(question.take(4000)).append("\n\n")
        append("إجابات المزودات:\n")
        good.forEachIndexed { i, c ->
            append("--- إجابة ${i + 1} ---\n").append(c.reply.take(6000)).append('\n')
        }
        append("\nادمج أفضل ما فيها في إجابة واحدة نهائية: صحّح التعارضات، احذف التكرار، ")
        append("لا تدّعِ تنفيذاً لم يحدث، لا تذكر أسماء المزودات ولا أنك دمجت إجابات.")
    }
}

package com.lion.ai

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * العقل المركزي كمدير ومنسّق:
 * - يحدد سياسة كل مهمة حسب الوضع (عام/تعلّم/برمجة) ونوع السؤال.
 * - يفرض المصادر للأسئلة العامة والمعرفية عندما يكون ذلك مفيداً.
 * - يوجّه مزوداً آخر للتحقق من الإجابة عند الحاجة (بدل عرض أي رد بلا تدقيق).
 * - يوسّع قائمة البدائل حتى لا ينتهي الحوار بـ «المزود لا يعمل».
 * - يمنع تكرار السؤال أو إعادة العمل عبر كتلة استئناف واضحة.
 */
enum class MissionFocus(val label: String) {
    FAST("⚡ سريع"),
    RELIABLE("🛡️ موثوق"),
    TEACHING("🎓 تعليمي"),
    ENGINEERING("🛠️ هندسي")
}

data class MissionPolicy(
    val focus: MissionFocus,
    val requireSources: Boolean,
    val verifyAnswers: Boolean,
    val expandFallbacks: Boolean
)

data class AnswerAttempt(
    val provider: String,
    val ok: Boolean,
    val note: String
)

data class Verdict(val ok: Boolean, val reason: String, val corrected: String)

object CentralBrain {
    private val NON_CHAT_MODEL = Regex(
        "(whisper|embedding|\\bembed\\b|\\btts\\b|text-to-speech|stable-diffusion|sdxl|" +
            "rerank|moderation|\\bguard\\b|content-safety)",
        RegexOption.IGNORE_CASE
    )

    private val ASK_TAG = Regex("\\[\\[ASK\\]\\](.*?)\\[\\[/ASK\\]\\]", RegexOption.DOT_MATCHES_ALL)
    private val QUESTION_END = Regex("[?؟]\\s*$")

    /** سياسة المهمة: مصدر واحد للحقيقة يتبعه باقي التطبيق. */
    fun policy(kind: TaskKind, mode: ChatMode, central: Boolean): MissionPolicy {
        if (!central) return MissionPolicy(MissionFocus.FAST, false, false, false)
        if (kind == TaskKind.FAST) return MissionPolicy(MissionFocus.FAST, false, false, false)
        return when (mode) {
            ChatMode.CODE -> MissionPolicy(
                focus = MissionFocus.ENGINEERING,
                requireSources = false,
                verifyAnswers = false,
                expandFallbacks = true
            )
            ChatMode.LEARN -> MissionPolicy(
                focus = MissionFocus.TEACHING,
                requireSources = kind == TaskKind.GENERAL || kind == TaskKind.FACTUAL,
                verifyAnswers = kind == TaskKind.FACTUAL,
                expandFallbacks = true
            )
            ChatMode.GENERAL -> when (kind) {
                TaskKind.GENERAL, TaskKind.FACTUAL -> MissionPolicy(
                    focus = MissionFocus.RELIABLE,
                    requireSources = true,
                    verifyAnswers = true,
                    expandFallbacks = true
                )
                TaskKind.VISION, TaskKind.IMAGE_EDIT -> MissionPolicy(
                    focus = MissionFocus.RELIABLE,
                    requireSources = false,
                    verifyAnswers = false,
                    expandFallbacks = true
                )
                else -> MissionPolicy(
                    focus = MissionFocus.RELIABLE,
                    requireSources = false,
                    verifyAnswers = true,
                    expandFallbacks = true
                )
            }
        }
    }

    /** هل نبحث في الويب؟ العام/المعرفي في الوضع المركزي يحصل على مصادر دائماً. */
    fun shouldSearch(
        prefs: SharedPreferences,
        kind: TaskKind,
        mode: ChatMode,
        prompt: String,
        central: Boolean
    ): Boolean {
        if (!WebGrounding.enabled(prefs)) return false
        if (WebGrounding.shouldSearch(kind, prompt)) return true
        if (!central) return false
        return policy(kind, mode, true).requireSources
    }

    /**
     * التحقق مطلوب فقط إذا كانت الإجابة من مزود غير موثوق، أو سؤالاً معرفياً بلا مصادر.
     * لا نضيّع طلباً إضافياً للتحقق من إجابة موثوقة مدعومة بالمصادر.
     */
    fun needsVerification(
        route: Route,
        kind: TaskKind,
        policy: MissionPolicy,
        judgeAvailable: Boolean,
        grounded: Boolean
    ): Boolean {
        if (!policy.verifyAnswers || !judgeAvailable) return false
        if (kind == TaskKind.FAST || kind == TaskKind.CODE || kind == TaskKind.DEV ||
            kind == TaskKind.IMAGE_EDIT || kind == TaskKind.VISION || kind == TaskKind.WRITING
        ) {
            return false
        }
        val reliable = SmartRouter.isReliableModel(route.provider, route.model)
        if (reliable && (grounded || !SmartRouter.isStrict(kind))) return false
        return true
    }

    /** أفضل مدقق متاح: مزود آخر موثوق، وإلا أي مزود آخر متاح. */
    fun judgeRoute(prefs: SharedPreferences, kind: TaskKind, answer: Route, planned: List<Route>): Route? {
        val others = planned.filter { it.provider != answer.provider }
        others.firstOrNull { SmartRouter.isReliableModel(it.provider, it.model) }?.let { return it }
        others.firstOrNull()?.let { return it }
        val extras = SmartRouter.candidates(kind)
            .filter { SmartRouter.available(prefs, it.provider) && it.provider != answer.provider }
        return extras.firstOrNull { SmartRouter.isReliableModel(it.provider, it.model) }
            ?: extras.firstOrNull()
    }

    /**
     * بدائل إضافية من كل المزودات المتاحة، كل مزود بموديله المناسب لقدرته.
     * هذه هي شبكة الأمان التي تمنع الوصول إلى «المزود لا يعمل» بلا إجابة.
     */
    fun rescueRoutes(
        prefs: SharedPreferences,
        kind: TaskKind,
        planned: List<Route>,
        image: Boolean,
        limit: Int = 5
    ): List<Route> {
        val plannedProviders = planned.map { it.provider }.toSet()
        val preferred = SmartRouter.candidates(kind).associateBy { it.provider }
        return PROVIDERS
            .filter { it.group == ProviderGroup.API }
            .filter { SmartRouter.available(prefs, it.id) && it.id !in plannedProviders }
            .map { info ->
                val model = preferred[info.id]?.model?.takeIf { it.isNotBlank() }
                    ?: prefs.getString("model_${info.id}", "")?.takeIf { it.isNotBlank() }
                    ?: info.defaultModel
                Route(info.id, model)
            }
            .filter { route ->
                if (image) {
                    LlmClient.supportsVision(route.provider, route.model)
                } else {
                    route.model.isBlank() || !NON_CHAT_MODEL.containsMatchIn(route.model)
                }
            }
            .sortedWith(
                compareByDescending<Route> { SmartRouter.isReliableModel(it.provider, it.model) }
                    .thenByDescending { providerInfo(it.provider).keyless }
            )
            .distinctBy { it.provider }
            .take(limit.coerceIn(1, 8))
    }

    fun verificationPrompt(
        question: String,
        answer: String,
        sources: List<WebSource>,
        mode: ChatMode
    ): String {
        val sourceBlock = if (sources.isEmpty()) {
            "لا توجد مصادر ويب لهذا الطلب."
        } else {
            WebGrounding.contextBlock(sources)
        }
        val modeRule = when (mode) {
            ChatMode.CODE -> "تحقق من عدم ادّعاء تنفيذ خطوات أو تعديلات لم تحدث فعلاً."
            ChatMode.LEARN -> "تحقق أن الشرح متدرج وواضح وتعليمي، وأن المعلومات صحيحة ومطابقة للسؤال."
            else -> "تحقق من صحة المعلومات ووضوحها والتزامها بالسؤال، وعدم اختراع تفاصيل أو مصادر."
        }
        return buildString {
            append("أنت المدقق المركزي في Lion AI. مهمتك الحكم على إجابة مرشحة، لا إعادة كتابة السؤال.\n")
            append(modeRule).append('\n')
            append("إن كانت الإجابة سليمة فأعد verdict=ok. إن كانت خاطئة أو ناقصة فأعد verdict=retry.\n")
            append("عند retry قدّم في حقل corrected إجابة نهائية مصححة كاملة إن أمكن، وإلا اتركه فارغاً.\n\n")
            append("سؤال المستخدم:\n").append(question.take(3000)).append('\n')
            append("\nالمصادر المتاحة:\n").append(sourceBlock.take(5000)).append('\n')
            append("\nالإجابة المرشحة:\n").append(answer.take(6000)).append('\n')
            append("\nأعد JSON فقط بدون Markdown:\n")
            append("{\"verdict\":\"ok\" أو \"retry\",\"reason\":\"سبب مختصر\",\"corrected\":\"إجابة مصححة أو فارغة\"}")
        }
    }

    fun parseVerdict(raw: String): Verdict {
        val text = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val obj = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            if (obj != null) {
                val verdict = obj.optString("verdict", "ok").trim().lowercase()
                val reason = obj.optString("reason", "").trim()
                val corrected = obj.optString("corrected", "").trim()
                return Verdict(verdict != "retry", reason, corrected)
            }
        }
        val retry = Regex("\"?verdict\"?\\s*[:=]\\s*\"?retry", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        return Verdict(!retry, if (retry) text.take(240) else "", "")
    }

    /** كتلة تُضاف للطلب التالي كي يكمل بدل تكرار السؤال أو إعادة العمل. */
    fun continuationBlock(attempts: List<AnswerAttempt>): String {
        if (attempts.isEmpty()) return ""
        return buildString {
            append("\n\n=== 🧠 توجيه المنسّق المركزي (متابعة لا سؤال جديد) ===\n")
            append("هذه ليست مهمة جديدة. المستخدم سأل مرة واحدة، والمحاولات التالية لم تُعتمد بعد.\n")
            append("لا تكرر سؤال المستخدم، ولا تعد الخطوات الناجحة، ولا تطلب نفس المعلومة مرة أخرى.\n")
            append("أكمل من آخر نقطة، وصحّح الأخطاء المذكورة فقط، وقدّم إجابة نهائية واحدة.\n")
            attempts.takeLast(4).forEach { attempt ->
                append("• ").append(providerInfo(attempt.provider).shortName).append(": ")
                append(if (attempt.ok) "✅ " else "❌ ").append(attempt.note.take(260)).append('\n')
            }
            append("ابدأ بالإجابة النهائية مباشرة دون مقدمة طويلة.")
        }
    }

    /** استخراج سؤال من رد النموذج (وسم ASK أو آخر سطر ينتهي بعلامة استفهام). */
    fun questionOf(reply: String): String? {
        ASK_TAG.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val lastLine = reply.trim().takeLast(400).lines().lastOrNull { it.trim().isNotEmpty() }
            ?.trim().orEmpty()
        return if (lastLine.length in 8..300 && QUESTION_END.containsMatchIn(lastLine)) lastLine else null
    }

    private fun questionSignature(text: String): String =
        TextNorm.forMatch(text)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)

    /**
     * هل الرد يعيد طرح سؤال سبق طرحه؟ يسجّل السؤال الجديد ثم يقارن بالأسئلة السابقة
     * وبالسؤال المعلّق من الجلسة.
     */
    fun repeatsQuestion(reply: String, asked: MutableSet<String>, pending: String?): Boolean {
        val question = questionOf(reply) ?: return false
        val signature = questionSignature(question)
        if (signature.isBlank()) return false
        val pendingSignature = pending?.let { questionSignature(it) }.orEmpty()
        val duplicate = (pendingSignature.isNotBlank() && pendingSignature == signature) ||
            asked.any { previous -> similarQuestion(previous, signature) }
        asked.add(signature)
        return duplicate
    }

    private fun similarQuestion(first: String, second: String): Boolean {
        if (first == second) return true
        if (first.length > 20 && second.length > 20 &&
            (first.contains(second) || second.contains(first))
        ) {
            return true
        }
        val a = first.split(' ').filter { it.length > 2 }.toSet()
        val b = second.split(' ').filter { it.length > 2 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return (a intersect b).size * 10 >= minOf(a.size, b.size) * 6
    }
}

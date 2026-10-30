package com.lion.ai

import android.content.SharedPreferences
import java.util.Locale

/** أنواع المهام التي يميّزها العقل المركزي. */
enum class TaskKind(val label: String) {
    FAST("سؤال سريع"),
    GENERAL("سؤال عام"),
    FACTUAL("سؤال معرفي"),
    CODE("برمجة"),
    DEV("مهمة تطوير"),
    REASONING("استدلال وحساب"),
    WRITING("كتابة وترجمة"),
    VISION("تحليل صورة"),
    IMAGE_EDIT("تعديل صورة"),
    LONG_DOC("ملف طويل")
}

data class Route(val provider: String, val model: String)

/** وضع جودة الإجابة: يتحكم في قبول المزودات الضعيفة وفي عدد المحاولات. */
enum class AnswerMode(val id: String, val label: String, val hint: String) {
    PRECISE(
        "precise",
        "🎯 دقيق (موصى به)",
        "يمنع النماذج الضعيفة في الأسئلة المعرفية والعامة، ويستعمل أقوى المزودات المتاحة فقط."
    ),
    BALANCED(
        "balanced",
        "⚖️ متوازن",
        "يبدأ بأقوى المزودات ثم يسمح ببديل عند فشلها؛ توازن بين الدقة والسرعة."
    ),
    FAST(
        "fast",
        "⚡ سريع",
        "محاولتان فقط وأولوية للسرعة. قد تقل الدقة في الأسئلة الطويلة أو الحساسة."
    );

    companion object {
        fun fromId(id: String?): AnswerMode = values().firstOrNull { it.id == id } ?: BALANCED
        fun read(prefs: SharedPreferences): AnswerMode = fromId(prefs.getString("answer_mode", BALANCED.id))
    }
}

object ImageIntent {
    private val EDIT = Regex(
        "(عدل|تعديل|فتح|فتحي|غمق|اقتص|قص |دور|ادر |قلب|فلتر|ابيض واسود|سيبيا|ضبابي|موه|تمويه|حده|حسن (ال)?(صوره|جوده)|" +
            "كبر|صغر|غير حجم|اكتب على|اضف نص|ارسم|حول (ال)?صوره|edit|crop|rotate|filter|brighten|resize)"
    )

    fun looksLikeEdit(text: String): Boolean = EDIT.containsMatchIn(TextNorm.forMatch(text))
}

/**
 * العقل المركزي: يصنّف السؤال محلياً (فوراً وبدون استهلاك طلب) ثم يختار أنسب موديل
 * من المزودات المتاحة لديك (التي لها مفتاح أو لا تحتاج مفتاحاً)، ويجرّب البديل عند الفشل.
 */
object SmartRouter {
    /** صحة المزودات: عدد الإخفاقات المتتالية وزمن الاستجابة المتوسط (توازن + تجنّب المتعطل). */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val latencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun record(route: Route, success: Boolean, elapsedMs: Long) {
        val key = route.provider
        if (success) {
            failures.remove(key)
            val old = latencyMs[key]
            latencyMs[key] = if (old == null) elapsedMs else (old * 3 + elapsedMs) / 4
        } else {
            failures[key] = (failures[key] ?: 0) + 1
        }
    }

    fun latency(provider: String): Long = latencyMs[provider] ?: 0L

    private val CODE_WORDS = Regex(
        "(كود|برمج|سكربت|دال[ةه]|دوال|خطا برمجي|compile|exception|stack ?trace|\\bbug\\b|debug|function|" +
            "\\bclass\\b|\\bimport\\b|\\bdef\\b|\\bfun\\b|public static|select .* from|\\bnpm\\b|\\bpip\\b|gradle|\\bgit\\b|docker|" +
            "\\bapi\\b|json|html|\\bcss\\b|python|kotlin|java|javascript|typescript|c\\+\\+|php|\\bsql\\b|react|flutter|" +
            "android studio|regex|تعبير نمطي|مكتب[ةه] برمجي|مشروع برمجي|ليب|لارافيل|django|node)"
    )
    private val CODE_EXT = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "cs", "go", "rs", "rb", "php",
        "sh", "gradle", "sql", "css", "html", "vue", "swift", "dart", "yml", "yaml", "json", "xml"
    )
    private val REASONING_WORDS = Regex(
        "(حل (ال)?مسال|حل (ال)?معادل|معادل|احسب|احتسب|برهن|اثبت|برهان|تكامل|تفاضل|احتمال|مصفوف|خطو[هة] بخطو[هة]|" +
            "فكر بعمق|منطق|لغز|math|prove|equation|derivative|integral|puzzle)"
    )
    private val MATH_EXPR = Regex("\\d+\\s*[+\\-*/^=×÷]\\s*\\d+")
    private val WRITING_WORDS = Regex(
        "(ترجم|ترجمه|لخص|تلخيص|اكتب (لي )?(مقال|قصه|رساله|خطاب|قصيده|بريد|تقرير|سيره|اعلان)|صياغه|اعد صياغه|" +
            "صحح (ال)?(نص|لغويا|املائيا)|translate|summarize|rewrite|essay|proofread)"
    )
    /** أسئلة معرفية تحتاج دقة: تواريخ وأحداث وفنانون وأغانٍ وأفلام ووثائق. */
    private val FACT_WORDS = Regex(
        "(فيلم|افلام|سينما|مسلسل|ممثل|ممثله|فنان|فنانه|مغني|مطرب|اغنيه|اغاني|البوم|مخرج|مؤلف|مولف|رواي|" +
            "وثيقه|وثائق|معاهده|اتفاقيه|دستور|قانون|بيان|تقرير|تاريخ|تواريخ|سنه|عام|ميلاد|وفاه|حدث|احداث|معركه|" +
            "ثوره|اكتشاف|اختراع|جائزه|بطوله|كاس|عاصمه|رئيس|ملكه|ملك|دوله|من هو|من هي|ما هو|ما هي|متي|اين|" +
            "\\bmovie\\b|\\bfilm\\b|\\bsinger\\b|\\bactor\\b|\\bhistory\\b|\\bdate\\b)"
    )
    /** أخبار وأسعار ونتائج حالية: نصنّفها معرفية حتى تُدعَم بمصادر ويب. */
    private val CURRENT_WORDS = Regex(
        "(اخبار(\\s|$|[؟?،.!])|عاجل|اسعار|سعر|طقس|نتيجه|مباراه|انتخابات|تصريح|قرار جديد)"
    )
    /** مجاملات قصيرة فقط: هذه وحدها تستحق المسار السريع، أما الأسئلة القصيرة فتحتاج مزوداً قوياً. */
    private val SMALLTALK = Regex(
        "(^|\\s)(مرحبا|مرحباً|اهلا|أهلا|هلا|السلام عليكم|سلام|صباح الخير|مساء الخير|شكرا|شكراً|شكرا لك|" +
            "تسلم|يعطيك العافية|تمام|اوكي|أوكي|ok|hi|hello|thanks|كيف حالك|كيف الحال|كيف حالكم|كيفك|كيف خالك|كيف خلك|شلونك|" +
            "ما اخبارك|ما أخبارك|من انت|من أنت|ما اسمك|وداعا|باي)(\\s|$|[!،.؟?])"
    )

    /** أفعال تطوير حقيقية: لا تكفي وحدها، بل مع سياق برمجي/مستودع. */
    private val DEV_ACTION = Regex(
        "(أصلح|اصلح|صحح|دقق|تدقيق|راجع|مراجعة|افحص|فحص|اختبر|جرّب|حلل|حلّل|أكمل|اكمل|طور|طوّر|تطوير|" +
            "عدل|عدّل|أضف|اضف|احذف|أنشئ|انشئ|ابن|ابني|بناء|ادفع|ارفع|دمج|نفذ|أنفذ|انفذ|اقرأ|اقرا|قراء[هة]|" +
            "implement|fix|audit|review|lint|inspect|test|build|deploy|push|commit|refactor|merge|release|patch|clone|update|add|remove)"
    )
    /** سياق يدل على كود أو مستودع حقيقي. */
    private val DEV_CONTEXT = Regex(
        "(github|\\bgit\\b|\\brepo\\b|repository|pull request|\\bpr\\b|branch|commit|workflow|actions|apk|" +
            "kotlin|android|gradle|compose|مستودع|مستودعات|ريبو|فرع|فروع|كوميت|جيت هاب|غيث هاب|" +
            "طلب سحب|دمج|بناء التطبيق|فشل البناء|سجل البناء|مشروع برمجي|كود المشروع|ملفات المشروع|" +
            "كود|برمج|برمجه|دال[هة]|دوال|تطبيق|مشروع|شاش[هة]|واجه[هة]|إعدادات|اعدادات|settings|screen|\\bui\\b)"
    )
    private val GITHUB_LINK = Regex("github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    /** أسئلة حضور/هوية/قدرات قصيرة: تجيب عنها واجهة الرد الفوري عادةً، وتُصنّف سريعة احتياطاً. */
    private val TRIVIAL_PHRASE = Regex(
        "(هل (انت|أنت) (هنا|موجود|متاح|جاهز)|(انت|أنت) هنا|^هل تعمل$|" +
            "بماذا يجب (ان|أن) تعمل|ماذا يجب (ان|أن) تفعل|ماذا تستطيع|ماذا يمكنك (ان|أن) تفعل|" +
            "ما وظيفتك|ما دورك|ما (هي )?مهامك|كيف (أ|ا)?ستخدمك)"
    )

    /** هل النص مجاملة/سؤال سطحي قصير يستحق رداً فورياً دون تفكير عميق؟ */
    fun isSmallTalk(text: String): Boolean {
        if (text.length > 80) return false
        val normalized = TextNorm.forMatch(text)
        return SMALLTALK.containsMatchIn(normalized) || TRIVIAL_PHRASE.containsMatchIn(normalized)
    }

    fun classify(text: String, hasImage: Boolean, attachmentChars: Int, attachmentName: String?): TaskKind {
        val t = TextNorm.forMatch(text)
        if (hasImage) return if (ImageIntent.looksLikeEdit(text)) TaskKind.IMAGE_EDIT else TaskKind.VISION
        if (attachmentChars > 14_000) return TaskKind.LONG_DOC
        // التحيات والأسئلة السطحية أولاً حتى لا تلتقطها كلمات مثل «كود» أو «تاريخ» بالخطأ.
        if (attachmentChars == 0 && isSmallTalk(text)) return TaskKind.FAST
        val ext = attachmentName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val attachedCode = ext in CODE_EXT && attachmentChars > 0
        if (text.contains("```") || attachedCode) return TaskKind.CODE
        // مهمة تطوير = فعل تعديل/بناء + سياق مستودع/كود، أو رابط GitHub مباشر.
        val devTask = GITHUB_LINK.containsMatchIn(t) ||
            (DEV_ACTION.containsMatchIn(t) && DEV_CONTEXT.containsMatchIn(t))
        if (devTask) return TaskKind.DEV
        if (CODE_WORDS.containsMatchIn(t)) return TaskKind.CODE
        if (REASONING_WORDS.containsMatchIn(t) || MATH_EXPR.containsMatchIn(t)) return TaskKind.REASONING
        if (WRITING_WORDS.containsMatchIn(t)) return TaskKind.WRITING
        if (FACT_WORDS.containsMatchIn(t) || CURRENT_WORDS.containsMatchIn(t)) return TaskKind.FACTUAL
        return TaskKind.GENERAL
    }

    private fun r(provider: String, model: String) = Route(provider, model)

    /** قوائم الأفضلية لكل نوع مهمة (من الأفضل إلى الأقل). تُصفّى حسب المزودات المتاحة لديك. */
    fun candidates(kind: TaskKind): List<Route> = when (kind) {
        TaskKind.FAST -> listOf(
            r("groq", "openai/gpt-oss-20b"), r("gemini", "gemini-3.5-flash-lite"), r("mistral", "mistral-small-latest"),
            r("kilo", "kilo-auto/free"), r("llm7", "fast"), r("nvidia", "openai/gpt-oss-20b"),
            r("cloudflare", "@cf/openai/gpt-oss-20b"), r("ollama", "gpt-oss:20b"),
            r("openrouter", "nvidia/nemotron-nano-9b-v2:free"), r("zai", "glm-4.7-flash"),
            r("opencode", "glm-5.3-flash"), r("opencode-go", "deepseek-v4.1-flash"),
            r("opencode-console", "glm-5.3-flash"), r("ovh", "gpt-oss-20b"), r("deepseek", "deepseek-flash"),
            r("openai", "gpt-6-luna"), r("claude", "claude-haiku-5-5")
        )
        TaskKind.GENERAL -> listOf(
            r("gemini", "gemini-3.8-flash"), r("claude", "claude-sonnet-5-5"), r("openai", "gpt-6-luna"),
            r("deepseek", "deepseek-flash"), r("mistral", "mistral-medium-latest"),
            r("cohere", "command-a-03-2025"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("zai", "glm-4.7-flash"), r("cloudflare", "@cf/openai/gpt-oss-120b"),
            r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("ovh", "gpt-oss-120b"), r("ollama", "gpt-oss:120b"), r("groq", "openai/gpt-oss-120b"),
            r("kilo", "nvidia/nemotron-3-super-120b-a12b:free"), r("openrouter", "nvidia/nemotron-3-super-120b-a12b:free"),
            r("opencode", "glm-5.3-flash"), r("opencode-go", "deepseek-v4.1-flash"), r("llm7", "default"),
            r("aihorde", "auto")
        )
        TaskKind.FACTUAL -> listOf(
            r("gemini", "gemini-3.8-flash"), r("claude", "claude-sonnet-5-5"), r("openai", "gpt-6-luna"),
            r("deepseek", "deepseek-flash"), r("mistral", "mistral-medium-latest"),
            r("cohere", "command-a-03-2025"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("zai", "glm-4.7-flash"), r("cloudflare", "@cf/openai/gpt-oss-120b"),
            r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("ovh", "gpt-oss-120b"), r("ollama", "gpt-oss:120b"), r("groq", "openai/gpt-oss-120b"),
            r("kilo", "kilo-auto/free"), r("opencode", "glm-5.3-flash"), r("opencode-go", "deepseek-v4.1-flash"), r("llm7", "default")
        )
        TaskKind.CODE -> listOf(
            r("claude", "claude-sonnet-5-5"), r("deepseek", "deepseek-flash"), r("openai", "gpt-6-luna"),
            r("opencode", "glm-5.3-flash"), r("opencode-go", "deepseek-v4.1-flash"),
            r("opencode-console", "deepseek-v4.1-flash"), r("mistral", "codestral-latest"), r("groq", "openai/gpt-oss-120b"),
            r("kilo", "poolside/laguna-s-2.1:free"), r("openrouter", "cohere/north-mini-code:free"),
            r("gemini", "gemini-3.8-flash"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("cohere", "north-mini-code-1-0"), r("ollama", "deepseek-v4-flash"), r("cloudflare", "@cf/openai/gpt-oss-120b"),
            r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("llm7", "default"), r("ovh", "gpt-oss-120b"), r("aihorde", "auto")
        )
        // مهام التطوير تحتاج نماذج قوية في اتباع الأدوات وتعديل المستودعات، مع بدائل مجانية قادرة.
        TaskKind.DEV -> listOf(
            r("claude", "claude-sonnet-5-5"), r("deepseek", "deepseek-flash"), r("openai", "gpt-6-luna"),
            r("opencode", "glm-5.3-flash"), r("opencode-go", "deepseek-v4.1-flash"),
            r("opencode-console", "deepseek-v4.1-flash"), r("gemini", "gemini-3.8-flash"),
            r("mistral", "codestral-latest"), r("groq", "openai/gpt-oss-120b"),
            r("zai", "glm-4.7-flash"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("cloudflare", "@cf/openai/gpt-oss-120b"),
            r("kilo", "nvidia/nemotron-3-super-120b-a12b:free"),
            r("openrouter", "cohere/north-mini-code:free"), r("ollama", "deepseek-v4-flash"),
            r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("llm7", "default"), r("ovh", "gpt-oss-120b")
        )
        TaskKind.REASONING -> listOf(
            r("claude", "claude-sonnet-5-5"), r("deepseek", "deepseek-flash"), r("openai", "gpt-6-luna"),
            r("groq", "openai/gpt-oss-120b"), r("gemini", "gemini-3.8-flash"), r("ollama", "deepseek-v4-flash"),
            r("nvidia", "nvidia/nemotron-3-super-120b-a12b"), r("zai", "glm-4.7-flash"),
            r("cloudflare", "@cf/openai/gpt-oss-120b"), r("kilo", "nvidia/nemotron-3-super-120b-a12b:free"),
            r("openrouter", "nvidia/nemotron-3-super-120b-a12b:free"), r("llm7", "DeepSeek-V4-Flash-0731"),
            r("cohere", "command-a-03-2025"), r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("ovh", "gpt-oss-120b")
        )
        TaskKind.WRITING -> listOf(
            r("claude", "claude-sonnet-5-5"), r("gemini", "gemini-3.8-flash"), r("openai", "gpt-6-luna"),
            r("mistral", "mistral-medium-latest"), r("cohere", "command-a-03-2025"), r("groq", "openai/gpt-oss-120b"),
            r("ollama", "gpt-oss:120b"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("kilo", "nvidia/nemotron-3-super-120b-a12b:free"), r("zai", "glm-4.7-flash"),
            r("llm7", "default"), r("together", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            r("ovh", "gpt-oss-120b"), r("aihorde", "auto")
        )
        TaskKind.VISION -> listOf(
            r("gemini", "gemini-3.8-flash"), r("claude", "claude-sonnet-5-5"), r("openai", "gpt-6-luna"),
            r("mistral", "mistral-small-latest"), r("zai", "glm-4.6v-flash"),
            r("openrouter", "google/gemma-4-31b-it:free"), r("kilo", "stepfun/step-3.7-flash:free"),
            r("cloudflare", "@cf/meta/llama-4-scout-17b-16e-instruct"),
            r("nvidia", "meta/llama-3.2-90b-vision-instruct"),
            r("ovh", "Qwen3.8-27B")
        )
        TaskKind.IMAGE_EDIT -> listOf(
            r("gemini", "gemini-3.8-flash"), r("groq", "openai/gpt-oss-120b"), r("claude", "claude-haiku-5-5"),
            r("mistral", "mistral-small-latest"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("kilo", "kilo-auto/free"), r("llm7", "default"), r("ollama", "gpt-oss:120b"), r("ovh", "gpt-oss-120b")
        )
        TaskKind.LONG_DOC -> listOf(
            r("gemini", "gemini-3.8-flash"), r("kilo", "nvidia/nemotron-3.5-lightning:free"),
            r("openrouter", "nvidia/nemotron-3.5-lightning:free"), r("nvidia", "nvidia/nemotron-3-super-120b-a12b"),
            r("ollama", "deepseek-v4-flash"), r("mistral", "mistral-medium-latest"), r("claude", "claude-sonnet-5-5"),
            r("llm7", "DeepSeek-V4-Flash-0731")
        )
    }

    /** مزودات نعتبرها قوية بما يكفي للأسئلة المعرفية الحساسة. */
    private val TRUSTED_FACTUAL = setOf(
        "gemini", "groq", "claude", "openai", "deepseek", "mistral",
        "cohere", "nvidia", "zai", "cloudflare", "together", "ovh", "ollama"
    )

    fun isStrict(kind: TaskKind): Boolean = kind == TaskKind.FACTUAL || kind == TaskKind.LONG_DOC

    fun isTrustedFactual(provider: String): Boolean = provider in TRUSTED_FACTUAL

    /** نماذج صغيرة/سريعة نعرف أنها تهلوّس في الأسئلة المعرفية؛ تُستبعد عند توفر بديل أقوى. */
    private val WEAK_MODEL_HINTS = listOf(
        "gpt-oss-20b", "llama-3.1-8b", "llama-3.2-3b", "llama-3.2-1b",
        "compound-mini", "flash-lite", "open-mistral-nemo", "command-r7b",
        "north-mini", "kilo-auto", "gemma", "minimax", "mimo", "laguna",
        "qwen3.8-27b", "step-3.7", "default"
    )

    /** هل المزود + الموديل معاً جديران بالأسئلة التي تحتاج دقة؟ */
    fun isReliableModel(provider: String, model: String): Boolean {
        if (!isTrustedFactual(provider)) return false
        val info = PROVIDERS.firstOrNull { it.id == provider } ?: return false
        val id = model.ifBlank { info.defaultModel }.lowercase(Locale.ROOT)
        return WEAK_MODEL_HINTS.none { id == it || id.contains(it) }
    }

    /** هل يوجد نموذج قوي متاح فعلاً لهذا النوع؟ (العام قد يصبح صارماً في وضع الدقة) */
    fun hasTrusted(prefs: SharedPreferences, kind: TaskKind): Boolean =
        (isStrict(kind) || kind == TaskKind.GENERAL) && candidates(kind).any {
            available(prefs, it.provider) && isReliableModel(it.provider, it.model)
        }

    fun available(prefs: SharedPreferences, provider: String): Boolean {
        val info = PROVIDERS.firstOrNull { it.id == provider } ?: return false
        if (provider == "custom") {
            return (prefs.getString("endpoint_custom", "") ?: "").trim().isNotEmpty()
        }
        val key = (prefs.getString("api_key_$provider", "") ?: "").trim()
        if (provider == "cloudflare" && (prefs.getString("cf_account_id", "") ?: "").isBlank()) return false
        return info.keyless || key.isNotEmpty()
    }

    /** أقصى مدة انتظار مناسبة لكل نوع، حتى لا يعلق سؤال بسيط في مزود بطيء. */
    fun timeoutMs(kind: TaskKind): Long = when (kind) {
        TaskKind.FAST -> 35_000L
        TaskKind.VISION -> 90_000L
        TaskKind.IMAGE_EDIT -> 60_000L
        TaskKind.DEV -> 180_000L
        TaskKind.LONG_DOC -> 150_000L
        else -> 120_000L
    }

    /**
     * المسارات المتاحة فعلاً بالترتيب، موديل واحد لكل مزود.
     * يستبعد المزودات التي فشلت مرتين متتاليتين عندما يوجد بديل سليم،
     * ويؤخّر البطيء جداً لصالح الأسرع عند تعدد البدائل.
     */
    fun plan(
        prefs: SharedPreferences,
        kind: TaskKind,
        mode: AnswerMode = AnswerMode.read(prefs)
    ): List<Route> {
        // موديل المزود الذي اخترته/حدّثته من قائمة النماذج يتقدّم على الموديل الافتراضي المكتوب في القوائم
        // (إلا في الصور حيث يلزم موديل يدعم الرؤية).
        val availableRoutes = candidates(kind).filter { available(prefs, it.provider) }.map {
            if (kind == TaskKind.VISION || kind == TaskKind.IMAGE_EDIT) it else withUserModel(prefs, it)
        }
        val reliableRoutes = availableRoutes.filter { isReliableModel(it.provider, it.model) }
        // المسار السريع يبقى سريعاً دائماً: لا نرفعه إلى نماذج ثقيلة مهما كان وضع الجودة.
        val fastOnly = kind == TaskKind.FAST || mode == AnswerMode.FAST
        val strict = !fastOnly && (isStrict(kind) || (mode == AnswerMode.PRECISE && kind == TaskKind.GENERAL))
        val preferReliable = !fastOnly && (
            (kind == TaskKind.GENERAL && mode != AnswerMode.FAST) ||
                mode == AnswerMode.PRECISE || kind == TaskKind.DEV
            )
        val base = when {
            strict -> reliableRoutes.ifEmpty { availableRoutes }
            preferReliable && reliableRoutes.isNotEmpty() ->
                reliableRoutes + availableRoutes.filterNot { r -> reliableRoutes.any { it == r } }
            else -> availableRoutes
        }
        val routes = base.distinctBy { it.provider }
        val maxAttempts = when {
            fastOnly -> 2
            kind == TaskKind.VISION || kind == TaskKind.IMAGE_EDIT -> 3
            else -> 4
        }
        val (healthy, failing) = routes.partition { (failures[it.provider] ?: 0) < 2 }
        // إن وُجد بديل سليم فلا نضيّع محاولات على مزود معروف الفشل.
        val ordered = (
            healthy + if (healthy.size >= maxAttempts) emptyList() else failing.take(maxAttempts - healthy.size)
            ).toMutableList()
        // توازن الاستجابة: إن كان الأول بطيئاً جداً (>20ث) فأعطِ الأولوية لأسرع بديل موثوق.
        if (ordered.size >= 2) {
            val first = latency(ordered[0].provider)
            if (first > 20_000) {
                val fasterIndex = (1 until ordered.size).firstOrNull { index ->
                    val candidate = latency(ordered[index].provider)
                    candidate > 0 && candidate + 8_000 < first
                }
                if (fasterIndex != null) {
                    val fastest = ordered.removeAt(fasterIndex)
                    ordered.add(0, fastest)
                }
            }
        }
        // الأفضل والأسرع: لا يحتكر المزود الأول كل المحادثات.
        // (1) استكشاف: كل مزود سليم لم يُقَس بعد يُجرَّب مرة واحدة فيدخل المنافسة.
        // (2) بعد القياس: إن كان بديل ضمن أول 3 أسرع بوضوح (30%+) يتقدّم، إلا في الأسئلة الصارمة حيث الجودة أولاً.
        if (!strict && ordered.size >= 2) {
            val head = ordered.take(3)
            val untried = head.drop(1).firstOrNull {
                latency(it.provider) == 0L && (failures[it.provider] ?: 0) == 0 && explored.add(it.provider)
            }
            if (untried != null) {
                ordered.remove(untried)
                ordered.add(0, untried)
            } else {
                val first = latency(ordered[0].provider)
                val best = head.drop(1).filter {
                    val l = latency(it.provider)
                    l > 0 && l < first - 1500 && l * 10 < first * 7
                }.minByOrNull { latency(it.provider) }
                if (first > 0 && best != null) {
                    ordered.remove(best)
                    ordered.add(0, best)
                }
            }
        }
        return ordered.take(maxAttempts)
    }

    private val explored = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun withUserModel(prefs: SharedPreferences, route: Route): Route {
        val saved = (prefs.getString("model_${route.provider}", "") ?: "").trim()
        return if (saved.isNotBlank()) route.copy(model = saved) else route
    }

    fun label(route: Route, kind: TaskKind?): String {
        val info = providerInfo(route.provider)
        val model = route.model.ifBlank { info.defaultModel }
        return info.shortName + " · " + model + (kind?.let { " · " + it.label } ?: "")
    }
}

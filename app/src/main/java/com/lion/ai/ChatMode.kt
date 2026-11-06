package com.lion.ai

import android.content.SharedPreferences

/**
 * أوضاع المحادثة المعزولة على مستوى وقت التشغيل.
 *
 * الفصل ليس تجميلياً: كل وضع ينتج ContextProfile مختلفاً يحدد تعليمات النظام
 * ومجموعات الأدوات المسموح بها. وضع الدردشة العامة والتعلّم لا يملكان أي أداة
 * ملفات أو صدفة أو Git، ولا يُحقن فيهما مسار مشروع أو AGENTS.md.
 */
enum class ChatMode(
    val id: String,
    val label: String,
    val short: String,
    val icon: String,
    val color: Long,
    val hint: String
) {
    GENERAL(
        id = "general",
        label = "دردشة عامة",
        short = "عام",
        icon = "💬",
        color = 0xFF4CAF50,
        hint = "محادثة وثقافة وأسئلة معرفية، بلا أدوات ملفات أو صدفة أو Git."
    ),
    CODE(
        id = "code",
        label = "المطوّر",
        short = "المطوّر",
        icon = "🛠️",
        color = 0xFF2196F3,
        hint = "بيئة التطوير الحقيقية: ملفات وصدفة وGit وبناء، مع شرح وتعليم أثناء العمل."
    );

    companion object {
        // وضع «تعلّم» القديم اندمج في «المطوّر»: المحادثات المحفوظة به تنتقل تلقائياً.
        fun fromId(id: String?): ChatMode = if (id == "learn") CODE else entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

/** حزمة سياق مستقلة تُبنى مرة لكل وضع وتُمرّر إلى حلقة الوكيل. */
data class ContextProfile(
    val mode: ChatMode,
    val allowTools: Boolean,
    val allowWrite: Boolean,
    val groups: Set<ToolGroup>,
    val promptSuffix: String,
    val injectProjectContext: Boolean,
    val injectAgentsFile: Boolean
)

object ContextProfiles {
    private val OPTIONAL_GROUP = setOf(
        ToolGroup.WEB,
        ToolGroup.UTIL,
        ToolGroup.DATABASE,
        ToolGroup.CONNECTORS,
        ToolGroup.COMMS
    )

    private val GENERAL_PROMPT = """

وضع الدردشة العامة:
- محادثة عامة أو ثقافية أو معرفية؛ حافظ على نبرة طبيعية ومختصرة افتراضياً.
- لا تفترض وجود مشروع برمجي أو Manifest ما لم يذكره المستخدم أو تعثر عليه بأداة.
- إن طُلب عمل تنفيذي فاستخدم الأدوات المتاحة فعلياً بدل الاعتذار عن عدم القدرة.
- سؤال عن قدراتك («هل تستطيع/بإمكانك…؟») أو سؤال معرفي: أجب مباشرة بجملة أو جملتين (نعم/لا + ما تستطيعه مع مثال قصير). لا تبدأ تنفيذاً ولا تطلب قراراً أو موافقة؛ التنفيذ فقط عند طلب صريح.
""".trimIndent()

    private val ALL_TOOLS_NOTE = """

الأدوات مفعّلة في هذه المحادثة أيضاً:
- لديك أدوات الملفات والصدفة وGit/GitHub والبحث والموصلات وFirebase عند ربطها. استخدمها عند الحاجة الفعلية.
- لا تدّعِ نتيجة أداة لم تُنفّذ؛ وبعد كل كتابة تحقق (قراءة/فرق/بوابة).
- للعمليات الخطرة (حذف/دفع/إرسال/تثبيت حزم) اطلب تأكيداً صريحاً في الرسالة.
""".trimIndent()

    private val NO_TOOLS_NOTE = """

الأدوات غير مفعّلة في هذا الوضع:
- لا تستخدم أي أداة ملفات أو صدفة أو Git أو GitHub، ولا تدّعِ أنك عدّلت شيئاً.
- إن طلب المستخدم عملاً تنفيذياً فاقترح ترقية المحادثة إلى «المطوّر» أو تفعيل «الأدوات في كل الأوضاع» من إعدادات أدوات المطور.
""".trimIndent()

    private val CODE_PROMPT = """

وضع الهندسة والبرمجة (عزل وقت تشغيل + حوكمة + منهجية علمية):
- راقب (اقرأ) ← أصغر تعديل ← تحقق بأداة ← تابع. لا تتوقف لطلب «أكمل».
- سؤال عن قدراتك («هل تستطيع/بإمكانك…؟») أو سؤال معرفي: أجب مباشرة بجملة أو جملتين (نعم/لا + ما تستطيعه مع مثال قصير). لا تبدأ تنفيذاً ولا تطلب قراراً أو موافقة؛ التنفيذ فقط عند طلب صريح.
- اسأل المستخدم بـ [[ASK]] فقط عند نقص معلومة أو موافقة خطرة أو اختيار بين بديلين بعد إنجاز ما يمكن.
- هذا سياق هندسي مستقل. اتبع دورة: Manifest معتمد ← وحدة تنفيذ واحدة ← بوابة جودة ميكانيكية ← مراجعة ← اختبار ← بناء.
- لا تكتب كوداً في مشروع له Manifest غير معتمد. لا تعلن «تم» قبل تشغيل بوابة فعلية.
- كل ملف مصدري يجب أن يصل إلى نقطة دخول حقيقية. الملفات اليتيمة أو الفارغة أو TODO تُرفض.
- لا تعبث بالاختبارات لجعلها خضراء؛ بوابة HMAC تكشف تعديل ملفات الاختبار دون تفويض.
- احفظ Checkpoint بعد كل خطوة فرعية، واستأنف من آخر نقطة. إن كان العمل ناقصاً فأكمل الناقص فقط.
- أنت مطوّر ومعلّم معاً: نفّذ بالأدوات، ثم اشرح باختصار ما فعلته ولماذا؛ وعند سؤال تعليمي اشرح تدريجياً بأمثلة وتمرين قصير دون إنشاء ملفات لم تُطلب.
""".trimIndent()

    fun of(mode: ChatMode, prefs: SharedPreferences, text: String = ""): ContextProfile {
        val devTask = mode != ChatMode.CODE && DevTask.matches(text)
        val enabled = AiTools.agentEnabled(prefs)
        // طلب المستخدم: الأدوات متاحة في كل الأوضاع (عام/تعلّم/برمجة) مع كل المزودات، ويمكن تعطيلها.
        val everywhere = prefs.getBoolean("tools_everywhere", true)
        val allowHere = enabled && (mode == ChatMode.CODE || everywhere)
        if (!allowHere) {
            val prompt = (if (mode == ChatMode.CODE) CODE_PROMPT else GENERAL_PROMPT)
            return ContextProfile(
                mode = mode,
                allowTools = false,
                allowWrite = false,
                groups = emptySet(),
                promptSuffix = prompt + NO_TOOLS_NOTE,
                injectProjectContext = false,
                injectAgentsFile = false
            )
        }
        // وضع البرمجة: حزمة التطوير الأساسية. العام/التعلّم: مجموعة META فقط + ما يكتشفه النص،
        // والمزيد يُحمَّل عند الحاجة عبر tools_schema (إرسال كل الأدوات مع كل رسالة كان يملأ سياق المزودات المجانية فتفشل).
        val lean = mode != ChatMode.CODE
        val base = if (lean) {
            mutableSetOf(ToolGroup.META)
        } else {
            mutableSetOf(
                ToolGroup.FILES,
                ToolGroup.CODE,
                ToolGroup.SHELL,
                ToolGroup.QUALITY,
                ToolGroup.CONTROL,
                ToolGroup.MEMORY,
                ToolGroup.MCP,
                ToolGroup.META,
                ToolGroup.FIREBASE
            )
        }
        if (AiTools.githubReady(prefs) && !lean) base.add(ToolGroup.GITHUB)
        var detectedAny = false
        if (everywhere && !lean) {
            // وضع البرمجة: كل المجموعات (ToolAgent يقلّصها تلقائياً للمزودات ذات السياق المحدود).
            base.addAll(ToolGroup.values().filter { it != ToolGroup.GITHUB || AiTools.githubReady(prefs) })
        } else {
            val detected = runCatching { AiTools.groupsFor(text, prefs) }.getOrDefault(emptySet())
            detectedAny = detected.isNotEmpty() || devTask
            if (devTask) {
                // طلب بناء/تطوير في وضع «عام»: تُفتح أدوات التطوير تلقائياً بدل رد كلامي بلا تنفيذ.
                base.addAll(listOf(ToolGroup.FILES, ToolGroup.CODE, ToolGroup.SHELL, ToolGroup.QUALITY, ToolGroup.CONTROL, ToolGroup.MEMORY))
            }
            if (lean) {
                base.addAll(detected.filter { it != ToolGroup.GITHUB || AiTools.githubReady(prefs) })
            } else {
                base.addAll(detected.filter { it in OPTIONAL_GROUP })
                if (ToolGroup.GITHUB in detected && AiTools.githubReady(prefs)) base.add(ToolGroup.GITHUB)
            }
        }
        if (lean && !detectedAny) {
            // محادثة عادية (عام/تعلّم) بلا طلب أدوات: رد مباشر بسياق نظيف، دون حقن كتالوج الأدوات ولا تعليمات وكيل البرمجة.
            return ContextProfile(
                mode = mode,
                allowTools = false,
                allowWrite = false,
                groups = emptySet(),
                promptSuffix = GENERAL_PROMPT,
                injectProjectContext = false,
                injectAgentsFile = false
            )
        }
        val suffix = if (mode == ChatMode.CODE) {
            CODE_PROMPT + "\n\n" + DevRoles.prompt() + "\n\n" + ContextStore.governancePrompt(prefs)
        } else {
            GENERAL_PROMPT + ALL_TOOLS_NOTE
        }
        return ContextProfile(
            mode = mode,
            allowTools = enabled && base.isNotEmpty(),
            allowWrite = enabled && AiTools.allowWrite(prefs),
            groups = base,
            promptSuffix = suffix,
            injectProjectContext = mode == ChatMode.CODE || detectedAny,
            injectAgentsFile = mode == ChatMode.CODE || detectedAny
        )
    }
}

/** تخزين وضع كل محادثة وجعل الافتراضي قابلاً للضبط. */
object ContextStore {
    private const val PREFIX = "chat_mode_"
    private const val DEFAULT = "default_chat_mode"

    fun read(prefs: SharedPreferences, chatId: String): ChatMode {
        val raw = prefs.getString(PREFIX + chatId, null)
        if (raw != null) return ChatMode.fromId(raw)
        // محادثة قديمة لها جلسة عمل غير مكتملة: لا نفقد سياق البرمجة عند الترقية.
        if (CheckpointStore.has(prefs, chatId) || AgentSessions.hasProgress(chatId)) return ChatMode.CODE
        return ChatMode.fromId(prefs.getString(DEFAULT, ChatMode.GENERAL.id))
    }

    fun write(prefs: SharedPreferences, chatId: String, mode: ChatMode) {
        prefs.edit().putString(PREFIX + chatId, mode.id).apply()
    }

    fun defaultMode(prefs: SharedPreferences): ChatMode =
        ChatMode.fromId(prefs.getString(DEFAULT, ChatMode.GENERAL.id))

    fun remove(prefs: SharedPreferences, chatId: String) {
        prefs.edit().remove(PREFIX + chatId).apply()
    }

    /** تعليمات الحوكمة تُضاف فقط لوضع البرمجة، وليست موجودة في الوضع العام. */
    fun governancePrompt(prefs: SharedPreferences): String {
        val manifest = ManifestStore.latest(prefs)
        return buildString {
            append("=== 🛡️ عقد الحوكمة الميكانيكية ===\n")
            append("1) لا كتابة قبل اعتماد Manifest. استخدم manifest_create ثم manifest_validate.\n")
            append("2) بعد كل وحدة استخدم gate_run. إن فشلت البوابة لا تنتقل؛ أصلح الأخطاء وأعد الفحص.\n")
            append("3) استخدم root_reachability للتأكد من عدم وجود ملفات يتيمة.\n")
            append("4) استخدم gate_verify_tests قبل التسليم للتأكد من سلامة الاختبارات (HMAC).\n")
            append("5) احفظ checkpoint_save بعد كل خطوة؛ واستخدم checkpoint_resume عند الاستئناف.\n")
            append("6) لا تقل «تم» إلا إذا أعادت gate_run وgate_verify_tests ونتيجة البناء/الاختبار نجاحاً فعلياً.\n")
            if (manifest != null) {
                append("\nManifest الحالي: ").append(manifest.project)
                    .append(" | ملفات: ").append(manifest.files.size)
                    .append(" | معتمد: ").append(if (manifest.approved) "نعم" else "لا").append('\n')
            }
        }
    }
}

/** كشف طلبات البرمجة قبل تفعيل الأدوات، لعرض ترقية صريحة بدل تنفيذ صامت. */
object ModeGuard {
    private val CODE_ACTION = Regex(
        "(اكتب|أنشئ|انشئ|ابن|ابني|أصلح|اصلح|عدل|عدّل|أضف|اضف|احذف|ارفع|ادفع|طور|طوّر|أكمل|اكمل|راجع|دقق|اختبر|نفذ|أنفذ|" +
            "build|create|write|fix|refactor|implement|deploy|commit|push|patch|compile|debug|test)"
    )
    private val CODE_TARGET = Regex(
        "(تطبيق|موقع|مشروع|كود|سكربت|دالة|دوال|كلاس|واجهة|شاشة|ملف|مستودع|ريبو|" +
            "apk|android|kotlin|java|python|javascript|typescript|react|flutter|gradle|github|" +
            "\\.kt\\b|\\.java\\b|\\.py\\b|\\.js\\b|\\.ts\\b|\\.tsx\\b|```)"
    )

    private val ACCESS_TARGET = Regex(
        "(ملف|ملفات|مستودع|مستودعات|ريبو|إنترنت|انترنت|رابط|روابط|موقع|ويب|github|api|جهازي|ملفاتي|مساحة العمل|workspace)"
    )
    private val ACCESS_ACTION = Regex(
        "(وصول|أصل|اوصل|ادخل|دخول|افتح|فتح|اقرأ|قراءة|عدل|تعديل|عدّل|تعديلها|فحص|حلل|تنفيذ|نفذ|جلب|تحميل|رفع|كتابة)"
    )

    fun looksLikeCodingRequest(text: String, kind: TaskKind): Boolean {
        if (UserIntent.isCapabilityQuestion(text)) return false
        if (kind == TaskKind.DEV) return true
        val normalized = TextNorm.forMatch(text)
        if (normalized.contains("```")) return true
        if (CODE_ACTION.containsMatchIn(normalized) && CODE_TARGET.containsMatchIn(normalized)) return true
        // طلبات الوصول للأدوات (ملفات/مستودعات/مواقع) في وضع غير برمجي يجب أن تعرض الترقية.
        return ACCESS_TARGET.containsMatchIn(normalized) && ACCESS_ACTION.containsMatchIn(normalized)
    }

    fun promotionOffer(mode: ChatMode): String =
        "🔒 هذا طلب برمجي تنفيذي، والمحادثة الحالية في وضع «عام» بلا أدوات ملفات أو صدفة أو Git. " +
            "انتقل إلى وضع «المطوّر» ليُنفَّذ في بيئة التطوير ولا يتلوث سياق الدردشة العامة."
}

/** يكشف طلبات البناء/التطوير الصريحة (فعل تنفيذ + موضوع برمجي) ليعمل الوكيل بأدواته حتى في «عام». */
object DevTask {
    private val VERB = Regex(
        """(?<![\p{L}])(اعمل|اعملي|اصنع|أضف|اضف|ضيف|طوّر|طور|برمج|ابنِ|ابن|ابني|صمم|صمّم|أنشئ|انشئ|حسّن|حسن|عدّل|عدل|أصلح|اصلح|ركّب|ركب|ترجم|حوّل|حول|اكتب|نفّذ|نفذ|شغّل|شغل|ثبّت|ثبت|ارفع|ادفع|build|make|create|add|implement|develop|fix|write|generate)(?![\p{L}])""",
        RegexOption.IGNORE_CASE
    )
    private val TOPIC = Regex(
        """(وحدة|وحده|ميزة|ميزه|تطبيق|مشروع|كود|سكربت|سكريبت|برنامج|مكتبة|إضافة|اضافة|بوت|ماجيسك|magisk|module|feature|app|apk|script|code|plugin|library|gradle|kotlin|python|\.kt|\.py|\.js|api|واجهة|دالة|class|function|github|مستودع|ريبو)""",
        RegexOption.IGNORE_CASE
    )
    fun matches(text: String): Boolean = text.length > 3 && VERB.containsMatchIn(text) && TOPIC.containsMatchIn(text)
}

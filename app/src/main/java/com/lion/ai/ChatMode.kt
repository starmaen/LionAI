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
        label = "هندسة وبرمجة",
        short = "برمجة",
        icon = "🛠️",
        color = 0xFF2196F3,
        hint = "سياق هندسي كامل: Manifest وبوابات جودة وأدوات ملفات وصدفة وGit وبناء."
    ),
    LEARN(
        id = "learn",
        label = "تعلّم",
        short = "تعلّم",
        icon = "🎓",
        color = 0xFFFFC107,
        hint = "شرح موجّه وأسئلة سقراطية وتمارين، بلا إنشاء ملفات أو تعديل مشاريع."
    );

    companion object {
        fun fromId(id: String?): ChatMode = entries.firstOrNull { it.id == id } ?: GENERAL
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

وضع الدردشة العامة (عزل وقت تشغيل):
- هذه محادثة عامة أو ثقافية أو معرفية. لا تستخدم أي أداة ملفات أو صدفة أو Git أو GitHub أو بناء تطبيقات.
- لا تفترض وجود مشروع برمجي، ولا تذكر مسار مشروع أو AGENTS.md أو Manifest.
- لا تدّعِ أنك عدّلت ملفاً أو بنيت تطبيقاً؛ فهذه القدرات غير مفعّلة في هذا الوضع.
- إن طلب المستخدم كتابة تطبيق أو تعديل كود أو بناء مشروع فاشرح له أن هذه المهمة تحتاج وضع «هندسة وبرمجة»، واقترح ترقية المحادثة صراحةً.
""".trimIndent()

    private val LEARN_PROMPT = """

وضع التعلّم (عزل وقت تشغيل):
- أنت مدرّب تعليمي. اشرح بالفهم التدريجي، واسأل أسئلة سقراطية قصيرة، واقترح تمارين عملية.
- لا تنشئ ملفات ولا تعدّل مشاريع ولا تنفّذ أوامر صدفة أو Git.
- يمكنك شرح الكود وإعطاء أمثلة نصية داخل الرد، لكن لا تدّعِ تنفيذها أو حفظها.
- إن طلب المستخدم بناء مشروع فعلي أو تعديل ملفات فاقترح الترقية الصريحة إلى وضع «هندسة وبرمجة».
""".trimIndent()

    private val CODE_PROMPT = """

وضع الهندسة والبرمجة (عزل وقت تشغيل + حوكمة):
- هذا سياق هندسي مستقل. اتبع دورة: Manifest معتمد ← وحدة تنفيذ واحدة ← بوابة جودة ميكانيكية ← مراجعة ← اختبار ← بناء.
- لا تكتب كوداً في مشروع له Manifest غير معتمد. لا تعلن «تم» قبل تشغيل بوابة فعلية (وجود الملفات + تحليل البنية + Root Reachability + سلامة الاختبارات).
- كل ملف مصدري يجب أن يصل إلى نقطة دخول حقيقية. الملفات اليتيمة أو الفارغة أو التي تحتوي TODO/NotImplemented تعتبر نقصاً وترفض.
- لا تعبث بالاختبارات لجعلها خضراء؛ بوابة HMAC تكشف تعديل ملفات الاختبار دون تفويض.
- احفظ Checkpoint بعد كل خطوة فرعية، واستأنف من آخر نقطة عند تبديل المزود أو انقطاع الجلسة.
- إن كان العمل ناقصاً فأكمل الناقص فقط، ولا تبدأ المشروع من الصفر.
""".trimIndent()

    fun of(mode: ChatMode, prefs: SharedPreferences, text: String = ""): ContextProfile {
        if (mode != ChatMode.CODE) {
            return ContextProfile(
                mode = mode,
                allowTools = false,
                allowWrite = false,
                groups = emptySet(),
                promptSuffix = if (mode == ChatMode.LEARN) LEARN_PROMPT else GENERAL_PROMPT,
                injectProjectContext = false,
                injectAgentsFile = false
            )
        }
        val enabled = AiTools.agentEnabled(prefs)
        val groups = if (!enabled) emptySet() else {
            val base = mutableSetOf(
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
            if (AiTools.githubReady(prefs)) base.add(ToolGroup.GITHUB)
            val detected = runCatching { AiTools.groupsFor(text, prefs) }.getOrDefault(emptySet())
            base.addAll(detected.filter { it in OPTIONAL_GROUP })
            if (ToolGroup.GITHUB in detected && AiTools.githubReady(prefs)) base.add(ToolGroup.GITHUB)
            base
        }
        return ContextProfile(
            mode = mode,
            allowTools = enabled && groups.isNotEmpty(),
            allowWrite = enabled && AiTools.allowWrite(prefs),
            groups = groups,
            promptSuffix = CODE_PROMPT + "\n\n" + DevRoles.prompt() + "\n\n" + ContextStore.governancePrompt(prefs),
            injectProjectContext = true,
            injectAgentsFile = true
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

    fun setDefault(prefs: SharedPreferences, mode: ChatMode) {
        prefs.edit().putString(DEFAULT, mode.id).apply()
    }

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

    fun looksLikeCodingRequest(text: String, kind: TaskKind): Boolean {
        if (kind == TaskKind.DEV) return true
        val normalized = TextNorm.forMatch(text)
        if (normalized.contains("```")) return true
        return CODE_ACTION.containsMatchIn(normalized) && CODE_TARGET.containsMatchIn(normalized)
    }

    fun promotionOffer(mode: ChatMode): String = when (mode) {
        ChatMode.LEARN ->
            "🎓 هذا طلب برمجي تنفيذي، ووضع التعلّم لا ينشئ ملفات أو يعدّل مشاريع. " +
                "ارقِ المحادثة صراحةً إلى «هندسة وبرمجة» لتنفيذه بأدواته وبواباته."
        else ->
            "🔒 هذا طلب برمجي، والمحادثة الحالية في وضع «دردشة عامة» بلا أدوات ملفات أو صدفة أو Git. " +
                "ارقِ المحادثة صراحةً إلى «هندسة وبرمجة» ليُنشأ سياق برمجي مستقل ولا يتلوث سياق الدردشة العامة."
    }
}

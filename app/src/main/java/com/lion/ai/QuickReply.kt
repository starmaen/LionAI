package com.lion.ai

/**
 * ردود فورية للمجاملات الخالصة والأسئلة القصيرة عن الحضور/الهوية/القدرات.
 * الهدف: ألا تنتظر تحية بسيطة دورة مزود كاملة (شبكة + مهلة + بدائل).
 */
object QuickReply {
    private const val GREETING_REPLY =
        "أهلاً بك! 👋 أنا Lion AI. اكتب سؤالك أو مهمتك وسأبدأ فوراً."
    private const val THANKS_REPLY = "على الرحب والسعة! 🌟"
    private const val PRESENCE_REPLY = "نعم، أنا هنا وجاهز. 👋 تفضّل بسؤالك أو مهمتك."
    private const val IDENTITY_REPLY =
        "أنا Lion AI، مساعدك العربي متعدد المزودات. أختار المزوّد الأنسب تلقائياً، وأبحث عن مصادر عند الحاجة، " +
            "وأعمل في أوضاع المحادثة والتعلّم والبرمجة."
    private const val CAPABILITY_REPLY =
        "أعمل كمنسّق مركزي: أختار المزوّد الأنسب وأبدّله تلقائياً عند الفشل، وأدعم الأسئلة العامة بمصادر، " +
            "وأشرح بأسلوب تعليمي في وضع التعلّم، وأنفّذ مهام البرمجة بأدوات الملفات وGitHub والبناء."

    private val GREETING = Regex(
        "^(مرحبا|مرحبا بك|اهلا|اهلا بك|هلا|السلام عليكم|سلام|صباح الخير|مساء الخير|هاي|hi|hello|hey)" +
            "( (كيف حالك|كيف الحال|كيف حالكم|كيفك|كيف خالك|شلونك))?( اليوم)?$"
    )
    private val THANKS = Regex(
        "^(شكرا|شكرا لك|شكرا جزيلا|تسلم|يعطيك العافيه|مشكور|thanks|thank you|thx)$"
    )
    private val PRESENCE = Regex(
        "^(هل انت هنا|هل انت موجود|انت هنا|هل انت متاح|هل انت جاهز|هل تعمل|هل انت تعمل|" +
            "are you there|you there|are you here)( الان)?$"
    )
    private val IDENTITY = Regex(
        "^(من انت|ما اسمك|who are you|what are you)$"
    )
    private val CAPABILITY = Regex(
        "^(بماذا يجب ان تعمل|ماذا يجب ان تفعل|ماذا تستطيع|ماذا يمكنك ان تفعل|ما وظيفتك|ما دورك|" +
            "ما هي مهامك|كيف استخدمك|what can you do|what do you do)$"
    )

    fun forText(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 120) return null
        val normalized = TextNorm.forMatch(text)
            .replace(Regex("[!،؛.؟?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            GREETING.matches(normalized) -> GREETING_REPLY
            THANKS.matches(normalized) -> THANKS_REPLY
            PRESENCE.matches(normalized) -> PRESENCE_REPLY
            IDENTITY.matches(normalized) -> IDENTITY_REPLY
            CAPABILITY.matches(normalized) -> CAPABILITY_REPLY
            else -> null
        }
    }
}

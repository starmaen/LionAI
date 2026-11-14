package com.lion.ai

/**
 * حزمة ترحيل المحادثة: ملخص منظم (بلا استدعاء نموذج، يعمل دون اتصال) يُلصق في أي مزود أو صفحة ويب
 * أو تطبيق آخر ليفهم الواقع السابق ويتابع بدل تكرار الخطوات.
 * لا تنقل إذن الكتابة: أي صلاحية يمنحها المستخدم في المكان الجديد فقط. الأسرار تُحجب.
 */
object HandoffBuilder {
    const val START = "[LION_HANDOFF v1]"
    const val END = "[/LION_HANDOFF]"

    private fun clean(text: String): String =
        WebToolBridge.redact(ToolMarkup.strip(FileDelivery.strip(text))).replace(Regex("\n{3,}"), "\n\n").trim()

    private fun cut(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** الأسطر التي تخص تطبيقنا (إذن الكتابة وقواعد الاستئناف الداخلية) لا تُنقل إلى جهة أخرى. */
    private fun sessionDigest(block: String): String =
        block.lineSequence()
            .filterNot { it.startsWith("إذن الكتابة:") || it.startsWith("قواعد الاستئناف:") || it.startsWith("===") }
            .joinToString("\n").trim()

    fun build(
        title: String,
        messages: List<Pair<Boolean, String>>,
        sessionBlock: String,
        now: String,
        maxChars: Int = 24_000
    ): String {
        val turns = messages.map { it.first to clean(it.second) }.filter { it.second.isNotBlank() }
        val goal = turns.firstOrNull { it.first }?.second.orEmpty()
        val digest = sessionDigest(sessionBlock)
        val recent = turns.takeLast(6)
        val head = buildString {
            appendLine(START)
            appendLine("# حزمة ترحيل محادثة: ${cut(title.ifBlank { "بدون عنوان" }, 120)}")
            appendLine("أُنشئت من Lion AI في $now. المحادثة السابقة توقفت أو انتهت، وهذه حزمة سياق لمتابعتها هنا.")
            appendLine()
            appendLine("## تعليمات لك (الجهة المستقبِلة)")
            appendLine("- اقرأ الحزمة كاملة قبل الرد، وعدّ ما فيها واقعاً سابقاً؛ لا تُعد خطوات ذُكر أنها نجحت.")
            appendLine("- لا تفترض أن لديك أدوات أو أذونات المحادثة السابقة؛ أي إذن يمنحه المستخدم هنا فقط.")
            appendLine("- لا تدّعِ نجاح شيء لم تذكر الحزمة أنه نجح، ولا تختلق نتائج.")
            appendLine("- ابدأ بسطر واحد يلخص فهمك للنقطة الحالية، ثم تابع من حيث توقفنا.")
            appendLine()
            appendLine("## الهدف الأصلي")
            appendLine(if (goal.isBlank()) "(غير متاح)" else cut(goal, 700))
            appendLine()
            appendLine("## الحالة وملخص التنفيذ")
            appendLine(if (digest.isBlank()) "محادثة نصية بلا جلسة أدوات؛ الحالة تُستخلص من الرسائل أدناه." else cut(digest, 5_000))
            appendLine()
            appendLine("## آخر النقاط (آخر ${recent.size} رسائل)")
            recent.forEach { (user, text) ->
                appendLine((if (user) "👤 المستخدم: " else "🦁 المساعد: ") + cut(text, 1_200))
                appendLine()
            }
        }
        val tail = buildString {
            appendLine("## ما أريده الآن")
            appendLine("(اكتب هنا ما تريد متابعته)")
            append(END)
        }
        // السجل المختصر يأخذ ما تبقى من السقف؛ نُسقط الأقدم أولاً.
        val room = (maxChars - head.length - tail.length - 80).coerceAtLeast(0)
        val older = turns.dropLast(recent.size)
        val lines = older.mapIndexed { i, (user, text) ->
            "${i + 1}. " + (if (user) "م: " else "ذ: ") + cut(text.replace('\n', ' '), 220)
        }
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length + 1 > room) break
            kept.addFirst(line); used += line.length + 1
        }
        val log = buildString {
            appendLine("## سجل مختصر للرسائل الأقدم (م = المستخدم، ذ = المساعد)")
            if (lines.isEmpty()) appendLine("(لا رسائل أقدم)")
            else {
                if (kept.size < lines.size) appendLine("(حُذفت أقدم ${lines.size - kept.size} رسالة لضيق الحجم)")
                kept.forEach { appendLine(it) }
            }
            appendLine()
        }
        return head + log + tail
    }
}

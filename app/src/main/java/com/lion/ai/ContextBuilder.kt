package com.lion.ai

import org.json.JSONObject

/**
 * طبقة تنسيق السياق (Context Orchestration Layer) — مستقلة عن أندرويد وقابلة للاختبار.
 *
 * بدل إرسال سجل المحادثة بحجمه، يُبنى السياق المرسل لأي مزود من طبقتين متغيرتين فوق
 * البادئة الثابتة (system prompt + الأدوات) التي لا تتغير داخل الجلسة:
 *
 *  1) ملخص حالة متدحرج [State]: يُحدَّث على دفعات من [SUMMARY_STEP] رسائل فقط،
 *     فيبقى نصه ثابتاً بين دفعتين (يخدم التخزين المؤقت للبادئة عند Gemini/DeepSeek).
 *     الملخص استخلاصي (بلا استدعاء نموذج) فلا يستهلك رموزاً ولا يتعطل بتعطل مزود.
 *  2) نافذة منزلقة: آخر [WINDOW_TURNS] رسائل على الأقل بنصها (مع قصّ الضخم منها).
 *
 * والملخص مشترك بين المزودين: إن تبدّل المزود أو عمل أكثر من مزود على المهمة نفسها
 * يستلم كلٌّ منهم الحالة نفسها فيكمل من حيث توقف الآخر.
 */
internal object ContextBuilder {
    const val WINDOW_TURNS = 6
    const val SUMMARY_STEP = 5
    const val SUMMARY_MAX_CHARS = 1_400
    private const val OLD_TURN_MAX = 1_800
    private const val RECENT_TURN_MAX = 6_000
    private const val TOTAL_SOFT_MAX = 16_000
    private const val SQUEEZED_TURN_MAX = 600
    private const val FILES_KEEP = 12
    private const val FILES_PREFIX = "- ملفات: "

    private val THINK_BLOCK = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
    private val THINK_DANGLING = Regex("(?s)<think(?:ing)?>.*$")
    private val PATH_RE = Regex("[\\w./-]+\\.(?:kt|kts|java|py|js|ts|tsx|json|xml|ya?ml|md|gradle|sh)\\b")
    private val SPACES = Regex("\\s+")

    /** يحذف وسوم التفكير الداخلي (حتى غير المغلقة) ويبقي الجواب النهائي فقط. */
    fun pruneThinking(text: String): String =
        text.replace(THINK_BLOCK, "").replace(THINK_DANGLING, "").trim()

    /** ملخص متدحرج: [covered] عدد الرسائل الأقدم المطوية فيه، و[text] نصه. */
    class State(val covered: Int, val text: String) {
        fun toJson(): String = JSONObject().put("c", covered).put("t", text).toString()

        companion object {
            fun parse(raw: String?): State? {
                if (raw.isNullOrBlank()) return null
                return runCatching {
                    val o = JSONObject(raw)
                    State(o.optInt("c", 0), o.optString("t", ""))
                }.getOrNull()?.takeIf { it.covered > 0 && it.text.isNotBlank() }
            }
        }
    }

    class Built(val turns: List<ChatTurn>, val state: State?)

    /**
     * يبني السجل المرسل من كل رسائل المحادثة ([all]) والملخص السابق ([previous]).
     * [Built.state] هو الملخص الواجب حفظه للمرة القادمة (null = لا حاجة لملخص).
     */
    fun build(all: List<ChatTurn>, previous: State?): Built {
        val cleaned = all.map { ChatTurn(it.role, if (it.role == "assistant") pruneThinking(it.content) else it.content) }
            .filter { it.content.isNotBlank() }
        val n = cleaned.size
        val olderCount = n - WINDOW_TURNS
        val target = if (olderCount > 0) (olderCount / SUMMARY_STEP) * SUMMARY_STEP else 0
        if (target <= 0) return Built(capTurns(cleaned), null)

        val usable = previous?.takeIf { it.covered in 1..target }
        val state = when {
            usable == null -> State(target, fold("", cleaned.subList(0, target)))
            usable.covered == target -> usable
            else -> State(target, fold(usable.text, cleaned.subList(usable.covered, target)))
        }
        val raw = capTurns(cleaned.subList(target, n))
        val block = "<summary>\n" + state.text + "\n</summary>"
        val turns = if (raw.isNotEmpty() && raw[0].role == "user") {
            listOf(ChatTurn("user", block + "\n\n" + raw[0].content)) + raw.drop(1)
        } else {
            listOf(ChatTurn("user", block)) + raw
        }
        return Built(turns, state)
    }

    private fun capTurns(turns: List<ChatTurn>): List<ChatTurn> {
        if (turns.isEmpty()) return turns
        val recentFrom = (turns.size - 2).coerceAtLeast(0)
        var capped = turns.mapIndexed { i, t ->
            val limit = if (i >= recentFrom) RECENT_TURN_MAX else OLD_TURN_MAX
            if (t.content.length > limit) ChatTurn(t.role, LionContext.truncateMiddle(t.content, limit)) else t
        }
        if (capped.sumOf { it.content.length } > TOTAL_SOFT_MAX) {
            capped = capped.mapIndexed { i, t ->
                if (i < recentFrom && t.content.length > SQUEEZED_TURN_MAX)
                    ChatTurn(t.role, LionContext.truncateMiddle(t.content, SQUEEZED_TURN_MAX)) else t
            }
        }
        return capped
    }

    /** يطوي رسائل قديمة في الملخص: طلبات المستخدم ونتائج المساعد والملفات المذكورة. */
    fun fold(previous: String, turns: List<ChatTurn>): String {
        val lines = ArrayList<String>()
        val files = LinkedHashSet<String>()
        for (line in previous.lines()) {
            if (line.isBlank()) continue
            if (line.startsWith(FILES_PREFIX)) {
                line.removePrefix(FILES_PREFIX).split("،").map { it.trim() }.filter { it.isNotEmpty() }.forEach { files.add(it) }
            } else lines.add(line)
        }
        for (t in turns) {
            val clean = pruneThinking(t.content).replace(SPACES, " ").trim()
            if (clean.isEmpty()) continue
            PATH_RE.findAll(clean).take(6).forEach { files.add(it.value) }
            if (t.role == "user") lines.add("- طلب: " + clean.take(140))
            else lines.add("- نتيجة: " + firstSentence(clean, 140))
        }
        fun render(): String {
            val shownFiles = files.toList().takeLast(FILES_KEEP)
            return (lines + if (shownFiles.isEmpty()) emptyList() else listOf(FILES_PREFIX + shownFiles.joinToString("، ")))
                .joinToString("\n")
        }
        var out = render()
        while (out.length > SUMMARY_MAX_CHARS && lines.size > 4) {
            lines.removeAt(0)
            out = render()
        }
        return if (out.length > SUMMARY_MAX_CHARS) out.takeLast(SUMMARY_MAX_CHARS) else out
    }

    private fun firstSentence(text: String, max: Int): String {
        val head = text.take(max)
        val cut = head.indexOfAny(charArrayOf('.', '!', '؟', '?', '。', '\n'))
        return if (cut in 20 until head.length) head.substring(0, cut + 1) else head
    }

    /**
     * قراءة موضعية: الأسطر [start]..[end] (ترقيم من 1، شاملة) مع أرقامها وإجمالي الأسطر.
     * بلا نطاق يعيد null ليبقى سلوك القراءة الكاملة كما هو.
     */
    /**
     * قراءة مُصفَّحة بلا قطع صامت: تُقطع على حدود الأسطر الكاملة، والعنوان يعكس ما عُرض فعلاً،
     * والتذييل يقول أين توقفت وبأي استدعاء تتابع (لا يعتقد المزود أنه قرأ ما لم يُعرض).
     * بلا نطاق: من السطر 1 حتى سقف الحجم. نطاق بلا نهاية: 200 سطر.
     */
    fun pageLines(label: String, text: String, start: Int, end: Int, maxChars: Int, continueWith: String): String {
        val lines = text.lines()
        val total = lines.size
        val s = start.coerceAtLeast(1)
        if (s > total) return "$label — الملف فيه $total سطراً فقط (طلبت البدء من $s)."
        val wanted = when {
            end > 0 -> end.coerceIn(s, total)
            start > 0 -> (s + 199).coerceAtMost(total)
            else -> total
        }
        val sb = StringBuilder()
        var shown = s - 1
        var chars = 0
        for (i in s..wanted) {
            var line = "$i: ${lines[i - 1]}"
            if (line.length > maxChars) line = line.take(maxChars) + " …[سطر طويل جداً اقتُطع]"
            if (chars + line.length + 1 > maxChars && shown >= s) break
            sb.append(line).append('\n')
            chars += line.length + 1
            shown = i
        }
        val head = "$label (الأسطر $s–$shown من $total)"
        val tail = when {
            shown < wanted -> "\n\n⚠️ لم تُعرض كل الأسطر المطلوبة ($s–$wanted) بسبب الحجم؛ عُرض حتى السطر $shown فقط. " +
                "تابع بـ $continueWith start_line=${shown + 1} (أو max_chars أكبر)."
            shown < total -> "\n\n[بقي ${total - shown} سطراً بعد $shown. للمتابعة: $continueWith start_line=${shown + 1}. " +
                "للبحث عن كلمة داخل الملف دون قراءته كاملاً استخدم file_search.]"
            else -> "\n\n[نهاية الملف]"
        }
        return head + "\n\n" + sb.toString().trimEnd() + tail
    }

    fun sliceLines(label: String, text: String, start: Int, end: Int, maxChars: Int = 15_000): String? {
        if (start <= 0 && end <= 0) return null
        val lines = text.lines()
        val s = start.coerceAtLeast(1)
        if (s > lines.size) return "$label — الملف فيه ${lines.size} سطراً فقط (طلبت البدء من $s)."
        val e = (if (end > 0) end else s + 199).coerceIn(s, lines.size)
        val body = (s..e).joinToString("\n") { "$it: ${lines[it - 1]}" }
        return "$label (الأسطر $s–$e من ${lines.size})\n\n" + body.take(maxChars)
    }
}

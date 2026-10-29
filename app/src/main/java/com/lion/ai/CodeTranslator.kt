package com.lion.ai

/**
 * ترجمة الأكواد بين اللغات عبر النموذج النشط نفسه.
 * الأداة تتحقق من المدخلات، تبني تعليمات صارمة، ثم تستخرج كتلة الكود من الرد
 * حتى لا تُحفظ شروحات أو علامات Markdown داخل الملف الناتج.
 */
object CodeTranslator {

    private const val MAX_SOURCE_CHARS = 60_000

    private val FENCED = Regex("```[\\w+#.\\-]*\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private val ALIASES = mapOf(
        "kotlin" to "Kotlin", "كوتلن" to "Kotlin", "كوتلين" to "Kotlin",
        "java" to "Java", "جافا" to "Java",
        "swift" to "Swift", "سويفت" to "Swift",
        "dart" to "Dart", "دارت" to "Dart",
        "python" to "Python", "بايثون" to "Python", "بيثون" to "Python", "py" to "Python",
        "javascript" to "JavaScript", "جافاسكريبت" to "JavaScript", "جافا سكريبت" to "JavaScript", "js" to "JavaScript",
        "typescript" to "TypeScript", "تايبسكريبت" to "TypeScript", "تايب سكريبت" to "TypeScript", "ts" to "TypeScript",
        "c#" to "C#", "csharp" to "C#", "سي شارب" to "C#", "سيشارب" to "C#",
        "c++" to "C++", "cpp" to "C++", "سي بلس بلس" to "C++",
        "c" to "C", "سي" to "C",
        "go" to "Go", "golang" to "Go", "جو" to "Go",
        "rust" to "Rust", "رست" to "Rust",
        "ruby" to "Ruby", "روبي" to "Ruby", "rb" to "Ruby",
        "php" to "PHP", "بي إتش بي" to "PHP", "بي اتش بي" to "PHP",
        "scala" to "Scala", "سكالا" to "Scala",
        "html" to "HTML", "css" to "CSS", "sql" to "SQL",
        "json" to "JSON", "yaml" to "YAML", "xml" to "XML"
    )

    private val EXTENSIONS = mapOf(
        "Kotlin" to "kt", "Java" to "java", "Swift" to "swift", "Dart" to "dart",
        "Python" to "py", "JavaScript" to "js", "TypeScript" to "ts",
        "C#" to "cs", "C++" to "cpp", "C" to "c", "Go" to "go", "Rust" to "rs",
        "Ruby" to "rb", "PHP" to "php", "Scala" to "scala",
        "HTML" to "html", "CSS" to "css", "SQL" to "sql",
        "JSON" to "json", "YAML" to "yml", "XML" to "xml"
    )

    private val BY_EXTENSION = EXTENSIONS.entries.associate { (language, ext) -> ext to language }

    fun canonical(target: String): String =
        ALIASES[target.trim().lowercase()] ?: target.trim().replaceFirstChar { it.uppercase() }

    fun extensionFor(target: String): String = EXTENSIONS[canonical(target)] ?: "txt"

    fun languageOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return BY_EXTENSION[ext] ?: ext.ifBlank { "نص" }
    }

    /** يستخرج أكبر كتلة كود من رد النموذج، أو يعيد الرد منزوع الأغلفة إن لم توجد كتلة. */
    fun extractCode(raw: String): String {
        val blocks = FENCED.findAll(raw).map { it.groupValues[1].trim('\n') }.toList()
        val body = if (blocks.isNotEmpty()) {
            blocks.maxByOrNull { it.length }.orEmpty()
        } else {
            raw.trim().removePrefix("```").removeSuffix("```").trim('\n')
        }
        return body.replace("\r\n", "\n").trim('\n')
    }

    fun outputPath(sourcePath: String, target: String): String {
        if (sourcePath.isBlank()) return "translated.${extensionFor(target)}"
        val dir = sourcePath.substringBeforeLast('/', "")
        val name = sourcePath.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val sourceLanguage = languageOf(name)
        val suffix = if (sourceLanguage.equals(canonical(target), ignoreCase = true)) ".translated" else ""
        val newName = "$base$suffix.${extensionFor(target)}"
        return if (dir.isBlank()) newName else "$dir/$newName"
    }

    internal fun prompt(target: String, sourceName: String, notes: String): String {
        val language = canonical(target)
        return buildString {
            append("أنت مترجم أكواد دقيق ومحترف. حوّل الكود المرفق إلى $language.\n\n")
            append("قواعد إلزامية:\n")
            append("1) ترجم الكود كاملاً، لا تختصر أو تحذف أجزاء بحجة التشابه.\n")
            append("2) حافظ على السلوك والمدخلات والمخرجات ومعالجة الأخطاء كما هي.\n")
            append("3) استخدم اصطلاحات $language القياسية، وأعد تسمية الرموز فقط عند الضرورة.\n")
            append("4) لا تخترع مكتبات خارجية غير موجودة في المصدر؛ إن كانت المكتبة غير متاحة اشرح ذلك بتعليق داخل الكود.\n")
            append("5) أخرج كتلة كود واحدة فقط داخل ``` بلا أي شرح قبلها أو بعدها.\n")
            if (notes.isNotBlank()) append("6) ملاحظات إضافية من المستخدم: $notes\n")
            append("\nاسم الملف الأصلي: ").append(sourceName.ifBlank { "code" })
            append("\nاللغة الهدف: ").append(language)
            append("\n")
        }
    }

    fun translate(
        ctx: ToolContext,
        source: String,
        sourceName: String,
        target: String,
        notes: String = ""
    ): String {
        if (source.isBlank()) return "⚠️ لا يوجد كود للترجمة."
        if (target.isBlank()) return "⚠️ حدّد اللغة الهدف (مثل Kotlin أو Python أو TypeScript)."
        if (source.length > MAX_SOURCE_CHARS) {
            return "⚠️ الملف أكبر من ${MAX_SOURCE_CHARS / 1000} ألف حرف. قسّمه إلى ملفات أصغر ثم ترجم كل جزء."
        }
        if (ctx.provider.isBlank()) {
            return "⚠️ لا يوجد مزود ذكاء اصطناعي نشط. اختر مزوداً من الإعدادات ثم أعد المحاولة."
        }
        val language = canonical(target)
        val reply = LlmClient.ask(
            message = "حوّل الكود التالي إلى $language:\n\n```\n$source\n```",
            apiKey = ctx.apiKey,
            provider = ctx.provider,
            history = emptyList(),
            modelOverride = ctx.model,
            accountId = ctx.accountId,
            systemPrompt = prompt(language, sourceName, notes),
            readTimeoutMs = 90_000
        )
        if (LlmClient.isErrorReply(reply)) return reply
        val code = extractCode(reply)
        if (code.isBlank()) return "⚠️ لم يُرجع النموذج كوداً قابلاً للاستخراج. أعد المحاولة أو غيّر المزود."
        return code
    }
}

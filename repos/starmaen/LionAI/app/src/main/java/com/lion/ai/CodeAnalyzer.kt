package com.lion.ai

/** محلل كود محلي بلا اتصال: مقاييس، تعقيد تقريبي، TODO، اعتماديات، وملاحظات. */
object CodeAnalyzer {
    data class Issue(val line: Int, val message: String)

    data class FunctionInfo(
        val name: String,
        val line: Int,
        val isAsync: Boolean = false,
        val isStatic: Boolean = false,
        val parameters: String = ""
    )

    data class ClassInfo(
        val name: String,
        val line: Int,
        val parent: String = "",
        val isAbstract: Boolean = false
    )

    data class ImportInfo(val path: String, val line: Int)

    data class Result(
        val name: String,
        val language: String,
        val lines: Int,
        val codeLines: Int,
        val blankLines: Int,
        val commentLines: Int,
        val functions: Int,
        val classes: Int,
        val complexity: Int,
        val todos: List<Issue>,
        val dependencies: List<String>,
        val issues: List<Issue>,
        val duplicateLines: Int,
        val functionList: List<FunctionInfo> = emptyList(),
        val classList: List<ClassInfo> = emptyList(),
        val importList: List<ImportInfo> = emptyList(),
        val fileSize: Int = 0
    )

    private val EXTENSIONS = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java", "py" to "Python",
        "js" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript",
        "ts" to "TypeScript", "tsx" to "TypeScript", "jsx" to "JavaScript",
        "c" to "C", "h" to "C", "cpp" to "C++", "cc" to "C++", "hpp" to "C++",
        "cs" to "C#", "go" to "Go", "rs" to "Rust", "rb" to "Ruby", "php" to "PHP",
        "swift" to "Swift", "dart" to "Dart", "sh" to "Shell", "bash" to "Shell",
        "sql" to "SQL", "html" to "HTML", "css" to "CSS", "scss" to "CSS",
        "json" to "JSON", "xml" to "XML", "yaml" to "YAML", "yml" to "YAML",
        "gradle" to "Gradle", "md" to "Markdown", "txt" to "نص"
    )

    private val TODO = Regex("(?i)//\\s*(TODO|FIXME|HACK|XXX|BUG|NOTE)\\b[: ]*(.*)|#\\s*(TODO|FIXME|HACK|XXX|BUG|NOTE)\\b[: ]*(.*)|/\\*\\s*(TODO|FIXME|HACK|XXX|BUG|NOTE)\\b[: ]*(.*)")
    private val DECISIONS = Regex("\\b(if|for|while|when|case|catch|switch|elif)\\b|&&|\\|\\||\\?:")
    private val SECRET = Regex("(?i)(api[_-]?key|secret|token|password|passwd)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']|\\b(ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16})\\b")
    private val LONG_LINE = 120

    fun languageFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return EXTENSIONS[ext] ?: if (name.contains("Dockerfile")) "Docker" else "غير معروف"
    }

    private fun countMatches(text: String, regex: Regex): Int = regex.findAll(text).count()

    private fun functionCount(language: String, code: String): Int = when (language) {
        "Kotlin" -> countMatches(code, Regex("(?m)^\\s*(?:private |public |internal |protected |override |suspend |inline |operator |infix |tailrec |external )*fun\\s+[A-Za-z_][A-Za-z0-9_]*"))
        "Java", "C#", "C++", "C", "Dart", "Swift" -> countMatches(code, Regex("(?m)^\\s*(?:public |private |protected |static |final |virtual |override |async |suspend )*[A-Za-z_][A-Za-z0-9_<>\\[\\], ?]*\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^;]*\\)\\s*\\{"))
        "Python" -> countMatches(code, Regex("(?m)^\\s*(?:async\\s+)?def\\s+[A-Za-z_][A-Za-z0-9_]*"))
        "JavaScript", "TypeScript" -> countMatches(code, Regex("(?m)(?:function\\s+[A-Za-z_][A-Za-z0-9_]*|(?:const|let|var)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>|(?:const|let|var)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*(?:async\\s*)?function)"))
        "Go" -> countMatches(code, Regex("(?m)^\\s*func\\s+[A-Za-z_]"))
        "Rust" -> countMatches(code, Regex("(?m)^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+[A-Za-z_]"))
        "PHP" -> countMatches(code, Regex("(?mi)(?:public\\s+|private\\s+|protected\\s+|static\\s+)*function\\s+[A-Za-z_]"))
        "Ruby" -> countMatches(code, Regex("(?m)^\\s*def\\s+[A-Za-z_]"))
        "Shell" -> countMatches(code, Regex("(?m)^\\s*(?:function\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*\\)\\s*\\{"))
        else -> 0
    }

    private fun classCount(language: String, code: String): Int = when (language) {
        "JSON", "XML", "YAML", "Markdown", "نص", "SQL", "HTML", "CSS", "Gradle" -> 0
        else -> countMatches(code, Regex("(?m)\\b(class|interface|enum|object|record|struct|trait)\\s+[A-Za-z_]"))
    }

    private fun commentPrefixes(language: String): List<String> = when (language) {
        "Python", "Ruby", "Shell", "YAML" -> listOf("#")
        "SQL" -> listOf("--", "#")
        "HTML", "XML" -> listOf("<!--")
        "CSS" -> listOf("/*", "//")
        else -> listOf("//", "/*", "*")
    }

    private fun dependencyLines(language: String, lines: List<String>): List<String> {
        val patterns = when (language) {
            "Kotlin", "Java", "Gradle" -> listOf(Regex("^\\s*import\\s+"), Regex("^\\s*(implementation|api|compileOnly|runtimeOnly|kapt|ksp)\\s*\\("))
            "Python" -> listOf(Regex("^\\s*(import|from)\\s+"))
            "JavaScript", "TypeScript" -> listOf(Regex("^\\s*import\\s+"), Regex("require\\s*\\("))
            "C", "C++" -> listOf(Regex("^\\s*#\\s*include"))
            "C#" -> listOf(Regex("^\\s*using\\s+"))
            "Go" -> listOf(Regex("^\\s*\"[^\"]+\"\\s*$"), Regex("^\\s*import"))
            "Rust" -> listOf(Regex("^\\s*use\\s+"))
            "PHP" -> listOf(Regex("^\\s*(use|require|include)\\s"))
            "Ruby" -> listOf(Regex("^\\s*require"))
            else -> emptyList()
        }
        if (patterns.isEmpty()) return emptyList()
        return lines.filter { line -> patterns.any { it.containsMatchIn(line) } }
            .map { it.trim() }
            .distinct()
            .take(80)
    }

    private val CLASS_LINE = Regex("(?i)\\b(class|interface|enum|object|record|struct|trait)\\s+([A-Za-z_][A-Za-z0-9_]*)")
    private val PARENT = Regex("(?i)(?:extends|implements|:)\\s*([A-Za-z_][A-Za-z0-9_, .<>]*)")

    private fun lineFunctions(language: String, lines: List<String>): List<FunctionInfo> {
        val regex = when (language) {
            "Kotlin" -> Regex("^\\s*(?:(?:private|public|internal|protected|override|suspend|inline|operator|infix|tailrec|external)\\s+)*fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
            "Python" -> Regex("^\\s*(async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
            "JavaScript", "TypeScript" -> Regex("(?:function\\s+([A-Za-z_][A-Za-z0-9_]*)|(?:const|let|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(async\\s*)?\\(([^)]*)\\)\\s*=>)")
            "Java", "C#", "C++", "C", "Swift", "Dart" -> Regex("^\\s*(?:(?:public|private|protected|static|final|virtual|override|async|suspend|fun|func)\\s+)*[A-Za-z_<>\\[\\], ?.]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*\\{")
            "Go" -> Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
            "Rust" -> Regex("^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
            "PHP" -> Regex("(?i)\\b(?:public|private|protected|static)?\\s*function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
            "Ruby" -> Regex("^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[\\( ]?([^)]*)\\)?")
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<FunctionInfo>()
        lines.forEachIndexed { index, line ->
            val match = regex.find(line) ?: return@forEachIndexed
            val name = match.groupValues.drop(1).firstOrNull { it.isNotBlank() && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }.orEmpty()
            if (name.isBlank() || name in setOf("if", "for", "while", "switch", "catch", "return", "new")) return@forEachIndexed
            val params = match.groupValues.lastOrNull().orEmpty()
            out.add(
                FunctionInfo(
                    name = name,
                    line = index + 1,
                    isAsync = line.contains("async", true) || line.contains("suspend", true),
                    isStatic = line.contains("static", true),
                    parameters = params.take(120)
                )
            )
        }
        return out
    }

    private fun lineClasses(language: String, lines: List<String>): List<ClassInfo> {
        if (language in setOf("JSON", "XML", "YAML", "Markdown", "نص", "SQL", "HTML", "CSS", "Gradle")) return emptyList()
        val out = mutableListOf<ClassInfo>()
        lines.forEachIndexed { index, line ->
            val match = CLASS_LINE.find(line) ?: return@forEachIndexed
            val name = match.groupValues[2]
            val parent = PARENT.find(line)?.groupValues?.get(1)?.trim().orEmpty()
            out.add(ClassInfo(name, index + 1, parent, line.contains("abstract", true)))
        }
        return out
    }

    private fun lineImports(language: String, lines: List<String>): List<ImportInfo> {
        val regex = when (language) {
            "Kotlin", "Java", "Gradle" -> Regex("^\\s*import\\s+([^;]+)")
            "Python" -> Regex("^\\s*(?:from|import)\\s+([A-Za-z0-9_.]+)")
            "JavaScript", "TypeScript" -> Regex("^\\s*(?:import\\s+.*?from\\s+[\"']([^\"']+)|.*?require\\(\\s*[\"']([^\"']+))")
            "C", "C++" -> Regex("^\\s*#\\s*include\\s*[<\"]([^>\"]+)")
            "C#" -> Regex("^\\s*using\\s+([A-Za-z0-9_.]+)")
            "Go" -> Regex("^\\s*(?:import\\s+)?(?:[A-Za-z_.]+\\s+)?\"([^\"]+)\"")
            "Rust" -> Regex("^\\s*use\\s+([^;]+)")
            "PHP" -> Regex("^\\s*(?:use|require|include)\\s*[\\(]?['\"]?([^'\";]+)")
            "Ruby" -> Regex("^\\s*require\\s+['\"]([^'\"]+)")
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<ImportInfo>()
        lines.forEachIndexed { index, line ->
            val match = regex.find(line) ?: return@forEachIndexed
            val path = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            if (path.isNotBlank()) out.add(ImportInfo(path.trim(), index + 1))
        }
        return out
    }

    fun analyze(name: String, text: String): Result {
        val language = languageFor(name)
        val lines = text.lines()
        val blank = lines.count { it.isBlank() }
        val prefixes = commentPrefixes(language)
        val comments = lines.count { line ->
            val trimmed = line.trimStart()
            trimmed.isNotEmpty() && prefixes.any { trimmed.startsWith(it) }
        }
        val code = lines.size - blank - comments
        val todo = mutableListOf<Issue>()
        lines.forEachIndexed { index, line ->
            val match = TODO.find(line) ?: return@forEachIndexed
            val label = (match.groupValues[1] + match.groupValues[3] + match.groupValues[5]).ifBlank { "TODO" }
            val message = (match.groupValues[2] + match.groupValues[4] + match.groupValues[6]).trim()
            todo.add(Issue(index + 1, "$label: $message".trim().removeSuffix(":")))
        }
        val issues = mutableListOf<Issue>()
        var sawTab = false
        var sawSpaces = false
        lines.forEachIndexed { index, line ->
            if (line.length > LONG_LINE) issues.add(Issue(index + 1, "سطر طويل (${line.length} حرفاً)"))
            if (line.endsWith(" ") || line.endsWith("\t")) issues.add(Issue(index + 1, "مسافات زائدة في نهاية السطر"))
            if (line.startsWith("\t")) sawTab = true
            if (line.startsWith("    ")) sawSpaces = true
            if (SECRET.containsMatchIn(line)) issues.add(Issue(index + 1, "احتمال وجود سر/مفتاح مكتوب داخل الكود"))
            if (Regex("(?i)console\\.log\\s*\\(|\\bprint\\s*\\(").containsMatchIn(line) && language in setOf("JavaScript", "TypeScript", "Python", "Kotlin")) {
                issues.add(Issue(index + 1, "طباعة تشخيصية قد تُترك في الإنتاج"))
            }
        }
        if (sawTab && sawSpaces) issues.add(Issue(0, "خلط بين Tab والمسافات في الإزاحة"))
        val normalized = lines.map { it.trim() }.filter { it.length > 12 && !it.startsWith("//") && !it.startsWith("#") }
        val duplicates = normalized.groupingBy { it }.eachCount().values.count { it > 1 }
        if (lines.size > 500) issues.add(Issue(0, "الملف كبير (${lines.size} سطراً) — يُفضّل تقسيمه"))
        val complexity = 1 + countMatches(text, DECISIONS)
        val functionList = lineFunctions(language, lines)
        val classList = lineClasses(language, lines)
        val importList = lineImports(language, lines)
        return Result(
            name = name,
            language = language,
            lines = lines.size,
            codeLines = code.coerceAtLeast(0),
            blankLines = blank,
            commentLines = comments,
            functions = functionCount(language, text),
            classes = classCount(language, text),
            complexity = complexity,
            todos = todo,
            dependencies = dependencyLines(language, lines),
            issues = issues.take(100),
            duplicateLines = duplicates,
            functionList = functionList,
            classList = classList,
            importList = importList,
            fileSize = text.length
        )
    }

    fun report(result: Result, maxIssues: Int = 20): String = buildString {
        append("🔍 تحليل: ").append(result.name).append(" (").append(result.language).append(")\n")
        append("• الأسطر: ").append(result.lines)
        append(" | كود: ").append(result.codeLines)
        append(" | فارغ: ").append(result.blankLines)
        append(" | تعليقات: ").append(result.commentLines).append('\n')
        append("• الدوال: ").append(result.functions)
        append(" | الكلاسات/الكائنات: ").append(result.classes)
        append(" | التعقيد التقريبي: ").append(result.complexity)
        val avg = if (result.functions > 0) "%.1f".format(result.complexity.toDouble() / result.functions) else "—"
        append(" | متوسط التعقيد/دالة: ").append(avg).append('\n')
        if (result.todos.isNotEmpty()) {
            append("\n📌 TODO/FIXME (").append(result.todos.size).append("):\n")
            result.todos.take(maxIssues).forEach { append("  - سطر ").append(it.line).append(": ").append(it.message).append('\n') }
        }
        if (result.dependencies.isNotEmpty()) {
            append("\n📦 الاعتماديات/الاستيرادات (").append(result.dependencies.size).append("):\n")
            result.dependencies.take(25).forEach { append("  - ").append(it).append('\n') }
        }
        if (result.issues.isNotEmpty()) {
            append("\n⚠️ ملاحظات (").append(result.issues.size).append("):\n")
            result.issues.take(maxIssues).forEach {
                append("  - ").append(if (it.line > 0) "سطر ${it.line}: " else "").append(it.message).append('\n')
            }
        }
        if (result.duplicateLines > 0) append("\n♻️ أسطر مكرّرة: ").append(result.duplicateLines).append('\n')
        if (result.functionList.isNotEmpty()) {
            append("\n🧩 الدوال (").append(result.functionList.size).append("):\n")
            result.functionList.take(30).forEach {
                append("  - ").append(it.name).append("() سطر ").append(it.line)
                if (it.isAsync) append(" [async]")
                if (it.isStatic) append(" [static]")
                append('\n')
            }
        }
        if (result.classList.isNotEmpty()) {
            append("\n🏛️ الكلاسات (").append(result.classList.size).append("):\n")
            result.classList.take(20).forEach {
                append("  - ").append(it.name)
                if (it.parent.isNotBlank()) append(" extends ").append(it.parent)
                append(" سطر ").append(it.line).append('\n')
            }
        }
    }.trim()

    fun projectReport(files: List<Pair<String, String>>): String = buildString {
        append("🗂️ تحليل مشروع (").append(files.size).append(" ملف)\n")
        val results = files.map { (name, text) -> analyze(name, text) }
        val totalLines = results.sumOf { it.lines }
        val totalFunctions = results.sumOf { it.functions }
        val totalComplexity = results.sumOf { it.complexity }
        append("• إجمالي الأسطر: ").append(totalLines)
        append(" | الدوال: ").append(totalFunctions)
        append(" | التعقيد: ").append(totalComplexity).append("\n")
        append("• اللغات: ").append(results.groupingBy { it.language }.eachCount().entries.sortedByDescending { it.value }.joinToString("، ") { "${it.key} (${it.value})" }).append("\n")
        val worst = results.sortedByDescending { it.complexity }.take(8)
        if (worst.isNotEmpty()) {
            append("\nأعلى الملفات تعقيداً:\n")
            worst.forEach { append("  - ").append(it.name).append(": ").append(it.complexity).append(" (").append(it.lines).append(" سطر)\n") }
        }
        val todos = results.flatMap { r -> r.todos.map { r.name to it } }
        if (todos.isNotEmpty()) {
            append("\nTODO/FIXME (").append(todos.size).append("):\n")
            todos.take(20).forEach { (name, issue) -> append("  - ").append(name).append(":").append(issue.line).append(" ").append(issue.message).append('\n') }
        }
    }.trim()
}

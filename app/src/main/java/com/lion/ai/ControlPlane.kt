package com.lion.ai

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ManifestFile(
    val path: String,
    val purpose: String = "",
    val contract: String = "",
    val required: Boolean = true,
    val entry: Boolean = false,
    val status: String = "pending"
)

data class ManifestTask(
    val id: String,
    val role: String,
    val title: String,
    val dependsOn: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val status: String = "pending"
)

data class BuildManifest(
    val project: String,
    val root: String = "",
    val entryPoints: List<String> = emptyList(),
    val files: List<ManifestFile> = emptyList(),
    val tasks: List<ManifestTask> = emptyList(),
    val approved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun file(path: String): ManifestFile? =
        files.firstOrNull { ManifestPaths.normalize(it.path) == ManifestPaths.normalize(path) }

    fun requiredPaths(): List<String> = files.filter { it.required }.map { ManifestPaths.normalize(it.path) }
}


data class ProjectSnapshot(
    val files: Map<String, String>,
    val directories: Set<String> = emptySet(),
    val emptyDirectories: Set<String> = emptySet()
) {
    fun has(path: String): Boolean = files.containsKey(ManifestPaths.normalize(path))
    fun text(path: String): String = files[ManifestPaths.normalize(path)].orEmpty()
    fun paths(): Set<String> = files.keys
}

data class GateIssue(
    val severity: String,
    val code: String,
    val path: String = "",
    val line: Int = 0,
    val message: String
) {
    fun lineText(): String = buildString {
        append(if (severity == "error") "❌" else "⚠️")
        append(' ').append(code)
        if (path.isNotBlank()) {
            append(" · ").append(path)
            if (line > 0) append(':').append(line)
        }
        append(" — ").append(message)
    }
}

data class GateReport(
    val ok: Boolean,
    val issues: List<GateIssue>,
    val checkedFiles: Int = 0,
    val reachable: Int = 0,
    val totalSource: Int = 0,
    val emptyDirectories: Int = 0
) {
    fun errors(): List<GateIssue> = issues.filter { it.severity == "error" }

    fun text(): String = buildString {
        append(if (ok) "✅ اجتازت البوابة" else "⛔ فشلت البوابة")
        append(" — ملفات: ").append(checkedFiles)
        append(" | مصادر: ").append(reachable).append('/').append(totalSource)
        if (emptyDirectories > 0) append(" | مجلدات فارغة: ").append(emptyDirectories)
        append('\n')
        issues.take(80).forEach { append(it.lineText()).append('\n') }
        if (issues.size > 80) append("… و").append(issues.size - 80).append(" ملاحظة أخرى\n")
    }.trimEnd()
}

data class ReachabilityResult(
    val entryPoints: Set<String>,
    val reachable: Set<String>,
    val orphans: Set<String>,
    val totalSource: Int
)

object ManifestPaths {
    fun normalize(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").trim('/')

    fun join(root: String, path: String): String {
        val r = normalize(root)
        val p = normalize(path)
        return if (r.isBlank() || p.startsWith(r + "/")) p else "$r/$p"
    }
}

/** بوابة ميكانيكية لا تعتمد على كلام النموذج: تفحص الملفات والبنية والاختبارات. */
object ControlPlane {
    private val SOURCE_EXT = setOf(
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "c", "cpp", "cc", "h", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "dart", "scala", "lua", "sh"
    )
        private val CODE_EXT = SOURCE_EXT + setOf("gradle", "xml", "json", "yml", "yaml", "html", "css")

    /** لغات يبدأ فيها التعليق بـ `#` — حتى بعد مسافات بادئة (Python/Shell/Ruby). */
    private val HASH_COMMENT_EXT = setOf("py", "sh", "rb")
    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", "build", "out", "node_modules", ".idea", ".lion-tmp", ".and-code"
    )
    private val TEST_MARKERS = listOf(
        "/src/test/", "/src/androidTest/", "/test/", "/tests/", "/__tests__/"
    )

        // تُبنى من أجزاء كي لا يظهر النمط نفسه في المصدر فيُبلَّغ عنه كعنصر نائب.
    private val PLACEHOLDER = Regex(
        listOf(
            "\\bTODO\\s*\\(",
            "Not" + "ImplementedError",
            "not\\s+implemented",
            "Unsupported" + "OperationException",
                        "<place" + "holder>",
            "coming\\s+soon",
            "قيد\\s+الإنشاء",
            "لم\\s+يتم\\s+التنفيذ"
        ).joinToString("|", "(", ")"),
        RegexOption.IGNORE_CASE
    )

    /**
     * يستبدل محتوى السلاسل والتعليقات بمسافات مع الحفاظ على أرقام الأسطر،
          * كي لا تُعدّ كلمة مثل اسم الاستثناء داخل نص أو تعليق «كوداً ناقصاً».
     */
    fun stripLiterals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var state = 0 // 0 code, 1 line comment, 2 block comment, 3 single, 4 double, 5 triple, 6 backtick
        var tripleChar = '"'
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { out.append("  "); i++; state = 1 }
                    c == '/' && next == '*' -> { out.append("  "); i++; state = 2 }
                    c == '\'' -> { out.append(' '); state = 3 }
                    c == '"' -> {
                        if (next == '"' && text.getOrNull(i + 2) == '"') {
                            out.append("   "); i += 2; state = 5; tripleChar = '"'
                        } else { out.append(' '); state = 4 }
                    }
                    c == '`' -> { out.append(' '); state = 6 }
                    else -> out.append(c)
                }
                1 -> if (c == '\n') { out.append('\n'); state = 0 } else out.append(' ')
                2 -> if (c == '*' && next == '/') { out.append("  "); i++; state = 0 }
                     else out.append(if (c == '\n') '\n' else ' ')
                3 -> when {
                    c == '\n' -> { out.append('\n'); state = 0 }
                    c == '\\' -> { out.append("  "); i++ }
                    c == '\'' -> { out.append(' '); state = 0 }
                    else -> out.append(' ')
                }
                4 -> when {
                    c == '\n' -> { out.append('\n'); state = 0 }
                    c == '\\' -> { out.append("  "); i++ }
                    c == '"' -> { out.append(' '); state = 0 }
                    else -> out.append(' ')
                }
                5 -> if (c == tripleChar && next == tripleChar && text.getOrNull(i + 2) == tripleChar) {
                    out.append("   "); i += 2; state = 0
                } else out.append(if (c == '\n') '\n' else ' ')
                6 -> when {
                    c == '\\' -> { out.append("  "); i++ }
                    c == '`' -> { out.append(' '); state = 0 }
                    else -> out.append(if (c == '\n') '\n' else ' ')
                }
            }
            i++
        }
        return out.toString()
    }

    fun isSource(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in SOURCE_EXT

    fun isTest(path: String): Boolean {
        val p = "/" + ManifestPaths.normalize(path).lowercase() + "/"
        if (TEST_MARKERS.any { p.contains(it) }) return true
        val name = path.substringAfterLast('/')
        return name.contains(".test.") || name.contains("_test.") || name.endsWith("Test.kt") ||
            name.endsWith("Test.java") || name.startsWith("test_")
    }

    fun isIgnored(path: String): Boolean {
        val parts = ManifestPaths.normalize(path).split('/')
        return parts.any { it in IGNORED_DIRS }
    }

    /**
     * فحص كامل: Manifest + وجود الملفات + المحتوى غير الفارغ + lint/typecheck مبسّط
     * + Root Reachability + سلامة الاختبارات عبر HMAC.
     */
    fun evaluate(
        snapshot: ProjectSnapshot,
        manifest: BuildManifest?,
        signatures: Map<String, String> = emptyMap(),
        hmacKey: String = TestIntegrity.keyMarker
    ): GateReport {
        val issues = mutableListOf<GateIssue>()
        if (manifest == null) {
            issues.add(GateIssue("error", "NO_MANIFEST", message = "لا يوجد Manifest معتمد لهذه المحادثة/المشروع"))
        } else {
            manifest.files.forEach { file ->
                val path = ManifestPaths.join(manifest.root, file.path)
                if (!snapshot.has(path)) {
                    issues.add(
                        GateIssue(
                            "error", "MISSING_FILE", path,
                            message = "ملف مطلوب في Manifest غير موجود" +
                                if (file.purpose.isNotBlank()) " — ${file.purpose}" else ""
                        )
                    )
                } else if (snapshot.text(path).isBlank()) {
                    issues.add(GateIssue("error", "EMPTY_FILE", path, message = "ملف مطلوب لكنه فارغ"))
                }
            }
        }

        snapshot.emptyDirectories.forEach { dir ->
            if (!isIgnored(dir)) {
                issues.add(GateIssue("error", "EMPTY_DIR", dir, message = "مجلد فارغ في التسليم"))
            }
        }

        val checks = lintAndTypecheck(snapshot)
        issues.addAll(checks)

        val entries = buildEntryPoints(snapshot, manifest)
        val reach = reachability(snapshot, entries)
        reach.orphans.forEach { path ->
            issues.add(
                GateIssue(
                    "error", "ORPHAN_FILE", path,
                    message = "ملف مصدري لا يصل إلى نقطة دخول (ملف يتيم أو غير مربوط)"
                )
            )
        }

        if (signatures.isNotEmpty()) {
            verifySignatures(snapshot, signatures, hmacKey).forEach { issues.add(it) }
        }

        val errors = issues.count { it.severity == "error" }
        return GateReport(
            ok = errors == 0,
            issues = issues,
            checkedFiles = snapshot.files.size,
            reachable = reach.reachable.size,
            totalSource = reach.totalSource,
            emptyDirectories = snapshot.emptyDirectories.size
        )
    }

    /**
     * فحص بنيوي للـ Manifest قبل اعتماده: لا يتطلب وجود الملفات بعد (فهي تُكتب في التنفيذ)،
     * لكنه يرفض المسارات غير الآمنة والتكرار وDAG المكسور أو الدوري.
     */
    fun validateManifest(manifest: BuildManifest): GateReport {
        val issues = mutableListOf<GateIssue>()
        if (manifest.project.isBlank()) {
            issues.add(GateIssue("error", "MANIFEST_NO_PROJECT", message = "اسم المشروع مطلوب"))
        }
        val root = ManifestPaths.normalize(manifest.root)
        if (root.contains("..")) {
            issues.add(GateIssue("error", "MANIFEST_BAD_ROOT", manifest.root, message = "جذر المشروع لا يمكن أن يحتوي .."))
        }
        if (manifest.files.isEmpty()) {
            issues.add(GateIssue("error", "MANIFEST_NO_FILES", message = "Manifest يحتاج ملفاً واحداً على الأقل"))
        }
        val seen = mutableSetOf<String>()
        manifest.files.forEach { file ->
            val path = ManifestPaths.normalize(file.path)
            if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
                issues.add(GateIssue("error", "MANIFEST_BAD_PATH", file.path, message = "مسار ملف غير آمن"))
            } else if (!seen.add(path)) {
                issues.add(GateIssue("error", "MANIFEST_DUPLICATE_FILE", path, message = "مسار مكرر في Manifest"))
            }
        }
        val filePaths = seen
        val taskIds = mutableSetOf<String>()
        manifest.tasks.forEach { task ->
            val id = task.id.trim()
            if (id.isBlank()) {
                issues.add(GateIssue("error", "MANIFEST_TASK_NO_ID", message = "مهمة بلا معرّف"))
            } else if (!taskIds.add(id)) {
                issues.add(GateIssue("error", "MANIFEST_DUPLICATE_TASK", id, message = "معرّف مهمة مكرر"))
            }
            task.dependsOn.forEach { dep ->
                if (dep !in manifest.tasks.map { it.id }) {
                    issues.add(GateIssue("error", "MANIFEST_BAD_DEP", id, message = "اعتماد على مهمة غير موجودة: $dep"))
                }
            }
            task.files.forEach { path ->
                if (ManifestPaths.normalize(path) !in filePaths) {
                    issues.add(GateIssue("error", "MANIFEST_TASK_UNKNOWN_FILE", id, message = "ملف غير موجود في Manifest: $path"))
                }
            }
        }
        // كشف الدوران في DAG عبر Kahn.
        if (manifest.tasks.isNotEmpty()) {
            val indegree = manifest.tasks.associate { it.id to it.dependsOn.count { dep -> dep in taskIds } }.toMutableMap()
            val queue = ArrayDeque(manifest.tasks.filter { indegree[it.id] == 0 }.map { it.id })
            var visited = 0
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                visited++
                manifest.tasks.filter { current in it.dependsOn }.forEach { next ->
                    val left = (indegree[next.id] ?: 0) - 1
                    indegree[next.id] = left
                    if (left == 0) queue.addLast(next.id)
                }
            }
            if (visited != manifest.tasks.size) {
                issues.add(GateIssue("error", "MANIFEST_CYCLE", message = "DAG يحتوي دورة؛ لا يمكن ترتيب المهام"))
            }
        }
        return GateReport(ok = issues.none { it.severity == "error" }, issues = issues)
    }

        /**
     * يستخرج أسماء الرموز المعلنة على المستوى الأعلى (class/object/fun/val/const/interface/enum).
     * تُستخدم لربط الملفات عبر رموزها لا عبر أسماء ملفاتها — Kotlin/Java لا تستورد ملفاً باسمه.
     */
    fun declaredSymbols(text: String): List<String> {
        val pattern = Regex(
            "(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*" +
                "(?:internal|private|public|protected|open|abstract|sealed|data|enum|annotation|inline|suspend|const|lateinit|override|operator|infix|tailrec|external|actual|expect)?\\s*" +
                                "(?:class|object|interface|fun|val|var|typealias)\\s+(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?([A-Za-z_][A-Za-z0-9_]*)"
        )
        return pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    fun reachability(snapshot: ProjectSnapshot, entryPoints: Set<String>): ReachabilityResult {
        val sources = snapshot.files.keys
            .filter { isSource(it) && !isTest(it) && !isIgnored(it) }
            .toSortedSet()
        val normalizedEntries = entryPoints
            .map { ManifestPaths.normalize(it) }
            .filter { snapshot.has(it) || snapshot.files.keys.any { p -> p.endsWith("/$it") } }
            .map { entry ->
                if (snapshot.has(entry)) entry
                else snapshot.files.keys.firstOrNull { it.endsWith("/$entry") } ?: entry
            }
            .toSet()
        if (sources.isEmpty()) {
            return ReachabilityResult(normalizedEntries, emptySet(), emptySet(), 0)
        }

                val byBase = sources.groupBy { it.substringAfterLast('/').substringBeforeLast('.').lowercase() }

        // خريطة رمز → الملفات التي تُعلنه. ضرورية لأن Kotlin/Java لا تستورد ملفاً باسمه:
        // LlmConfig.kt يُستخدم عبر ثوابته (LLM_USER_AGENT) لا بذكر اسم الملف.
        val bySymbol = HashMap<String, MutableSet<String>>()
        sources.forEach { source ->
            declaredSymbols(snapshot.text(source)).forEach { sym ->
                bySymbol.getOrPut(sym.lowercase()) { LinkedHashSet() }.add(source)
            }
        }

        val edges = HashMap<String, MutableSet<String>>()
        sources.forEach { source ->
            val text = snapshot.text(source)
            val refs = LinkedHashSet<String>()
            byBase.forEach { (base, candidates) ->
                if (base.length < 3) return@forEach
                if (candidates.any { it == source }) return@forEach
                if (text.contains(base, ignoreCase = false) || text.contains(base, ignoreCase = true)) {
                    candidates.filter { it != source }.forEach { refs.add(it) }
                }
            }
            // ربط بالرموز المعلنة: أي رمز من ملف آخر مذكور هنا = حافة وصول.
            bySymbol.forEach { (sym, owners) ->
                if (sym.length < 4) return@forEach
                if (owners.any { it == source }) return@forEach
                if (Regex("(?<![A-Za-z0-9_$])" + Regex.escape(sym) + "(?![A-Za-z0-9_$])", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text)
                ) {
                    owners.filter { it != source }.forEach { refs.add(it) }
                }
            }
            normaliseImports(text).forEach { imported ->
                byBase[imported.lowercase()]?.filter { it != source }?.forEach { refs.add(it) }
            }
            edges[source] = refs
        }

        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        normalizedEntries.filter { it in sources }.forEach { queue.add(it); reachable.add(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            edges[current].orEmpty().forEach { next ->
                if (reachable.add(next)) queue.add(next)
            }
        }
        val orphans = sources.filterNot { it in reachable }.toSet()
        return ReachabilityResult(normalizedEntries, reachable, orphans, sources.size)
    }

    fun buildEntryPoints(snapshot: ProjectSnapshot, manifest: BuildManifest?): Set<String> {
        val result = LinkedHashSet<String>()
        manifest?.entryPoints.orEmpty().forEach {
            val joined = ManifestPaths.join(manifest?.root.orEmpty(), it)
            result.add(if (snapshot.has(joined)) joined else ManifestPaths.normalize(it))
        }
        snapshot.files.filter { (path, _) -> manifest?.file(path)?.entry == true }.keys.forEach { result.add(it) }
        val autoNames = listOf(
            "MainActivity.kt", "MainActivity.java", "Main.kt", "App.kt", "Application.kt",
            "MainApplication.kt", "main.py", "app.py", "index.js", "index.tsx", "main.ts",
            "main.tsx", "App.tsx", "App.jsx", "main.dart", "Program.cs", "main.go", "main.rs"
        )
        autoNames.forEach { name ->
            snapshot.files.keys.firstOrNull { it.endsWith("/$name") || it == name }?.let { result.add(it) }
        }
        // مكوّنات أندرويد المعلنة في AndroidManifest.xml هي جذور مشروعة أيضاً.
        val androidManifest = snapshot.files.entries.firstOrNull { it.key.endsWith("AndroidManifest.xml") }?.value
        if (androidManifest != null) {
            Regex("android:name\\s*=\\s*\"\\.?([A-Za-z0-9_.]+)\"").findAll(androidManifest).forEach { match ->
                val simple = match.groupValues[1].substringAfterLast('.')
                snapshot.files.keys.firstOrNull {
                    it.substringAfterLast('/').substringBeforeLast('.').equals(simple, ignoreCase = true)
                }?.let { result.add(it) }
            }
        }
        if (manifest != null && result.isEmpty()) {
            snapshot.files.filterKeys { isSource(it) && !isTest(it) }.keys.take(1).forEach { result.add(it) }
        }
        return result
    }

    private fun normaliseImports(text: String): List<String> {
        val result = LinkedHashSet<String>()
        Regex("(?m)^\\s*import\\s+([A-Za-z0-9_.]+)").findAll(text).forEach { match ->
            result.add(match.groupValues[1].substringAfterLast('.'))
        }
        Regex("""(?:from|import)\s+['"]([^'"]+)['"]""").findAll(text).forEach { match ->
            val raw = match.groupValues[1]
            result.add(raw.substringAfterLast('/').substringBefore('.'))
            result.add(raw.substringAfterLast('/').substringBefore('.').replace('-', '_'))
        }
        Regex("""require\(\s*['"]([^'"]+)['"]\s*\)""").findAll(text).forEach { match ->
            result.add(match.groupValues[1].substringAfterLast('/').substringBefore('.'))
        }
        Regex("""#include\s+["<]([^">]+)[">]""").findAll(text).forEach { match ->
            result.add(match.groupValues[1].substringAfterLast('/').substringBefore('.'))
        }
        return result.toList()
    }

    /** بديل عملي عن typecheck/lint داخل التطبيق: أخطاء بنيوية حقيقية لا مجرد أسلوب. */
    fun lintAndTypecheck(snapshot: ProjectSnapshot): List<GateIssue> {
        val issues = mutableListOf<GateIssue>()
        snapshot.files.forEach { (path, content) ->
            if (isIgnored(path) || isTest(path)) return@forEach
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in CODE_EXT) return@forEach
            if (content.isBlank()) {
                issues.add(GateIssue("error", "EMPTY_FILE", path, message = "ملف فارغ"))
                return@forEach
            }
                        if (ext in SOURCE_EXT) {
                // نبحث عن العناصر النائبة في الكود الحقيقي فقط: نص داخل سلسلة أو تعليق ليس كوداً ناقصاً.
                val code = stripLiterals(content)
                PLACEHOLDER.find(code)?.let { match ->
                    val line = code.take(match.range.first).count { it == '\n' } + 1
                    issues.add(
                        GateIssue("error", "PLACEHOLDER", path, line, "كود ناقص/مؤجل: ${match.value.take(40)}")
                    )
                }
                balance(content, ext in HASH_COMMENT_EXT)?.let { error ->
                    issues.add(GateIssue("error", "SYNTAX_BALANCE", path, error.second, error.first))
                }
            }
        }
        return issues
    }

    private val REGEX_KEYWORDS = setOf(
        "return", "case", "throw", "typeof", "void", "delete", "in", "of", "yield", "await", "else"
    )

    private fun canStartRegex(text: String, index: Int): Boolean {
        var k = index - 1
        while (k >= 0 && (text[k] == ' ' || text[k] == '\t')) k--
        if (k < 0 || text[k] == '\n') return true
        val prev = text[k]
        if (prev in "(,=:[!&|?{};+-*%<>~^") return true
        if (prev.isLetter()) {
            var start = k
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '$')) {
                start--
            }
            val word = text.substring(start, k + 1)
            if (word in REGEX_KEYWORDS) return true
        }
        return false
    }

        /**
     * يفحص توازن الأقواس مع تجاهل النصوص والتعليقات لتقليل الإيجابيات الكاذبة.
     * @param hashComments true في لغات `#` (Python/Shell/Ruby) حيث التعليق يبدأ بعد مسافات بادئة أيضاً.
     */
    fun balance(text: String, hashComments: Boolean = false): Pair<String, Int>? {
        val stack = ArrayDeque<Char>()
        var i = 0
        var line = 1
        var state = 0 // 0 code, 1 line comment, 2 block comment, 3 single quote, 4 double quote, 5 triple quote, 6 backtick
        var tripleChar = '"'
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (c == '\n') line++
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { state = 1; i++ }
                    c == '/' && next == '*' -> { state = 2; i++ }
                    // regex literal (JS/TS): بعد رمز أو كلمة مفتاحية مثل return؛ نتجاوزه كله كي لا تُعدّ أقواسه/علامات اقتباسه.
                    c == '/' && canStartRegex(text, i) -> {
                        var j = i + 1
                        var inClass = false
                        while (j < text.length && text[j] != '\n') {
                            val d = text[j]
                            if (d == '\\') { j += 2; continue }
                            if (d == '[') inClass = true
                            else if (d == ']') inClass = false
                            else if (d == '/' && !inClass) break
                            j++
                        }
                        if (j < text.length && text[j] == '/') i = j
                    }
                                        // في لغات `#` التعليق يبدأ عند بداية كلمة لا عند العمود صفر فقط.
                    hashComments && c == '#' &&
                        (i == 0 || text[i - 1].isWhitespace() || text[i - 1] == ';') -> state = 1
                    c == '\'' -> { state = 3 }
                    c == '"' -> {
                        if (next == '"' && text.getOrNull(i + 2) == '"') { state = 5; tripleChar = '"'; i += 2 }
                        else state = 4
                    }
                    c == '`' -> state = 6
                    c == '(' || c == '[' || c == '{' -> stack.addLast(c)
                    c == ')' || c == ']' || c == '}' -> {
                        val open = stack.removeLastOrNull()
                            ?: return "قوس إغلاق بلا فتح: $c" to line
                        val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                        if (open != expected) return "قوس غير متطابق: توقعت إغلاق $open ووجدت $c" to line
                    }
                }
                1 -> if (c == '\n') state = 0
                2 -> if (c == '*' && next == '/') { state = 0; i++ }
                3 -> if (c == '\n') state = 0 else if (c == '\\') i++ else if (c == '\'') state = 0
                4 -> if (c == '\n') state = 0 else if (c == '\\') i++ else if (c == '"') state = 0
                5 -> if (c == tripleChar && next == tripleChar && text.getOrNull(i + 2) == tripleChar) {
                    state = 0; i += 2
                }
                6 -> if (c == '\\') {
                    if (next == '\n') line++
                    i++
                } else if (c == '`') state = 0
            }
            i++
        }
        if (stack.isNotEmpty()) {
            return "أقواس غير مغلقة: ${stack.joinToString("")}" to line
        }
        return null
    }

    fun verifySignatures(
        snapshot: ProjectSnapshot,
        signatures: Map<String, String>,
        hmacKey: String = TestIntegrity.keyMarker
    ): List<GateIssue> {
        val issues = mutableListOf<GateIssue>()
        signatures.forEach { (path, expected) ->
            val normalized = ManifestPaths.normalize(path)
            if (!snapshot.has(normalized)) {
                issues.add(GateIssue("error", "TEST_MISSING", normalized, message = "ملف اختبار موقّع مفقود"))
                return@forEach
            }
            val actual = TestIntegrity.sign(snapshot.text(normalized), hmacKey)
            if (actual != expected) {
                issues.add(
                    GateIssue(
                        "error", "TEST_TAMPERED", normalized,
                        message = "تم تعديل ملف اختبار موقّع دون تفويض (HMAC mismatch)"
                    )
                )
            }
        }
        return issues
    }
}

/** توقيع HMAC-SHA256 لملفات الاختبار لكشف العبث بدل إصلاح الكود. */
object TestIntegrity {
    /**
     * علامة داخلية: التحقق الفعلي في ControlPlane.verifySignatures يستخدم المفتاح
     * الممرر من الإعدادات. هذه القيمة تُستبدل في ControlRuntime قبل الاستدعاء.
     */
    var keyMarker: String = "lion-default-test-key"

    fun sign(text: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun signAll(snapshot: ProjectSnapshot, key: String): Map<String, String> =
        snapshot.files.filterKeys { ControlPlane.isTest(it) }
            .mapValues { (_, content) -> sign(content, key) }
}

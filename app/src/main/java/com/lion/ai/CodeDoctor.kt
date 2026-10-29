package com.lion.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * طبيب المشروع: تقرير جاهزية هندسية واحد يجمع البنية، صحة الكود، الاختبارات،
 * سلسلة البناء المتاحة، الاعتماديات، وحوكمة Manifest — ثم يعطي حكماً وخطوات مرتبة.
 * لا يدّعي تنفيذ بناء لم يحدث؛ يفحص ما هو متاح فعلاً على الجهاز.
 */
object CodeDoctor {
    fun report(
        context: Context,
        prefs: SharedPreferences,
        path: String = "",
        chatId: String = "",
        online: Boolean = false,
        maxFiles: Int = 3000
    ): String {
        val entries = FileTools.walk(context, path, maxFiles.coerceIn(1, 5000))
        if (entries.isEmpty()) {
            return "🩺 طبيب المشروع\nالمسار فارغ أو لا يحتوي ملفات: ${path.ifBlank { "مساحة العمل" }}"
        }
        val names = entries.map { it.path.lowercase() }
        val systems = detectSystems(names)
        val sources = entries.filter {
            CodeAnalyzer.languageFor(it.path) != "غير معروف" && it.size in 1..600_000
        }.take(200)
        val testFiles = entries.filter { isTest(it.path) }
        val emptyFiles = entries.filter { it.size == 0L }
        val analyzed = sources.mapNotNull { entry ->
            runCatching { entry to FileTools.read(context, entry.path, 600_000).text }.getOrNull()
        }

        var todos = 0
        var secretHits = 0
        var longLines = 0
        var emptySource = 0
        val worst = mutableListOf<Pair<String, Int>>()
        analyzed.forEach { (entry, text) ->
            val result = CodeAnalyzer.analyze(entry.path, text)
            todos += result.todos.size
            secretHits += result.issues.count { it.message.contains("سر/مفتاح") }
            longLines += result.issues.count { it.message.startsWith("سطر طويل") }
            if (text.isBlank()) emptySource++
            worst += entry.path to result.complexity
        }

        val manifest = if (chatId.isBlank()) null else ManifestStore.read(prefs, chatId)
        val missingTools = mutableListOf<String>()
        systems.forEach { system ->
            system.tools.forEach { tool ->
                if (resolveTool(context, tool) == null) missingTools += "$tool (${system.label})"
            }
        }

        val score = score(
            systems = systems,
            sources = sources.size,
            tests = testFiles.size,
            emptySource = emptySource,
            secrets = secretHits,
            missingTools = missingTools.size,
            manifest = manifest
        )
        val level = when {
            score >= 85 -> "🟢 جاهز"
            score >= 65 -> "🟡 جاهز مع ملاحظات"
            score >= 40 -> "🟠 يحتاج عملاً قبل الاعتماد"
            else -> "🔴 غير جاهز"
        }

        val actions = mutableListOf<String>()
        if (systems.isEmpty()) actions += "أضف ملف بناء واضحاً (build.gradle.kts أو package.json أو pyproject.toml…)."
        if (sources.isEmpty()) actions += "لا توجد ملفات مصدرية قابلة للتحليل؛ تأكد من وجود الكود داخل مساحة العمل."
        if (emptySource > 0) actions += "أكمل أو احذف $emptySource ملفاً مصدرياً فارغاً (بوابة Root Reachability ترفضها)."
        if (testFiles.isEmpty()) actions += "أضف اختباراً واحداً على الأقل لمسار حرج قبل البناء."
        if (secretHits > 0) actions += "أزل $secretHits مفتاحاً/سراً مكتوباً داخل الكود وانقلها إلى الإعدادات."
        val androidProject = systems.any { it.label.startsWith("Android") }
        if (androidProject && names.none { it.substringAfterLast('/') == "gradlew" }) {
            actions += "أضف Gradle Wrapper (gradlew) أو استخدم github_dispatch لبناء APK في Actions."
        }
        if (androidProject && System.getenv("ANDROID_HOME").isNullOrBlank() &&
            System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
        ) {
            actions += "Android SDK غير مضبوط محلياً (ANDROID_HOME/ANDROID_SDK_ROOT)؛ البناء المحلي غير متاح."
        }
        if (missingTools.isNotEmpty()) {
            actions += "أدوات ناقصة محلياً: ${missingTools.take(6).joinToString("، ")}. استخدم GitHub Actions للبناء/الاختبار."
        }
        if (manifest != null && !manifest.approved) actions += "اعتمد Manifest الجلسة قبل أي كتابة (pipeline_approve)."
        if (todos > 10) actions += "عالج $todos موضع TODO/FIXME أو حوّلها إلى مهام موثقة."
        if (longLines > 20) actions += "اختصر $longLines سطراً طويلاً (>120 حرفاً) لتحسين القراءة والمراجعة."
        if (actions.isEmpty()) actions += "لا عوائق ظاهرة؛ شغّل الاختبارات والبناء ثم راجع الفروق قبل الدمج."

        return buildString {
            append("🩺 تقرير جاهزية المشروع: ").append(level).append(" (").append(score).append("/100)\n")
            append("• المسار: ").append(path.ifBlank { "مساحة العمل" }).append('\n')
            append("• الملفات: ").append(entries.size)
            append(" | مصدرية: ").append(sources.size)
            append(" | اختبارات: ").append(testFiles.size)
            append(" | فارغة: ").append(emptyFiles.size).append('\n')
            append("• أنظمة البناء: ").append(
                if (systems.isEmpty()) "غير معروفة" else systems.joinToString("، ") { it.label }
            ).append('\n')
            append("• نقاط الدخول: ").append(entryPoints(names).joinToString("، ").ifBlank { "لم تُكتشف" }).append('\n')
            if (manifest != null) {
                append("• Manifest: ").append(if (manifest.approved) "معتمد" else "غير معتمد")
                append(" (").append(manifest.files.size).append(" ملف)\n")
            } else {
                append("• Manifest: لا يوجد لهذه الجلسة\n")
            }
            append("\n📊 صحة الكود:\n")
            append("• TODO/FIXME: ").append(todos)
            append(" | أسرار محتملة: ").append(secretHits)
            append(" | أسطر طويلة: ").append(longLines)
            append(" | ملفات فارغة: ").append(emptySource).append('\n')
            if (worst.isNotEmpty()) {
                append("• أعلى تعقيد: ").append(
                    worst.sortedByDescending { it.second }.take(5)
                        .joinToString("، ") { "${it.first} (${it.second})" }
                ).append('\n')
            }
            if (systems.isNotEmpty()) {
                append("\n🔧 سلسلة البناء:\n")
                systems.forEach { system ->
                    append("• ").append(system.label).append(": ")
                    append(system.tools.joinToString("، ") { tool ->
                        val found = resolveTool(context, tool)
                        if (found == null) "$tool ✗" else "$tool ✓"
                    })
                    append('\n')
                }
            }
            val deps = runCatching {
                DependencyAudit.audit(context, path, online, maxFiles)
            }.getOrElse { "تعذّر تدقيق الاعتماديات: ${it.message}" }
            append("\n").append(deps.take(3500)).append('\n')
            append("\n🎯 الخطوات المقترحة:\n")
            actions.take(10).forEachIndexed { index, action ->
                append(index + 1).append(". ").append(action).append('\n')
            }
            append("\nℹ️ هذا تقرير فحص ساكن؛ البناء الفعلي يحتاج JDK/SDK محلياً أو github_dispatch/wait_run.")
        }.trim()
    }

    private data class BuildSystem(val label: String, val tools: List<String>)

    private fun detectSystems(names: List<String>): List<BuildSystem> {
        val systems = mutableListOf<BuildSystem>()
        val bases = names.map { it.substringAfterLast('/') }.toSet()
        if (bases.any { it == "build.gradle" || it == "build.gradle.kts" }) {
            systems += BuildSystem("Android/Gradle", listOf("java", "sh"))
        }
        if ("package.json" in bases) systems += BuildSystem("Node/npm", listOf("node", "npm"))
        if ("pyproject.toml" in bases || bases.any { it.startsWith("requirements") }) {
            systems += BuildSystem("Python", listOf("python3", "pip3"))
        }
        if ("pubspec.yaml" in bases) systems += BuildSystem("Flutter/Dart", listOf("flutter", "dart"))
        if ("cargo.toml" in bases) systems += BuildSystem("Rust/Cargo", listOf("cargo"))
        if ("go.mod" in bases) systems += BuildSystem("Go", listOf("go"))
        if ("pom.xml" in bases) systems += BuildSystem("Maven", listOf("java", "mvn"))
        return systems
    }

    private fun entryPoints(names: List<String>): List<String> =
        names.filter {
            it.contains("mainactivity") || it.endsWith("androidmanifest.xml") ||
                it.endsWith("/main.py") || it == "main.py" || it == "app.py" || it == "manage.py" ||
                it.endsWith("/index.js") || it == "index.js" || it == "lib/main.dart" ||
                it == "src/main.rs" || it == "src/lib.rs" || it == "main.go"
        }.take(6)

    private fun isTest(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/src/test/") || lower.contains("/test/") || lower.contains("/tests/") ||
            lower.contains("__tests__") || lower.substringAfterLast('/').contains("test.")
    }

    private fun resolveTool(context: Context, tool: String): String? {
        val found = ShellTools.which(context, tool)
        return if (found.startsWith("غير موجود")) null else found
    }

    private fun score(
        systems: List<BuildSystem>,
        sources: Int,
        tests: Int,
        emptySource: Int,
        secrets: Int,
        missingTools: Int,
        manifest: BuildManifest?
    ): Int {
        var score = 100
        if (systems.isEmpty()) score -= 25
        if (sources == 0) score -= 30
        if (tests == 0) score -= 10
        score -= minOf(emptySource * 10, 20)
        if (secrets > 0) score -= 15
        score -= minOf(missingTools * 5, 15)
        if (manifest != null && !manifest.approved) score -= 10
        return score.coerceIn(0, 100)
    }
}

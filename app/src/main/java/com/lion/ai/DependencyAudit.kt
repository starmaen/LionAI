package com.lion.ai

import android.content.Context
import org.json.JSONObject

/**
 * تدقيق المكتبات والاعتماديات دون بناء المشروع:
 * يقرأ ملفات Gradle/Maven/npm/Python/Dart/Rust/Go، ويكشف النسخ غير المثبّتة
 * والتعارضات، ويمكنه الاستعلام من OSV عن الثغرات المعروفة للنسخ المثبّتة.
 */
object DependencyAudit {
    data class Dependency(
        val ecosystem: String,
        val name: String,
        val version: String,
        val source: String,
        val scope: String
    )

    data class Finding(
        val id: String,
        val name: String,
        val version: String,
        val severity: String,
        val summary: String,
        val fixed: String
    )

    private val MANIFEST_NAMES = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "libs.versions.toml", "pom.xml", "package.json", "requirements.txt",
        "pyproject.toml", "pubspec.yaml", "cargo.toml", "go.mod"
    )

    private val GRADLE_DEP = Regex(
        """(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|androidTestImplementation|kapt|ksp|classpath|annotationProcessor)\s*\(\s*["']([^"':]+):([^"':]+):([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val GRADLE_CATALOG_MODULE = Regex("""module\s*=\s*["']([^"':]+):([^"':]+)["']""")
    private val GRADLE_CATALOG_VERSION = Regex("""version(?:\.ref)?\s*=\s*["']([^"']+)["']""")
    private val GRADLE_PLUGIN = Regex("""id\s*\(\s*["']([^"']+)["']\s*\)\s*version\s*["']([^"']+)["']""")
    private val MAVEN_DEP = Regex(
        """<dependency>.*?<groupId>\s*([^<]+)\s*</groupId>.*?<artifactId>\s*([^<]+)\s*</artifactId>.*?(?:<version>\s*([^<]+)\s*</version>)?.*?</dependency>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val PY_REQ = Regex("""^([A-Za-z0-9_.\-]+)\s*(==|>=|<=|~=|>|<|!=)?\s*([^\s#;]+)?""")
    private val GO_REQUIRE = Regex("""^\s*(?:require\s+)?([A-Za-z0-9_.\-/]+)\s+(v[^\s]+)\s*$""")

    fun audit(context: Context, path: String = "", online: Boolean = true, maxFiles: Int = 5000): String {
        val deps = collect(context, path, maxFiles)
        if (deps.isEmpty()) {
            return "لم أجد ملفات اعتماديات معروفة (Gradle/Maven/npm/Python/Dart/Rust/Go) تحت: " +
                path.ifBlank { "مساحة العمل" }
        }
        val byEcosystem = deps.groupBy { it.ecosystem }.toSortedMap()
        val unpinned = deps.filter { it.version.isBlank() || exactVersion(it.version) == null }
        val conflicts = deps.groupBy { it.ecosystem to it.name.lowercase() }
            .filterValues { list -> list.map { exactVersion(it.version) }.filterNotNull().distinct().size > 1 }

        val report = buildString {
            append("📦 تدقيق الاعتماديات (").append(deps.size).append(" اعتمادية)\n")
            byEcosystem.forEach { (ecosystem, list) ->
                append("• ").append(ecosystem).append(": ").append(list.size)
                append(" (").append(list.map { it.name }.distinct().size).append(" حزمة فريدة)\n")
            }
            if (unpinned.isNotEmpty()) {
                append("\n⚠️ نسخ غير مثبّتة/نطاقات (").append(unpinned.size).append("):\n")
                unpinned.take(20).forEach {
                    append("  - ").append(it.name).append(" : ").append(it.version.ifBlank { "بدون نسخة" })
                    append(" [").append(it.source).append("]\n")
                }
            }
            if (conflicts.isNotEmpty()) {
                append("\n⚔️ تعارض إصدارات (").append(conflicts.size).append("):\n")
                conflicts.entries.take(12).forEach { (key, list) ->
                    append("  - ").append(key.second).append(": ")
                    append(list.map { it.version }.distinct().joinToString("، "))
                    append(" [").append(list.first().source).append("]\n")
                }
            }
            if (unpinned.isEmpty() && conflicts.isEmpty()) {
                append("\n✅ لا نطاقات مفتوحة ولا تعارضات إصدارات ظاهرة.\n")
            }
            append("\nأهم المكتبات المباشرة:\n")
            deps.filter { it.scope in setOf("implementation", "dependencies", "require") }
                .distinctBy { it.ecosystem to it.name.lowercase() }
                .take(25)
                .forEach { append("  - ").append(it.name).append(' ').append(it.version).append('\n') }
        }.trim()

        if (!online) return report + "\n\nℹ️ الفحص الأمني OSV معطّل (online=false)."

        val findings = runCatching { osv(deps) }.getOrElse {
            return report + "\n\n⚠️ تعذّر الاستعلام من OSV: " + (it.message ?: it.javaClass.simpleName)
        }
        if (findings.isEmpty()) {
            return report + "\n\n✅ OSV: لم تُعثر ثغرات معروفة للنسخ المثبّتة."
        }
        return buildString {
            append(report)
            append("\n\n🛡️ ثغرات OSV (").append(findings.size).append("):\n")
            findings.take(40).forEach { finding ->
                append("  - [").append(finding.severity).append("] ").append(finding.name)
                append(' ').append(finding.version).append(" — ").append(finding.id)
                if (finding.fixed.isNotBlank()) append(" (الإصلاح: ").append(finding.fixed).append(')')
                if (finding.summary.isNotBlank()) append("\n    ").append(finding.summary.take(220))
                append('\n')
            }
            append("\n⚠️ حدّث الحزم المتأثرة وأعد البناء قبل النشر.")
        }
    }

    fun collect(context: Context, path: String = "", maxFiles: Int = 5000): List<Dependency> {
        val entries = FileTools.walk(context, path, maxFiles)
        val result = mutableListOf<Dependency>()
        entries.forEach { entry ->
            val name = entry.path.substringAfterLast('/').lowercase()
            if (name !in MANIFEST_NAMES && !name.startsWith("requirements")) return@forEach
            val text = runCatching { FileTools.read(context, entry.path, 2_000_000).text }.getOrNull()
                ?: return@forEach
            result += when {
                name.contains("build.gradle") || name.endsWith(".gradle.kts") ->
                    parseGradle(text, entry.path)
                name == "libs.versions.toml" -> parseVersionCatalog(text, entry.path)
                name == "pom.xml" -> parseMaven(text, entry.path)
                name == "package.json" -> parsePackageJson(text, entry.path)
                name.startsWith("requirements") -> parseRequirements(text, entry.path)
                name == "pyproject.toml" -> parsePyProject(text, entry.path)
                name == "pubspec.yaml" -> parsePubspec(text, entry.path)
                name == "cargo.toml" -> parseCargo(text, entry.path)
                name == "go.mod" -> parseGoMod(text, entry.path)
                else -> emptyList()
            }
        }
        return result.distinctBy { "${it.ecosystem}:${it.name.lowercase()}:${it.version}:${it.source}" }
    }

    internal fun parseGradle(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        GRADLE_DEP.findAll(text).forEach {
            out += Dependency(
                "Maven", "${it.groupValues[2]}:${it.groupValues[3]}",
                it.groupValues[4].trim(), source, it.groupValues[1]
            )
        }
        GRADLE_PLUGIN.findAll(text).forEach {
            out += Dependency("Maven", it.groupValues[1], it.groupValues[2].trim(), source, "plugin")
        }
        return out
    }

    private fun parseVersionCatalog(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        var pendingName: String? = null
        text.lines().forEach { raw ->
            val line = raw.trim()
            val module = GRADLE_CATALOG_MODULE.find(line)
            if (module != null) {
                pendingName = "${module.groupValues[1]}:${module.groupValues[2]}"
                val version = GRADLE_CATALOG_VERSION.find(line)?.groupValues?.get(1)
                if (!version.isNullOrBlank()) {
                    out += Dependency("Maven", pendingName, version, source, "catalog")
                    pendingName = null
                }
            } else if (pendingName != null) {
                val version = GRADLE_CATALOG_VERSION.find(line)?.groupValues?.get(1)
                if (!version.isNullOrBlank()) {
                    out += Dependency("Maven", pendingName!!, version, source, "catalog")
                    pendingName = null
                }
            }
            if (line.startsWith("[")) pendingName = null
        }
        return out
    }

    private fun parseMaven(text: String, source: String): List<Dependency> =
        MAVEN_DEP.findAll(text).map {
            Dependency(
                "Maven", "${it.groupValues[1]}:${it.groupValues[2]}",
                it.groupValues[3].trim(), source, "dependency"
            )
        }.toList()

    internal fun parsePackageJson(text: String, source: String): List<Dependency> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Dependency>()
        listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies").forEach { scope ->
            val obj = root.optJSONObject(scope) ?: return@forEach
            obj.keys().forEach { name ->
                val version = obj.optString(name)
                if (name.isNotBlank() && version.isNotBlank()) {
                    out += Dependency("npm", name, version, source, scope)
                }
            }
        }
        return out
    }

    internal fun parseRequirements(text: String, source: String): List<Dependency> =
        text.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("-")) return@mapNotNull null
            val match = PY_REQ.find(line) ?: return@mapNotNull null
            val name = match.groupValues[1]
            val op = match.groupValues[2]
            val version = match.groupValues[3].substringBefore(';').trim()
            if (name.isBlank()) null else Dependency("PyPI", name, op + version, source, "requirements")
        }

    private fun parsePyProject(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        var inDeps = false
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) {
                inDeps = line == "[tool.poetry.dependencies]" || line == "[tool.poetry.dev-dependencies]"
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (inDeps) {
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim().trim('"')
                    val version = parts[1].trim().trim('"').substringBefore(' ').trim()
                    if (name.isNotBlank() && name != "python") {
                        out += Dependency("PyPI", name, version, source, "poetry")
                    }
                }
            } else if (line.contains("=")) {
                val key = line.substringBefore('=').trim()
                val candidates = if (key == "dependencies" || key.endsWith("dependencies")) {
                    Regex("\"([^\"]+)\"|'([^']+)'").findAll(line).map {
                        it.groupValues[1].ifBlank { it.groupValues[2] }
                    }.toList()
                } else {
                    listOf(line)
                }
                candidates.forEach { candidate ->
                    val match = PY_REQ.find(candidate)
                    if (match != null && match.groupValues[1].isNotBlank()) {
                        out += Dependency(
                            "PyPI", match.groupValues[1],
                            match.groupValues[2] + match.groupValues[3], source, "project"
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parsePubspec(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        var inDeps = false
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            val indent = line.length - line.trimStart().length
            val key = line.trimStart().substringBefore(':').trim()
            if (indent == 0) {
                inDeps = key == "dependencies" || key == "dev_dependencies"
                return@forEach
            }
            if (!inDeps || indent < 2) return@forEach
            val value = line.substringAfter(':', "").trim().trim('"')
            if (key.isNotBlank() && key !in setOf("flutter", "sdk") && value.isNotBlank() &&
                !value.startsWith("{")
            ) {
                out += Dependency("Pub", key, value, source, "pubspec")
            }
        }
        return out
    }

    private fun parseCargo(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        var inDeps = false
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) {
                inDeps = line.removePrefix("[").removeSuffix("]").endsWith("dependencies")
                return@forEach
            }
            if (!inDeps || line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val name = parts[0].trim().trim('"')
            val rawValue = parts[1].trim()
            val version = Regex("""version\s*=\s*["']([^"']+)["']""").find(rawValue)?.groupValues?.get(1)
                ?: rawValue.trim('"', '\'', ' ')
            if (name.isNotBlank() && version.isNotBlank()) {
                out += Dependency("crates.io", name, version, source, "cargo")
            }
        }
        return out
    }

    private fun parseGoMod(text: String, source: String): List<Dependency> {
        val out = mutableListOf<Dependency>()
        var inBlock = false
        text.lines().forEach { raw ->
            val line = raw.trim().substringBefore("//").trim()
            if (line.startsWith("require (")) {
                inBlock = true
                return@forEach
            }
            if (inBlock && line == ")") {
                inBlock = false
                return@forEach
            }
            if (line.startsWith("//") || line.isBlank()) return@forEach
            val match = GO_REQUIRE.find(line) ?: return@forEach
            val name = match.groupValues[1]
            if (name.isNotBlank() && name != "require") {
                out += Dependency("Go", name, match.groupValues[2], source, "require")
            }
        }
        return out
    }

    private fun osv(deps: List<Dependency>): List<Finding> {
        val queries = deps.mapNotNull { dep ->
            val version = exactVersion(dep.version) ?: return@mapNotNull null
            Json(
                "package" to Json("name" to dep.name, "ecosystem" to dep.ecosystem),
                "version" to version
            )
        }.distinctBy { it.toString() }
        if (queries.isEmpty()) return emptyList()
        val findings = mutableListOf<Finding>()
        queries.chunked(80).forEach { chunk ->
            val body = JSONObject().put("queries", org.json.JSONArray().apply {
                chunk.forEach { put(it) }
            })
            val response = LlmClient.postJson(
                "https://api.osv.dev/v1/querybatch",
                mapOf("Content-Type" to "application/json", "User-Agent" to LLM_USER_AGENT),
                body
            )
            val results = response.optJSONArray("results") ?: return@forEach
            chunk.forEachIndexed { index, query ->
                val result = results.optJSONObject(index) ?: return@forEachIndexed
                val vulns = result.optJSONArray("vulns") ?: return@forEachIndexed
                val pkg = query.optJSONObject("package")
                val name = pkg?.optString("name").orEmpty()
                val version = query.optString("version")
                for (i in 0 until vulns.length()) {
                    val vuln = vulns.optJSONObject(i) ?: continue
                    val severity = vuln.optJSONObject("database_specific")?.optString("severity").orEmpty()
                        .ifBlank { vuln.optJSONArray("severity")?.optJSONObject(0)?.optString("score").orEmpty() }
                        .ifBlank { "UNKNOWN" }
                    findings += Finding(
                        id = vuln.optString("id", "OSV"),
                        name = name,
                        version = version,
                        severity = severity,
                        summary = vuln.optString("summary").ifBlank { vuln.optString("details") },
                        fixed = fixedVersion(vuln)
                    )
                }
            }
        }
        return findings
    }

    private fun fixedVersion(vuln: JSONObject): String {
        val affected = vuln.optJSONArray("affected") ?: return ""
        for (i in 0 until affected.length()) {
            val ranges = affected.optJSONObject(i)?.optJSONArray("ranges") ?: continue
            for (j in 0 until ranges.length()) {
                val events = ranges.optJSONObject(j)?.optJSONArray("events") ?: continue
                for (k in 0 until events.length()) {
                    val fixed = events.optJSONObject(k)?.optString("fixed").orEmpty()
                    if (fixed.isNotBlank()) return fixed
                }
            }
        }
        return ""
    }

    /** يستخرج نسخة دقيقة قابلة للاستعلام، أو null إذا كانت نطاقاً/متغيراً. */
    internal fun exactVersion(raw: String): String? {
        val original = raw.trim()
        if (original.startsWith("^") || original.startsWith("~") || original.startsWith(">") ||
            original.startsWith("<") || original.startsWith("!=")
        ) {
            return null
        }
        val value = original
            .removePrefix("==").removePrefix("=")
            .trim()
        if (value.isBlank()) return null
        if (value.contains('$') || value.contains(' ') || value.contains('|') ||
            value.contains(',') || value.contains('*') || value.endsWith('+')
        ) {
            return null
        }
        if (!value.first().isDigit()) return null
        return value
    }

    private fun Json(vararg pairs: Pair<String, Any>): JSONObject =
        JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }
}

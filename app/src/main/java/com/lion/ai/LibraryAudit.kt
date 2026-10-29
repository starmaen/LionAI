package com.lion.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * فحص أعمق للمكتبات: أحدث إصدار من مستودع كل نظام بيئي، الترخيص، والوصف،
 * مع مقارنة الإصدار المثبّت بالإصدار الأحدث وتحديد نوع الترقية (رئيسية/فرعية/إصلاح).
 * لا يجلب الشيفرة ولا يبني المشروع؛ مجرد استعلامات قراءة من مستودعات عامة.
 */
object LibraryAudit {

    data class Info(
        val ecosystem: String,
        val name: String,
        val current: String,
        val latest: String,
        val license: String,
        val summary: String,
        val url: String,
        val error: String? = null
    ) {
        val level: String get() = levelOf(current, latest)
        val outdated: Boolean get() = level != "same" && level != "unknown"
    }

    fun normalizeEcosystem(raw: String): String = when (raw.trim().lowercase()) {
        "maven", "gradle" -> "Maven"
        "npm", "node", "javascript", "js" -> "npm"
        "pypi", "pip", "python" -> "PyPI"
        "pub", "dart", "flutter" -> "Pub"
        "crates", "crates.io", "cargo", "rust" -> "crates.io"
        "go", "golang" -> "Go"
        else -> raw.trim()
    }

    internal fun compareVersions(a: String, b: String): Int? {
        val left = numericParts(a) ?: return null
        val right = numericParts(b) ?: return null
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun numericParts(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").substringBefore('+').substringBefore('-')
        if (core.isBlank()) return null
        val parts = core.split('.')
        if (parts.any { it.toIntOrNull() == null }) return null
        return parts.mapNotNull { it.toIntOrNull() }
    }

    fun levelOf(current: String, latest: String): String {
        val c = DependencyAudit.exactVersion(current) ?: current.trim()
        val l = DependencyAudit.exactVersion(latest) ?: latest.trim()
        val cmp = compareVersions(c, l) ?: return "unknown"
        if (cmp >= 0) return "same"
        val ca = numericParts(c) ?: return "unknown"
        val la = numericParts(l) ?: return "unknown"
        val size = minOf(ca.size, la.size)
        for (i in 0 until size) {
            if (ca[i] != la[i]) return when (i) {
                0 -> "major"
                1 -> "minor"
                else -> "patch"
            }
        }
        return "patch"
    }

    fun lookup(ecosystem: String, name: String, current: String = "", online: Boolean = true): Info {
        val eco = normalizeEcosystem(ecosystem)
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return Info(eco, cleanName, current, "", "", "", "", "اسم مكتبة فارغ")
        }
        if (!online) {
            return Info(eco, cleanName, current, "", "", "", "", "الفحص عبر الإنترنت معطّل")
        }
        return try {
            when (eco) {
                "Maven" -> maven(cleanName, current)
                "npm" -> npm(cleanName, current)
                "PyPI" -> pypi(cleanName, current)
                "Pub" -> pub(cleanName, current)
                "crates.io" -> crates(cleanName, current)
                "Go" -> go(cleanName, current)
                else -> Info(eco, cleanName, current, "", "", "", "", "نظام بيئي غير مدعوم: $eco")
            }
        } catch (e: Exception) {
            Info(eco, cleanName, current, "", "", "", "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun base(eco: String, name: String, current: String, latest: String, license: String, summary: String, url: String): Info =
        Info(
            eco, name, current, latest,
            license.trim().take(80),
            summary.trim().replace(Regex("\\s+"), " ").take(160),
            url
        )

    private fun npm(name: String, current: String): Info {
        val encoded = encode(name)
        val json = LlmClient.getJson(
            "https://registry.npmjs.org/$encoded/latest",
            mapOf("Accept" to "application/json")
        )
        val license = json.optString("license").ifBlank {
            json.optJSONObject("license")?.optString("type").orEmpty()
        }
        return base(
            "npm", name, current, json.optString("version"), license,
            json.optString("description"), "https://www.npmjs.com/package/$name"
        )
    }

    private fun pypi(name: String, current: String): Info {
        val json = LlmClient.getJson("https://pypi.org/pypi/${encode(name)}/json", emptyMap())
        val info = json.optJSONObject("info") ?: throw IllegalStateException("رد PyPI بلا معلومات")
        var license = info.optString("license")
        if (license.isBlank() || license.equals("UNKNOWN", ignoreCase = true)) {
            val classifiers = info.optJSONArray("classifiers")
            if (classifiers != null) {
                for (i in 0 until classifiers.length()) {
                    val c = classifiers.optString(i)
                    if (c.startsWith("License ::")) {
                        license = c.substringAfterLast("::").trim()
                        break
                    }
                }
            }
        }
        return base(
            "PyPI", name, current, info.optString("version"), license,
            info.optString("summary"), info.optString("project_url").ifBlank { "https://pypi.org/project/$name/" }
        )
    }

    private fun pub(name: String, current: String): Info {
        val json = LlmClient.getJson("https://pub.dev/api/packages/${encode(name)}", emptyMap())
        val latest = json.optJSONObject("latest")
        val pubspec = latest?.optJSONObject("pubspec")
        return base(
            "Pub", name, current,
            latest?.optString("version").orEmpty(),
            pubspec?.optString("license").orEmpty(),
            pubspec?.optString("description").orEmpty(),
            pubspec?.optString("repository").orEmpty()
                .ifBlank { pubspec?.optString("homepage").orEmpty() }
                .ifBlank { "https://pub.dev/packages/$name" }
        )
    }

    private fun crates(name: String, current: String): Info {
        val json = LlmClient.getJson(
            "https://crates.io/api/v1/crates/${encode(name)}",
            mapOf("Accept" to "application/json")
        )
        val crate = json.optJSONObject("crate") ?: throw IllegalStateException("رد crates.io بلا بيانات")
        val versionLicense = json.optJSONArray("versions")?.optJSONObject(0)?.optString("license").orEmpty()
        return base(
            "crates.io", name, current, crate.optString("max_version"),
            crate.optString("license").ifBlank { versionLicense },
            crate.optString("description"),
            crate.optString("repository").ifBlank { "https://crates.io/crates/$name" }
        )
    }

    private fun go(name: String, current: String): Info {
        val json = LlmClient.getJson(
            "https://proxy.golang.org/${name.trim('/')}/@latest",
            emptyMap()
        )
        return base(
            "Go", name, current, json.optString("Version").removePrefix("v"),
            "", "", "https://pkg.go.dev/$name"
        )
    }

    private fun maven(name: String, current: String): Info {
        val parts = name.split(":")
        if (parts.size < 2) throw IllegalArgumentException("صيغة Maven يجب أن تكون group:artifact")
        val group = parts[0].trim()
        val artifact = parts[1].trim()
        var latest = googleLatest(group, artifact)
        if (latest.isBlank()) {
            val query = encode("g:\"$group\" AND a:\"$artifact\"")
            val json = LlmClient.getJson(
                "https://search.maven.org/solrsearch/select?q=$query&rows=1&wt=json",
                emptyMap()
            )
            latest = json.optJSONObject("response")
                ?.optJSONArray("docs")
                ?.optJSONObject(0)
                ?.optString("latestVersion")
                .orEmpty()
        }
        if (latest.isBlank()) throw IllegalStateException("لم أجد $name في Maven Central/Google")
        return base("Maven", name, current, latest, "", "", "https://mvnrepository.com/artifact/$group/$artifact")
    }

    private fun googleLatest(group: String, artifact: String): String {
        val googleGroup = group.startsWith("androidx.") || group.startsWith("com.android.") ||
            group.startsWith("com.google.android.") || group.startsWith("com.google.firebase.") ||
            group.startsWith("com.google.mlkit.")
        if (!googleGroup) return ""
        val path = group.replace('.', '/')
        val xml = runCatching {
            httpText("https://dl.google.com/dl/android/maven2/$path/$artifact/maven-metadata.xml")
        }.getOrNull().orEmpty()
        return Regex("<release>\\s*([^<]+?)\\s*</release>").find(xml)?.groupValues?.get(1)
            ?: Regex("<latest>\\s*([^<]+?)\\s*</latest>").find(xml)?.groupValues?.get(1)
            ?: ""
    }

    fun audit(context: android.content.Context, path: String = "", max: Int = 12, online: Boolean = true): String {
        if (!online) return "⚠️ فحص أحدث الإصدارات يحتاج اتصالاً بالإنترنت."
        val deps = DependencyAudit.collect(context, path, 5000)
            .distinctBy { normalizeEcosystem(it.ecosystem).lowercase() to it.name.lowercase() }
            .take(max.coerceIn(1, 30))
        if (deps.isEmpty()) {
            return "لم أجد اعتماديات معروفة تحت: " + path.ifBlank { "مساحة العمل" }
        }
        val results = deps.map { dep ->
            lookup(dep.ecosystem, dep.name, dep.version, true)
        }
        val outdated = results.filter { it.error == null && it.outdated }
        val fresh = results.filter { it.error == null && it.level == "same" }
        val errors = results.filter { it.error != null }
        return buildString {
            appendLine("📚 فحص إصدارات المكتبات (${results.size} مكتبة):")
            appendLine()
            results.forEach { info ->
                if (info.error != null) {
                    appendLine("• ${info.ecosystem}/${info.name}: ⚠️ ${info.error}")
                } else {
                    val marker = if (info.outdated) "⬆️" else "✅"
                    append("• $marker ${info.ecosystem}/${info.name}: ${info.current.ifBlank { "?" }} → ${info.latest}")
                    if (info.outdated) append(" (${levelLabel(info.level)})")
                    if (info.license.isNotBlank()) append(" · ${info.license}")
                    appendLine()
                    if (info.summary.isNotBlank()) appendLine("   ${info.summary}")
                }
            }
            appendLine()
            append("النتيجة: ${fresh.size} محدّثة، ${outdated.size} تحتاج ترقية، ${errors.size} تعذّر فحصها.")
            if (outdated.isNotEmpty()) {
                appendLine()
                append("⚠️ راجع الترقيات الرئيسية قبل التطبيق؛ قد تتضمن تغييرات كاسرة.")
            }
        }
    }

    fun lookupText(ecosystem: String, name: String, current: String, online: Boolean): String {
        if (!online) return "⚠️ الفحص يحتاج اتصالاً بالإنترنت."
        val info = lookup(ecosystem, name, current, true)
        if (info.error != null) return "⚠️ ${info.name}: ${info.error}"
        return buildString {
            appendLine("📦 ${info.ecosystem}/${info.name}")
            appendLine("المثبّت: ${info.current.ifBlank { "غير محدد" }}")
            appendLine("الأحدث: ${info.latest} (${levelLabel(info.level)})")
            if (info.license.isNotBlank()) appendLine("الترخيص: ${info.license}")
            if (info.summary.isNotBlank()) appendLine("الوصف: ${info.summary}")
            append("المصدر: ${info.url}")
        }
    }

    private fun levelLabel(level: String): String = when (level) {
        "same" -> "محدّث"
        "major" -> "ترقية رئيسية"
        "minor" -> "ترقية فرعية"
        "patch" -> "ترقية إصلاح"
        else -> "مقارنة غير متاحة"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun httpText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
            setRequestProperty("User-Agent", LLM_USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw LlmException(status, "تعذّر جلب $url")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2_000_000) }
        } finally {
            connection.disconnect()
        }
    }
}

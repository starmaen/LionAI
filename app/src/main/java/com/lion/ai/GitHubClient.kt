package com.lion.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubException(val status: Int, message: String) : Exception(message)

/**
 * عميل GitHub REST صغير لا يحتاج أي مكتبة خارجية.
 * كل الدوال ترمي GitHubException عند فشل الطلب، وتعيد بيانات جاهزة للعرض أو للأدوات.
 */
object GitHubClient {
    private const val API = "https://api.github.com"
    private const val UA = "LionAI/1.7 (Android)"

    data class Repo(
        val fullName: String,
        val description: String,
        val private: Boolean,
        val defaultBranch: String,
        val updatedAt: String,
        val language: String,
        val stars: Int,
        val htmlUrl: String,
        val forks: Int = 0,
        val openIssues: Int = 0,
        val isFork: Boolean = false,
        val createdAt: String = "",
        val size: Int = 0,
        val owner: String = "",
        val avatarUrl: String = ""
    )

    data class Entry(val name: String, val path: String, val type: String, val size: Long)

    data class FileData(val path: String, val sha: String, val text: String, val binary: Boolean)

    data class Issue(
        val number: Int,
        val title: String,
        val state: String,
        val user: String,
        val body: String,
        val comments: Int,
        val htmlUrl: String,
        val isPull: Boolean,
        val labels: List<String> = emptyList(),
        val createdAt: String = ""
    )

    data class Pull(
        val number: Int,
        val title: String,
        val state: String,
        val user: String,
        val head: String,
        val base: String,
        val htmlUrl: String,
        val createdAt: String = ""
    )

    data class Comment(val user: String, val body: String, val createdAt: String)

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String =
        path.split("/").filter { it.isNotBlank() }.joinToString("/") { encode(it) }

    private fun request(
        path: String,
        token: String,
        method: String = "GET",
        body: JSONObject? = null
    ): String {
        if (token.isBlank()) throw GitHubException(401, "أضف توكن GitHub من الإعدادات أولاً")
        val connection = (URL(if (path.startsWith("http")) path else API + path)
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(raw).optString("message") }
                    .getOrDefault("").ifBlank { raw.take(300).ifBlank { "خطأ غير معروف" } }
                throw GitHubException(status, message)
            }
            return raw
        } finally {
            connection.disconnect()
        }
    }

    private fun repoFromJson(o: JSONObject): Repo = Repo(
        fullName = o.optString("full_name"),
        description = o.optString("description"),
        private = o.optBoolean("private"),
        defaultBranch = o.optString("default_branch", "main"),
        updatedAt = o.optString("updated_at"),
        language = o.optString("language"),
        stars = o.optInt("stargazers_count"),
        htmlUrl = o.optString("html_url"),
        forks = o.optInt("forks_count"),
        openIssues = o.optInt("open_issues_count"),
        isFork = o.optBoolean("fork"),
        createdAt = o.optString("created_at"),
        size = o.optInt("size"),
        owner = o.optJSONObject("owner")?.optString("login").orEmpty(),
        avatarUrl = o.optJSONObject("owner")?.optString("avatar_url").orEmpty()
    )

    private fun issueFromJson(o: JSONObject): Issue = Issue(
        number = o.optInt("number"),
        title = o.optString("title"),
        state = o.optString("state"),
        user = o.optJSONObject("user")?.optString("login").orEmpty(),
        body = o.optString("body"),
        comments = o.optInt("comments"),
        htmlUrl = o.optString("html_url"),
        isPull = o.has("pull_request"),
        labels = o.optJSONArray("labels")?.let { labels ->
            (0 until labels.length()).mapNotNull { labels.optJSONObject(it)?.optString("name") }
        }.orEmpty(),
        createdAt = o.optString("created_at")
    )

    private fun pullFromJson(o: JSONObject): Pull = Pull(
        number = o.optInt("number"),
        title = o.optString("title"),
        state = o.optString("state"),
        user = o.optJSONObject("user")?.optString("login").orEmpty(),
        head = o.optJSONObject("head")?.optString("ref").orEmpty(),
        base = o.optJSONObject("base")?.optString("ref").orEmpty(),
        htmlUrl = o.optString("html_url"),
        createdAt = o.optString("created_at")
    )

    fun whoAmI(token: String): JSONObject = JSONObject(request("/user", token))

    fun listRepos(token: String, perPage: Int = 50): List<Repo> {
        val safe = perPage.coerceIn(1, 100)
        val raw = request("/user/repos?per_page=$safe&sort=updated&affiliation=owner,collaborator,organization_member", token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::repoFromJson) }
    }

    fun getRepo(token: String, owner: String, repo: String): Repo =
        repoFromJson(JSONObject(request("/repos/${encode(owner)}/${encode(repo)}", token)))

    fun createRepo(
        token: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = true,
        gitignoreTemplate: String = "",
        licenseTemplate: String = ""
    ): Repo {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", private)
            .put("auto_init", autoInit)
        if (gitignoreTemplate.isNotBlank()) body.put("gitignore_template", gitignoreTemplate)
        if (licenseTemplate.isNotBlank()) body.put("license_template", licenseTemplate)
        return repoFromJson(JSONObject(request("/user/repos", token, "POST", body)))
    }

    fun listDir(token: String, owner: String, repo: String, path: String = "", ref: String = ""): List<Entry> {
        val suffix = if (ref.isBlank()) "" else "?ref=${encode(ref)}"
        val url = "/repos/${encode(owner)}/${encode(repo)}/contents/" + encodePath(path) + suffix
        val raw = request(url, token)
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Entry(
                name = o.optString("name"),
                path = o.optString("path"),
                type = o.optString("type", "file"),
                size = o.optLong("size")
            )
        }.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
    }

    fun listTree(token: String, owner: String, repo: String, branch: String = "", maxEntries: Int = 400): List<Entry> {
        val ref = branch.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/git/trees/${encode(ref)}?recursive=1",
            token
        )
        val array = JSONObject(raw).optJSONArray("tree") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Entry(
                name = o.optString("path").substringAfterLast('/'),
                path = o.optString("path"),
                type = if (o.optString("type") == "tree") "dir" else "file",
                size = o.optLong("size")
            )
        }.take(maxEntries.coerceIn(1, 2000))
    }

    fun readFile(token: String, owner: String, repo: String, path: String, ref: String = ""): FileData {
        val suffix = if (ref.isBlank()) "" else "?ref=${encode(ref)}"
        val url = "/repos/${encode(owner)}/${encode(repo)}/contents/" + encodePath(path) + suffix
        val o = JSONObject(request(url, token))
        val sha = o.optString("sha")
        val encoded = o.optString("content").replace("\n", "").replace("\r", "")
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        val zeroCount = bytes.take(8192).count { it.toInt() == 0 }
        val binary = zeroCount > 0
        val text = if (binary) "" else runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        return FileData(path = o.optString("path", path), sha = sha, text = text, binary = binary)
    }

    fun writeFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        content: String,
        message: String = "تحديث من Lion AI",
        branch: String = "",
        sha: String? = null
    ): JSONObject {
        val targetBranch = branch.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val existingSha = sha ?: runCatching {
            readFile(token, owner, repo, path, targetBranch).sha
        }.getOrDefault("")
        val body = JSONObject()
            .put("message", message.ifBlank { "تحديث من Lion AI" })
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", targetBranch)
        if (existingSha.isNotBlank() && existingSha != "null") body.put("sha", existingSha)
        val url = "/repos/${encode(owner)}/${encode(repo)}/contents/" + encodePath(path)
        return JSONObject(request(url, token, "PUT", body))
    }

    fun deleteFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        message: String = "حذف من Lion AI",
        branch: String = "",
        sha: String? = null
    ): JSONObject {
        val targetBranch = branch.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val existingSha = sha ?: readFile(token, owner, repo, path, targetBranch).sha
        val body = JSONObject()
            .put("message", message.ifBlank { "حذف من Lion AI" })
            .put("sha", existingSha)
            .put("branch", targetBranch)
        val url = "/repos/${encode(owner)}/${encode(repo)}/contents/" + encodePath(path)
        return JSONObject(request(url, token, "DELETE", body))
    }

    fun listBranches(token: String, owner: String, repo: String): List<String> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/branches?per_page=100", token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
    }

    fun createBranch(token: String, owner: String, repo: String, branch: String, from: String = ""): String {
        val base = from.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val refRaw = request("/repos/${encode(owner)}/${encode(repo)}/git/ref/heads/${encode(base)}", token)
        val sha = JSONObject(refRaw).optJSONObject("object")?.optString("sha").orEmpty()
        val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha)
        request("/repos/${encode(owner)}/${encode(repo)}/git/refs", token, "POST", body)
        return branch
    }

    fun listCommits(token: String, owner: String, repo: String, branch: String = "", path: String = ""): List<String> {
        val params = mutableListOf("per_page=15")
        if (branch.isNotBlank()) params.add("sha=${encode(branch)}")
        if (path.isNotBlank()) params.add("path=${encode(path)}")
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/commits?" + params.joinToString("&"), token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val sha = o.optString("sha").take(8)
            val message = o.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty()
            val author = o.optJSONObject("commit")?.optJSONObject("author")?.optString("name").orEmpty()
            "$sha — $message ($author)"
        }
    }

    fun readme(token: String, owner: String, repo: String): String {
        val candidates = listOf("README.md", "README.MD", "readme.md", "README.rst", "README.txt", "README")
        candidates.forEach { candidate ->
            val text = runCatching { readFile(token, owner, repo, candidate).text }.getOrDefault("")
            if (text.isNotBlank()) return text
        }
        throw GitHubException(404, "لا يوجد README في هذا المستودع")
    }

    data class RepoAnalysis(
        val repo: Repo,
        val branches: List<String>,
        val languages: Map<String, Long>,
        val totalFiles: Int,
        val totalDirectories: Int,
        val fileTypes: Map<String, Int>,
        val readme: String,
        val tree: List<Entry>
    ) {
        fun summary(): String = buildString {
            append("تحليل المستودع: ").append(repo.fullName).append('\n')
            append("الوصف: ").append(repo.description.ifBlank { "لا يوجد" }).append('\n')
            append("النجوم: ").append(repo.stars).append(" | الفوركات: ").append(repo.forks).append('\n')
            append("الفرع الافتراضي: ").append(repo.defaultBranch).append('\n')
            append("الملفات: ").append(totalFiles).append(" | المجلدات: ").append(totalDirectories).append('\n')
            val totalBytes = languages.values.sum().coerceAtLeast(1)
            if (languages.isNotEmpty()) {
                append("اللغات:\n")
                languages.entries.take(8).forEach {
                    append("  ").append(it.key).append(": ")
                        .append(String.format("%.1f", it.value * 100.0 / totalBytes)).append("%\n")
                }
            }
            if (fileTypes.isNotEmpty()) {
                append("أنواع الملفات:\n")
                fileTypes.entries.sortedByDescending { it.value }.take(12).forEach {
                    append("  ").append(it.key).append(": ").append(it.value).append(" ملف\n")
                }
            }
            append("الفروع (").append(branches.size).append("): ").append(branches.take(20).joinToString(", ")).append('\n')
        }
    }

    fun analyzeRepo(token: String, owner: String, repo: String): RepoAnalysis {
        val info = getRepo(token, owner, repo)
        val branches = listBranches(token, owner, repo)
        val tree = listTree(token, owner, repo, info.defaultBranch, 2000)
        val readme = runCatching { readme(token, owner, repo) }.getOrDefault("")
        val languages = runCatching { languages(token, owner, repo) }.getOrDefault(emptyMap())
        val fileTypes = linkedMapOf<String, Int>()
        var files = 0
        val dirs = mutableSetOf<String>()
        tree.forEach { entry ->
            if (entry.type == "dir") {
                dirs.add(entry.path)
            } else {
                files++
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val key = if (ext.isBlank()) "بدون امتداد" else ".$ext"
                fileTypes[key] = (fileTypes[key] ?: 0) + 1
            }
        }
        return RepoAnalysis(info, branches, languages, files, dirs.size, fileTypes, readme, tree)
    }

    fun listIssues(token: String, owner: String, repo: String, state: String = "open"): List<Issue> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/issues?state=${encode(state)}&per_page=40", token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::issueFromJson) }
    }

    fun createIssue(
        token: String,
        owner: String,
        repo: String,
        title: String,
        body: String = "",
        labels: List<String> = emptyList()
    ): Issue {
        val json = JSONObject().put("title", title).put("body", body)
        if (labels.isNotEmpty()) json.put("labels", JSONArray(labels))
        return issueFromJson(
            JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/issues", token, "POST", json))
        )
    }

    fun commentIssue(token: String, owner: String, repo: String, number: Int, body: String): String {
        val json = JSONObject().put("body", body)
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/issues/$number/comments",
            token, "POST", json
        )
        return JSONObject(raw).optString("html_url")
    }

    fun listComments(token: String, owner: String, repo: String, number: Int): List<Comment> {
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/issues/$number/comments?per_page=50",
            token
        )
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Comment(
                user = o.optJSONObject("user")?.optString("login").orEmpty(),
                body = o.optString("body"),
                createdAt = o.optString("created_at")
            )
        }
    }

    fun listPulls(token: String, owner: String, repo: String, state: String = "open"): List<Pull> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/pulls?state=${encode(state)}&per_page=40", token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::pullFromJson) }
    }

    fun createPull(
        token: String,
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String = "",
        body: String = "",
        draft: Boolean = false
    ): Pull {
        val baseBranch = base.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val json = JSONObject()
            .put("title", title)
            .put("head", head)
            .put("base", baseBranch)
            .put("body", body)
            .put("draft", draft)
        return pullFromJson(
            JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/pulls", token, "POST", json))
        )
    }

    fun searchRepos(token: String, query: String, perPage: Int = 10): List<Repo> {
        val safe = perPage.coerceIn(1, 30)
        val raw = request("/search/repositories?q=${encode(query)}&per_page=$safe", token)
        val array = JSONObject(raw).optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::repoFromJson) }
    }

    fun searchCode(token: String, query: String, perPage: Int = 10): List<String> {
        val safe = perPage.coerceIn(1, 30)
        val raw = request("/search/code?q=${encode(query)}&per_page=$safe", token)
        val array = JSONObject(raw).optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            o.optJSONObject("repository")?.optString("full_name").orEmpty() +
                " · " + o.optString("path")
        }
    }

    fun workflowRuns(token: String, owner: String, repo: String): List<String> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/actions/runs?per_page=10", token)
        val array = JSONObject(raw).optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val status = o.optString("status")
            val conclusion = o.optString("conclusion").ifBlank { "—" }
            val name = o.optString("name")
            val branch = o.optString("head_branch")
            "#${o.optInt("run_number")} $name [$branch] — $status/$conclusion"
        }
    }

    fun languages(token: String, owner: String, repo: String): Map<String, Long> {
        val o = JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/languages", token))
        val out = linkedMapOf<String, Long>()
        o.keys().forEach { key -> out[key] = o.optLong(key) }
        return out.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    fun parseFullName(full: String): Pair<String, String> {
        val cleaned = full.trim().removePrefix("https://github.com/").trim('/')
        val parts = cleaned.split("/")
        if (parts.size < 2) throw IllegalArgumentException("اسم المستودع يجب أن يكون owner/repo")
        return parts[0] to parts[1]
    }
}

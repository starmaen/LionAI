package com.lion.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.File
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

    data class Entry(val name: String, val path: String, val type: String, val size: Long, val sha: String = "")

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

    data class WorkflowRun(
        val id: Long,
        val number: Int,
        val name: String,
        val workflowId: Long,
        val status: String,
        val conclusion: String,
        val branch: String,
        val event: String,
        val htmlUrl: String,
        val createdAt: String,
        val headSha: String = ""
    )

    data class WorkflowJob(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String,
        val startedAt: String,
        val completedAt: String,
        val steps: List<Pair<String, String>>
    )

    data class Artifact(val id: Long, val name: String, val size: Long, val expired: Boolean, val createdAt: String)

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

    /** إنشاء مستودع جديد من قالب GitHub جاهز (Template Repository). */
    fun createRepoFromTemplate(
        token: String,
        templateOwner: String,
        templateRepo: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        includeAllBranches: Boolean = false
    ): Repo {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", private)
            .put("include_all_branches", includeAllBranches)
        return repoFromJson(
            JSONObject(
                request(
                    "/repos/${encode(templateOwner)}/${encode(templateRepo)}/generate",
                    token, "POST", body
                )
            )
        )
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
                size = o.optLong("size"),
                sha = o.optString("sha")
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
                size = o.optLong("size"),
                sha = o.optString("sha")
            )
        }.take(maxEntries.coerceIn(1, 5000))
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

    private fun workflowRunFromJson(o: JSONObject): WorkflowRun = WorkflowRun(
        id = o.optLong("id"),
        number = o.optInt("run_number"),
        name = o.optString("name").ifBlank { o.optString("display_title") },
        workflowId = o.optLong("workflow_id"),
        status = o.optString("status"),
        conclusion = o.optString("conclusion").ifBlank { "—" },
        branch = o.optString("head_branch"),
        event = o.optString("event"),
        htmlUrl = o.optString("html_url"),
        createdAt = o.optString("created_at"),
        headSha = o.optString("head_sha")
    )

    fun listWorkflowRuns(
        token: String,
        owner: String,
        repo: String,
        perPage: Int = 10,
        workflowId: Long? = null,
        branch: String = "",
        headSha: String = ""
    ): List<WorkflowRun> {
        val safe = perPage.coerceIn(1, 50)
        val base = if (workflowId != null) {
            "/repos/${encode(owner)}/${encode(repo)}/actions/workflows/$workflowId/runs"
        } else {
            "/repos/${encode(owner)}/${encode(repo)}/actions/runs"
        }
        val query = buildString {
            append("?per_page=").append(safe)
            if (branch.isNotBlank()) append("&branch=").append(encode(branch))
            if (headSha.isNotBlank()) append("&head_sha=").append(encode(headSha))
        }
        val raw = request("$base$query", token)
        val array = JSONObject(raw).optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::workflowRunFromJson) }
    }

    fun workflowRuns(token: String, owner: String, repo: String): List<String> =
        listWorkflowRuns(token, owner, repo).map {
            "#${it.number} ${it.name} [${it.branch}] — ${it.status}/${it.conclusion}"
        }

    fun listWorkflows(token: String, owner: String, repo: String): List<Pair<Long, String>> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/actions/workflows?per_page=50", token)
        val array = JSONObject(raw).optJSONArray("workflows") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            o.optLong("id").takeIf { it > 0 }?.let { it to o.optString("name", "workflow") }
        }
    }

    fun listRunJobs(token: String, owner: String, repo: String, runId: Long): List<WorkflowJob> {
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/jobs?per_page=50",
            token
        )
        val array = JSONObject(raw).optJSONArray("jobs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val steps = o.optJSONArray("steps")?.let { list ->
                (0 until list.length()).mapNotNull { stepIndex ->
                    val step = list.optJSONObject(stepIndex) ?: return@mapNotNull null
                    step.optString("name") to step.optString("conclusion").ifBlank { step.optString("status") }
                }
            }.orEmpty()
            WorkflowJob(
                id = o.optLong("id"),
                name = o.optString("name"),
                status = o.optString("status"),
                conclusion = o.optString("conclusion").ifBlank { "—" },
                startedAt = o.optString("started_at"),
                completedAt = o.optString("completed_at"),
                steps = steps
            )
        }
    }

    private fun fetchBytes(path: String, token: String, maxBytes: Int): ByteArray {
        var current = URL(if (path.startsWith("http")) path else API + path)
        var hops = 0
        while (hops < 6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                setRequestProperty("User-Agent", UA)
                if (current.host == "api.github.com") {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw GitHubException(status, "تحويل بلا عنوان")
                    current = URL(location)
                    hops++
                    continue
                }
                if (status !in 200..299) {
                    val raw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw GitHubException(status, raw.take(300).ifBlank { "فشل الطلب ($status)" })
                }
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total < maxBytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                }
                return output.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
        throw GitHubException(508, "عدد تحويلات كبير")
    }

    fun jobLog(token: String, owner: String, repo: String, jobId: Long, maxBytes: Int = 800_000): String {
        val bytes = fetchBytes(
            "/repos/${encode(owner)}/${encode(repo)}/actions/jobs/$jobId/logs",
            token,
            maxBytes.coerceIn(10_000, 2_000_000)
        )
        return String(bytes, Charsets.UTF_8)
    }

    data class JobAnnotation(val path: String?, val startLine: Int?, val endLine: Int?, val title: String?, val message: String?)

    /** بديل عام عند تعذّر جلب السجل الكامل (يتطلب administrator): يعرض 메시جات الفشل من annotations. */
    fun jobAnnotations(token: String, owner: String, repo: String, jobId: Long): List<JobAnnotation> {
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/check-runs/$jobId/annotations?per_page=100",
            token
        )
        val array = JSONObject(raw).optJSONArray("annotations") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            JobAnnotation(
                path = o.optString("path").ifBlank { null },
                startLine = o.optInt("start_line").takeIf { it > 0 },
                endLine = o.optInt("end_line").takeIf { it > 0 },
                title = o.optString("title").ifBlank { null },
                message = o.optString("message").ifBlank { null }
            )
        }
    }

    fun rerunRun(token: String, owner: String, repo: String, runId: Long) {
        request("/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/rerun", token, "POST")
    }

    fun cancelRun(token: String, owner: String, repo: String, runId: Long) {
        request("/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/cancel", token, "POST")
    }

    fun dispatchWorkflow(
        token: String,
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ) {
        val body = JSONObject().put("ref", ref.ifBlank { getRepo(token, owner, repo).defaultBranch })
        if (inputs.isNotEmpty()) {
            val obj = JSONObject()
            inputs.forEach { (key, value) -> obj.put(key, value) }
            body.put("inputs", obj)
        }
        request("/repos/${encode(owner)}/${encode(repo)}/actions/workflows/$workflowId/dispatches", token, "POST", body)
    }

    fun listArtifacts(token: String, owner: String, repo: String, runId: Long): List<Artifact> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId/artifacts", token)
        val array = JSONObject(raw).optJSONArray("artifacts") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Artifact(
                id = o.optLong("id"),
                name = o.optString("name"),
                size = o.optLong("size_in_bytes"),
                expired = o.optBoolean("expired"),
                createdAt = o.optString("created_at")
            )
        }
    }

    private fun downloadTo(path: String, token: String, target: File, maxBytes: Long): File {
        var current = URL(if (path.startsWith("http")) path else API + path)
        var hops = 0
        while (hops < 6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 120_000
                useCaches = false
                setRequestProperty("User-Agent", UA)
                if (current.host == "api.github.com") {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw GitHubException(status, "تحويل بلا عنوان")
                    current = URL(location)
                    hops++
                    continue
                }
                if (status !in 200..299) {
                    throw GitHubException(status, "فشل التنزيل ($status)")
                }
                target.parentFile?.mkdirs()
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) throw IllegalStateException("الملف أكبر من الحد المسموح")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return target
            } finally {
                connection.disconnect()
            }
        }
        throw GitHubException(508, "عدد تحويلات كبير")
    }

    fun downloadArtifact(
        token: String,
        owner: String,
        repo: String,
        artifactId: Long,
        target: File
    ): File = downloadTo(
        "/repos/${encode(owner)}/${encode(repo)}/actions/artifacts/$artifactId/zip",
        token,
        target,
        120L * 1024 * 1024
    )

    fun downloadRepoZip(
        token: String,
        owner: String,
        repo: String,
        ref: String,
        target: File
    ): File {
        val branch = ref.ifBlank { getRepo(token, owner, repo).defaultBranch }
        return downloadTo(
            "/repos/${encode(owner)}/${encode(repo)}/zipball/${encode(branch)}",
            token,
            target,
            200L * 1024 * 1024
        )
    }

    private fun safeRepoPath(path: String): String {
        val clean = path.trim().removePrefix("/")
        if (clean.isBlank() || clean.split('/').any { it.isBlank() || it == ".." || it == "." }) {
            throw IllegalArgumentException("مسار ملف غير صالح: $path")
        }
        return clean
    }

    /** إنشاء commit واحد لعدة ملفات وحذف دفعة واحدة عبر Git Data API. */
    fun commitFiles(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        files: Map<String, String>,
        deletions: List<String> = emptyList()
    ): String {
        if (files.isEmpty() && deletions.isEmpty()) throw IllegalArgumentException("لا توجد تغييرات")
        val targetBranch = branch.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val repoPath = "/repos/${encode(owner)}/${encode(repo)}"
        val refRaw = request("$repoPath/git/ref/heads/${encode(targetBranch)}", token)
        val headSha = JSONObject(refRaw).optJSONObject("object")?.optString("sha").orEmpty()
        if (headSha.isBlank()) throw GitHubException(404, "الفرع غير موجود: $targetBranch")
        val commitRaw = request("$repoPath/git/commits/$headSha", token)
        val baseTree = JSONObject(commitRaw).optJSONObject("tree")?.optString("sha").orEmpty()
        val tree = JSONArray()
        files.forEach { (rawPath, content) ->
            val path = safeRepoPath(rawPath)
            val blob = JSONObject(
                request(
                    "$repoPath/git/blobs", token, "POST",
                    JSONObject().put("content", content).put("encoding", "utf-8")
                )
            )
            tree.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.optString("sha"))
            )
        }
        deletions.forEach { rawPath ->
            tree.put(
                JSONObject()
                    .put("path", safeRepoPath(rawPath))
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        }
        val treeBody = JSONObject().put("tree", tree)
        if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
        val newTree = JSONObject(request("$repoPath/git/trees", token, "POST", treeBody))
            .optString("sha")
        val parents = JSONArray().put(headSha)
        val commitBody = JSONObject()
            .put("message", message.ifBlank { "تحديث من Lion AI" })
            .put("tree", newTree)
            .put("parents", parents)
        val newCommit = JSONObject(request("$repoPath/git/commits", token, "POST", commitBody))
            .optString("sha")
        request(
            "$repoPath/git/refs/heads/${encode(targetBranch)}", token, "PATCH",
            JSONObject().put("sha", newCommit).put("force", false)
        )
        return newCommit
    }

    fun getWorkflowRun(token: String, owner: String, repo: String, runId: Long): WorkflowRun =
        workflowRunFromJson(JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/actions/runs/$runId", token)))

    fun compare(token: String, owner: String, repo: String, base: String, head: String): String {
        val raw = request(
            "/repos/${encode(owner)}/${encode(repo)}/compare/${encode(base)}...${encode(head)}",
            token
        )
        val o = JSONObject(raw)
        val builder = StringBuilder()
        builder.append("مقارنة $base...$head — ${o.optString("status")}, ")
            .append("${o.optInt("ahead_by")} commits أماماً / ${o.optInt("behind_by")} خلفاً\n")
        val commits = o.optJSONArray("commits") ?: JSONArray()
        for (i in 0 until commits.length().coerceAtMost(30)) {
            val c = commits.optJSONObject(i) ?: continue
            builder.append("• ").append(c.optString("sha").take(8)).append(" — ")
                .append(c.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty())
                .append('\n')
        }
        val files = o.optJSONArray("files") ?: JSONArray()
        if (files.length() > 0) builder.append("\nالملفات:\n")
        for (i in 0 until files.length().coerceAtMost(60)) {
            val f = files.optJSONObject(i) ?: continue
            builder.append("• [").append(f.optString("status")).append("] ").append(f.optString("filename"))
                .append(" (+").append(f.optInt("additions")).append("/-").append(f.optInt("deletions")).append(")\n")
            val patch = f.optString("patch", "")
            if (patch.isNotBlank()) builder.append(patch.lineSequence().take(40).joinToString("\n")).append("\n")
        }
        return builder.toString().take(16_000)
    }

    fun pullFiles(token: String, owner: String, repo: String, number: Int): String {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/pulls/$number/files?per_page=100", token)
        val array = JSONArray(raw)
        return (0 until array.length()).joinToString("\n") { index ->
            val f = array.optJSONObject(index) ?: return@joinToString ""
            val patch = f.optString("patch", "").lineSequence().take(60).joinToString("\n")
            "• [${f.optString("status")}] ${f.optString("filename")} (+${f.optInt("additions")}/-${f.optInt("deletions")})" +
                if (patch.isBlank()) "" else "\n$patch"
        }.take(16_000)
    }

    fun mergePull(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        commitTitle: String = "",
        method: String = "merge"
    ): String {
        val mergeMethod = when (method.lowercase()) {
            "squash", "rebase" -> method.lowercase()
            else -> "merge"
        }
        val body = JSONObject().put("merge_method", mergeMethod)
        if (commitTitle.isNotBlank()) body.put("commit_title", commitTitle)
        val o = JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/pulls/$number/merge", token, "PUT", body))
        return if (o.optBoolean("merged")) {
            "✅ دُمج PR #$number (${o.optString("sha").take(8)}): ${o.optString("message")}"
        } else {
            "لم يُدمج: ${o.optString("message")}"
        }
    }

    fun reviewPull(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        event: String,
        body: String
    ): String {
        val normalized = when (event.uppercase()) {
            "APPROVE", "APPROVED" -> "APPROVE"
            "REQUEST_CHANGES", "REQUEST-CHANGES", "CHANGES_REQUESTED" -> "REQUEST_CHANGES"
            else -> "COMMENT"
        }
        val json = JSONObject().put("event", normalized).put("body", body)
        val o = JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/pulls/$number/reviews", token, "POST", json))
        return "✅ أُرسلت المراجعة ($normalized) — ${o.optString("html_url")}"
    }

    fun closeIssue(token: String, owner: String, repo: String, number: Int): String {
        val o = JSONObject(
            request(
                "/repos/${encode(owner)}/${encode(repo)}/issues/$number",
                token, "PATCH", JSONObject().put("state", "closed")
            )
        )
        return "✅ أُغلق #$number (${o.optString("title")})"
    }

    fun listTags(token: String, owner: String, repo: String): List<String> {
        val raw = request("/repos/${encode(owner)}/${encode(repo)}/tags?per_page=100", token)
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    fun createTag(
        token: String,
        owner: String,
        repo: String,
        name: String,
        from: String = ""
    ): String {
        val ref = from.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val sha = if (Regex("[0-9a-fA-F]{40}").matches(ref)) {
            ref
        } else {
            val refRaw = request("/repos/${encode(owner)}/${encode(repo)}/git/ref/heads/${encode(ref)}", token)
            JSONObject(refRaw).optJSONObject("object")?.optString("sha").orEmpty()
        }
        if (sha.isBlank()) throw GitHubException(404, "تعذّر تحديد commit للوسم")
        val body = JSONObject().put("ref", "refs/tags/${name.trim()}").put("sha", sha)
        val o = JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/git/refs", token, "POST", body))
        return "✅ أُنشئ الوسم ${name.trim()} عند ${sha.take(8)} (${o.optString("ref")})"
    }

    fun createRelease(
        token: String,
        owner: String,
        repo: String,
        tag: String,
        name: String = "",
        body: String = "",
        draft: Boolean = false,
        prerelease: Boolean = false
    ): String {
        val json = JSONObject()
            .put("tag_name", tag.trim())
            .put("name", name.ifBlank { tag.trim() })
            .put("body", body)
            .put("draft", draft)
            .put("prerelease", prerelease)
        val o = JSONObject(request("/repos/${encode(owner)}/${encode(repo)}/releases", token, "POST", json))
        return "✅ أُنشئ الإصدار ${o.optString("tag_name")} — ${o.optString("html_url")}"
    }

    fun deleteBranch(token: String, owner: String, repo: String, branch: String) {
        request("/repos/${encode(owner)}/${encode(repo)}/git/refs/heads/${encode(branch)}", token, "DELETE")
    }

    /** بصمة git blob القياسية (SHA-1) للمقارنة مع شجرة GitHub دون تنزيل المحتوى. */
    fun gitBlobSha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}".toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * يرفع مجلد مساحة عمل كاملاً إلى GitHub عبر Git Data API، بمقارنة بصمات
     * blob لتجنب رفع الملفات غير المتغيرة. يدعم الملفات الثنائية والحذف الاختياري.
     */
    fun commitWorkspace(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        localRoot: File,
        deleteMissing: Boolean = false,
        dryRun: Boolean = false,
        maxFiles: Int = 400,
        maxBytesPerFile: Long = 20L * 1024 * 1024
    ): String {
        if (!localRoot.isDirectory) throw IllegalArgumentException("مجلد مساحة العمل غير موجود: ${localRoot.path}")
        val targetBranch = branch.ifBlank { getRepo(token, owner, repo).defaultBranch }
        val remote = listTree(token, owner, repo, targetBranch, maxEntries = 5000)
            .filter { it.type == "file" && it.sha.isNotBlank() }
            .associate { it.path to it.sha }
        val root = localRoot.canonicalFile
        val excludes = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".venv", "venv",
            "dist", "target", ".dart_tool", "Pods", ".next", ".lion-backup", ".lion-tmp"
        )
        val local = linkedMapOf<String, File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            children.sortedBy { it.name }.forEach { child ->
                if (child.name in excludes) return@forEach
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (!canonical.path.startsWith(root.path + File.separator) && canonical != root) return@forEach
                if (child.isDirectory) walk(child) else if (child.isFile && !child.name.startsWith(".lion-")) {
                    val rel = canonical.relativeTo(root).path.replace(File.separatorChar, '/')
                    local[rel] = child
                }
            }
        }
        walk(root)

        val changed = mutableListOf<Pair<String, File>>()
        local.forEach { (path, file) ->
            if (file.length() > maxBytesPerFile) {
                throw IllegalArgumentException("الملف $path أكبر من الحد (${maxBytesPerFile / 1024 / 1024}MB)")
            }
            val bytes = file.readBytes()
            if (remote[path] != gitBlobSha(bytes)) changed.add(path to file)
        }
        val deleted = if (deleteMissing) remote.keys.filterNot { local.containsKey(it) } else emptyList()
        if (changed.isEmpty() && deleted.isEmpty()) return "لا توجد تغييرات لرفعها إلى $owner/$repo@$targetBranch"
        if (changed.size + deleted.size > maxFiles.coerceIn(1, 500)) {
            throw IllegalArgumentException("عدد الملفات المتغيرة كبير جداً (${changed.size + deleted.size})")
        }
        val summary = buildString {
            append("📦 $owner/$repo@$targetBranch\n")
            append("متغير/جديد: ${changed.size} — محذوف: ${deleted.size}\n")
            changed.take(60).forEach { (path, _) -> append("• ✏️ ").append(path).append('\n') }
            if (changed.size > 60) append("• … ${changed.size - 60} ملفاً آخر\n")
            deleted.take(40).forEach { append("• 🗑️ ").append(it).append('\n') }
        }
        if (dryRun) return "$summary\n(تجريبي فقط — لم يُرفع شيء)"

        val repoPath = "/repos/${encode(owner)}/${encode(repo)}"
        val refRaw = request("$repoPath/git/ref/heads/${encode(targetBranch)}", token)
        val headSha = JSONObject(refRaw).optJSONObject("object")?.optString("sha").orEmpty()
        if (headSha.isBlank()) throw GitHubException(404, "الفرع غير موجود: $targetBranch")
        val baseTree = JSONObject(request("$repoPath/git/commits/$headSha", token))
            .optJSONObject("tree")?.optString("sha").orEmpty()

        val tree = JSONArray()
        changed.forEach { (path, file) ->
            val content = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val blob = JSONObject(
                request(
                    "$repoPath/git/blobs", token, "POST",
                    JSONObject().put("content", content).put("encoding", "base64")
                )
            )
            tree.put(
                JSONObject()
                    .put("path", safeRepoPath(path))
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.optString("sha"))
            )
        }
        deleted.forEach { path ->
            tree.put(
                JSONObject()
                    .put("path", safeRepoPath(path))
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        }
        val treeBody = JSONObject().put("tree", tree)
        if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
        val newTree = JSONObject(request("$repoPath/git/trees", token, "POST", treeBody)).optString("sha")
        val commitBody = JSONObject()
            .put("message", message.ifBlank { "تحديث من Lion AI" })
            .put("tree", newTree)
            .put("parents", JSONArray().put(headSha))
        val newCommit = JSONObject(request("$repoPath/git/commits", token, "POST", commitBody))
            .optString("sha")
        request(
            "$repoPath/git/refs/heads/${encode(targetBranch)}", token, "PATCH",
            JSONObject().put("sha", newCommit).put("force", false)
        )
        return "$summary\n✅ commit جديد: $newCommit"
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

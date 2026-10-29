package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** قراءة المشروع من مساحة العمل إلى لقطة نصية قابلة للفحص الميكانيكي. */
object WorkspaceSnapshot {
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "apk", "aab",
        "jar", "class", "dex", "so", "ttf", "otf", "mp3", "mp4", "wav", "ogg", "webm"
    )
    private val IGNORED = setOf(".git", ".gradle", "build", "out", "node_modules", ".idea")

    fun of(
        context: Context,
        rootPath: String = "",
        maxFiles: Int = 4000,
        maxBytes: Int = 512_000
    ): ProjectSnapshot {
        val root = FileTools.file(context, rootPath)
        if (!root.exists()) {
            return ProjectSnapshot(emptyMap(), emptySet(), emptySet())
        }
        if (root.isFile) {
            val rel = FileTools.relative(context, root)
            val text = if (root.length() <= maxBytes && root.extension.lowercase() !in BINARY_EXT) {
                runCatching { root.readText(Charsets.UTF_8) }.getOrDefault("")
            } else ""
            return ProjectSnapshot(mapOf(rel to text), emptySet(), emptySet())
        }
        val files = linkedMapOf<String, String>()
        val dirs = linkedSetOf<String>()
        val filesParents = linkedSetOf<String>()
        root.walkTopDown()
            .onEnter { dir ->
                val keep = dir == root || dir.name !in IGNORED
                if (keep && dir.isDirectory && dir != root) dirs.add(relativeDir(context, dir))
                keep
            }
            .filter { it.isFile && it.name != ".DS_Store" }
            .take(maxFiles.coerceIn(1, 10_000))
            .forEach { file ->
                val rel = FileTools.relative(context, file)
                filesParents.addAll(parentChain(rel))
                val ext = file.extension.lowercase()
                val text = if (file.length() <= maxBytes && ext !in BINARY_EXT) {
                    runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                } else {
                    ""
                }
                files[rel] = text
            }
        val empty = dirs.filterNot { dir ->
            filesParents.any { it == dir || it.startsWith("$dir/") }
        }.toSet()
        return ProjectSnapshot(files, dirs, empty)
    }

    private fun parentChain(path: String): List<String> {
        val normalized = ManifestPaths.normalize(path)
        val parts = normalized.split('/')
        val result = mutableListOf<String>()
        for (i in 1 until parts.size) result.add(parts.take(i).joinToString("/"))
        return result
    }

    private fun relativeDir(context: Context, dir: File): String =
        FileTools.relative(context, dir).replace('\\', '/').trim('/')
}

object ManifestStore {
    private const val PREFIX = "manifest_"
    private const val SIG_PREFIX = "test_sigs_"
    private const val HMAC_KEY = "control_hmac_key"

    fun read(prefs: SharedPreferences, chatId: String): BuildManifest? {
        if (chatId.isBlank()) return null
        return runCatching { fromJson(prefs.getString(PREFIX + chatId, "") ?: "") }.getOrNull()
    }

    fun latest(prefs: SharedPreferences): BuildManifest? =
        prefs.all.keys.filter { it.startsWith(PREFIX) }
            .mapNotNull { key -> runCatching { fromJson(prefs.getString(key, "") ?: "") }.getOrNull() }
            .maxByOrNull { it.updatedAt }

    fun write(prefs: SharedPreferences, chatId: String, manifest: BuildManifest) {
        val updated = manifest.copy(updatedAt = System.currentTimeMillis())
        prefs.edit().putString(PREFIX + chatId, toJson(updated)).apply()
    }

    fun approve(prefs: SharedPreferences, chatId: String): BuildManifest? {
        val current = read(prefs, chatId) ?: return null
        val updated = current.copy(approved = true, updatedAt = System.currentTimeMillis())
        write(prefs, chatId, updated)
        return updated
    }

    fun markFile(prefs: SharedPreferences, chatId: String, path: String, status: String): BuildManifest? {
        val current = read(prefs, chatId) ?: return null
        val normalized = ManifestPaths.normalize(path)
        val updated = current.copy(
            files = current.files.map {
                if (ManifestPaths.normalize(it.path) == normalized) it.copy(status = status) else it
            },
            updatedAt = System.currentTimeMillis()
        )
        write(prefs, chatId, updated)
        return updated
    }

    fun delete(prefs: SharedPreferences, chatId: String) {
        prefs.edit().remove(PREFIX + chatId).remove(SIG_PREFIX + chatId).apply()
    }

    fun hmacKey(prefs: SharedPreferences): String {
        val existing = prefs.getString(HMAC_KEY, "") ?: ""
        if (existing.isNotBlank()) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(HMAC_KEY, generated).apply()
        return generated
    }

    fun signatures(prefs: SharedPreferences, chatId: String): Map<String, String> {
        val raw = prefs.getString(SIG_PREFIX + chatId, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val result = linkedMapOf<String, String>()
            obj.keys().forEach { key -> result[key] = obj.optString(key) }
            result
        }.getOrDefault(emptyMap())
    }

    fun signTests(prefs: SharedPreferences, chatId: String, snapshot: ProjectSnapshot): Map<String, String> {
        val key = hmacKey(prefs)
        val signatures = TestIntegrity.signAll(snapshot, key)
        val obj = JSONObject()
        signatures.forEach { (path, sig) -> obj.put(path, sig) }
        prefs.edit().putString(SIG_PREFIX + chatId, obj.toString()).apply()
        return signatures
    }

    fun gate(prefs: SharedPreferences, chatId: String, root: String = ""): GateReport {
        val manifest = read(prefs, chatId)
        val actualRoot = root.ifBlank { manifest?.root.orEmpty() }
        val snapshot = WorkspaceSnapshot.of(ContextHolder.require(), actualRoot)
        return ControlPlane.evaluate(
            snapshot = snapshot,
            manifest = manifest,
            signatures = signatures(prefs, chatId),
            hmacKey = hmacKey(prefs)
        )
    }

    fun toJson(manifest: BuildManifest): String {
        val files = JSONArray()
        manifest.files.forEach { file ->
            files.put(
                JSONObject()
                    .put("path", file.path)
                    .put("purpose", file.purpose)
                    .put("contract", file.contract)
                    .put("required", file.required)
                    .put("entry", file.entry)
                    .put("status", file.status)
            )
        }
        val tasks = JSONArray()
        manifest.tasks.forEach { task ->
            tasks.put(
                JSONObject()
                    .put("id", task.id)
                    .put("role", task.role)
                    .put("title", task.title)
                    .put("depends_on", JSONArray(task.dependsOn))
                    .put("files", JSONArray(task.files))
                    .put("status", task.status)
            )
        }
        return JSONObject()
            .put("project", manifest.project)
            .put("root", manifest.root)
            .put("entry_points", JSONArray(manifest.entryPoints))
            .put("files", files)
            .put("tasks", tasks)
            .put("approved", manifest.approved)
            .put("created_at", manifest.createdAt)
            .put("updated_at", manifest.updatedAt)
            .toString(2)
    }

    fun fromJson(raw: String): BuildManifest? {
        if (raw.isBlank()) return null
        val obj = JSONObject(raw)
        val project = obj.optString("project", "")
        if (project.isBlank()) return null
        val files = mutableListOf<ManifestFile>()
        obj.optJSONArray("files")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path", "")
                if (path.isBlank()) continue
                files.add(
                    ManifestFile(
                        path = path,
                        purpose = item.optString("purpose", ""),
                        contract = item.optString("contract", ""),
                        required = item.optBoolean("required", true),
                        entry = item.optBoolean("entry", false),
                        status = item.optString("status", "pending")
                    )
                )
            }
        }
        val tasks = mutableListOf<ManifestTask>()
        obj.optJSONArray("tasks")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                tasks.add(
                    ManifestTask(
                        id = id,
                        role = item.optString("role", "implementer"),
                        title = item.optString("title", ""),
                        dependsOn = stringList(item.optJSONArray("depends_on")),
                        files = stringList(item.optJSONArray("files")),
                        status = item.optString("status", "pending")
                    )
                )
            }
        }
        return BuildManifest(
            project = project,
            root = obj.optString("root", ""),
            entryPoints = stringList(obj.optJSONArray("entry_points")),
            files = files,
            tasks = tasks,
            approved = obj.optBoolean("approved", false),
            createdAt = obj.optLong("created_at", System.currentTimeMillis()),
            updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

}

/** يحقن سياق التطبيق مرة واحدة من MainActivity/LionApp لتفادي تمرير Context في كل طبقة. */
object ContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context = appContext
        ?: throw IllegalStateException("لم يُهيأ سياق التطبيق بعد")

    fun peek(): Context? = appContext
}

enum class PipelinePhase(val id: String, val label: String) {
    PLAN("plan", "التخطيط"),
    MANIFEST("manifest", "اعتماد Manifest"),
    IMPLEMENT("implement", "التنفيذ"),
    GATE("gate", "بوابة الجودة"),
    REVIEW("review", "المراجعة"),
    QA("qa", "الاختبار"),
    BUILD("build", "البناء"),
    DONE("done", "التسليم");

    companion object {
        fun fromId(id: String?): PipelinePhase = entries.firstOrNull { it.id == id } ?: PLAN
        fun next(current: PipelinePhase): PipelinePhase = when (current) {
            PLAN -> MANIFEST
            MANIFEST -> IMPLEMENT
            IMPLEMENT -> GATE
            GATE -> REVIEW
            REVIEW -> QA
            QA -> BUILD
            BUILD -> DONE
            DONE -> DONE
        }
    }
}

data class PipelineState(
    val chatId: String,
    val project: String,
    val root: String,
    val phase: PipelinePhase = PipelinePhase.PLAN,
    val manifestApproved: Boolean = false,
    val lastGateOk: Boolean = false,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

object PipelineStore {
    private const val PREFIX = "pipeline_"

    fun read(prefs: SharedPreferences, chatId: String): PipelineState? {
        val raw = prefs.getString(PREFIX + chatId, "") ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            PipelineState(
                chatId = chatId,
                project = obj.optString("project", ""),
                root = obj.optString("root", ""),
                phase = PipelinePhase.fromId(obj.optString("phase", "plan")),
                manifestApproved = obj.optBoolean("manifest_approved", false),
                lastGateOk = obj.optBoolean("last_gate_ok", false),
                note = obj.optString("note", ""),
                updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun write(prefs: SharedPreferences, state: PipelineState) {
        val obj = JSONObject()
            .put("project", state.project)
            .put("root", state.root)
            .put("phase", state.phase.id)
            .put("manifest_approved", state.manifestApproved)
            .put("last_gate_ok", state.lastGateOk)
            .put("note", state.note)
            .put("updated_at", System.currentTimeMillis())
        prefs.edit().putString(PREFIX + state.chatId, obj.toString()).apply()
    }

    fun delete(prefs: SharedPreferences, chatId: String) {
        prefs.edit().remove(PREFIX + chatId).apply()
    }

    /** لا كتابة كود لمشروع بدأت له دورة حياة قبل اعتماد Manifest. */
    fun canWrite(prefs: SharedPreferences, chatId: String): Boolean {
        if (chatId.isBlank()) return true
        val state = read(prefs, chatId)
            ?: return ManifestStore.read(prefs, chatId)?.approved == true
        return state.manifestApproved &&
            state.phase in setOf(
                PipelinePhase.IMPLEMENT,
                PipelinePhase.GATE,
                PipelinePhase.REVIEW,
                PipelinePhase.QA,
                PipelinePhase.BUILD
            )
    }
}

data class CheckpointInfo(
    val chatId: String,
    val task: String,
    val mode: String,
    val steps: Int,
    val files: Int,
    val pending: String?,
    val updatedAt: Long,
    val completed: Boolean
)

/** نقاط استئناف دائمة تعيش بعد إغلاق التطبيق وبعد تبديل المزود. */
object CheckpointStore {
    private const val PREFIX = "ckpt_"

    fun save(prefs: SharedPreferences, session: AgentSession) {
        prefs.edit().putString(PREFIX + session.chatId, session.toJson().toString()).apply()
    }

    fun load(prefs: SharedPreferences, chatId: String): AgentSession? {
        val raw = prefs.getString(PREFIX + chatId, "") ?: return null
        return runCatching { AgentSession.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun has(prefs: SharedPreferences, chatId: String): Boolean =
        prefs.contains(PREFIX + chatId)

    fun delete(prefs: SharedPreferences, chatId: String) {
        prefs.edit().remove(PREFIX + chatId).apply()
    }

    fun list(prefs: SharedPreferences): List<CheckpointInfo> =
        prefs.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { key ->
            val chatId = key.removePrefix(PREFIX)
            val session = load(prefs, chatId) ?: return@mapNotNull null
            CheckpointInfo(
                chatId = chatId,
                task = session.lastTask,
                mode = session.mode,
                steps = session.stepCount(),
                files = session.touchedFiles().size,
                pending = session.pendingQuestion,
                updatedAt = session.updatedAt,
                completed = session.completed
            )
        }.sortedByDescending { it.updatedAt }
}

data class MemoryEntry(
    val id: String,
    val project: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** ذاكرة قرارات معمارية قصيرة تعيش عبر المزودات والجلسات. */
object ProjectMemory {
    private const val KEY = "project_memory_v1"
    private const val MAX = 600

    fun save(
        prefs: SharedPreferences,
        project: String,
        title: String,
        body: String,
        tags: List<String> = emptyList()
    ): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString().take(8),
            project = project.trim(),
            title = title.trim().take(160),
            body = body.trim().take(4000),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }.take(12)
        )
        val list = all(prefs).toMutableList()
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)
        write(prefs, list)
        return entry
    }

    fun all(prefs: SharedPreferences): List<MemoryEntry> {
        val raw = prefs.getString(KEY, "") ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                MemoryEntry(
                    id = obj.optString("id", ""),
                    project = obj.optString("project", ""),
                    title = obj.optString("title", ""),
                    body = obj.optString("body", ""),
                    tags = (obj.optJSONArray("tags")?.let { tags ->
                        (0 until tags.length()).map { tags.optString(it) }
                    }).orEmpty(),
                    updatedAt = obj.optLong("updated_at", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun search(prefs: SharedPreferences, query: String, limit: Int = 10): List<MemoryEntry> {
        val needle = query.trim().lowercase()
        val entries = all(prefs)
        if (needle.isBlank()) return entries.take(limit)
        return entries.filter {
            it.title.lowercase().contains(needle) ||
                it.body.lowercase().contains(needle) ||
                it.project.lowercase().contains(needle) ||
                it.tags.any { tag -> tag.lowercase().contains(needle) }
        }.take(limit)
    }

    fun delete(prefs: SharedPreferences, id: String): Boolean {
        val list = all(prefs)
        val filtered = list.filterNot { it.id == id }
        write(prefs, filtered)
        return filtered.size != list.size
    }

    private fun write(prefs: SharedPreferences, list: List<MemoryEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("project", entry.project)
                    .put("title", entry.title)
                    .put("body", entry.body)
                    .put("tags", JSONArray(entry.tags))
                    .put("updated_at", entry.updatedAt)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}

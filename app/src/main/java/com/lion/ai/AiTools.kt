package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class ToolGroup(val id: String, val label: String, val icon: String) {
    GITHUB("github", "GitHub", "🐙"),
    WEB("web", "الويب", "🌐"),
    FILES("files", "الملفات", "📁"),
    DATABASE("database", "قواعد البيانات", "🗄️"),
    CODE("code", "محلل الكود", "🔍"),
    UTIL("util", "أدوات مساعدة", "🧰"),
    CONNECTORS("connectors", "الموصلات", "🔌")
}

data class ToolCall(val name: String, val args: JSONObject, val raw: String)

data class ToolResult(val name: String, val output: String, val ok: Boolean)

data class ToolAuditEntry(
    val at: Long,
    val name: String,
    val ok: Boolean,
    val summary: String
)

/** سجل مختصر لآخر عمليات الأدوات لعرضه في شاشة أدوات المطوّر. */
object ToolAudit {
    private const val LIMIT = 60
    private val entries = ArrayDeque<ToolAuditEntry>()

    fun add(name: String, ok: Boolean, output: String) {
        synchronized(entries) {
            entries.addFirst(
                ToolAuditEntry(
                    System.currentTimeMillis(),
                    name,
                    ok,
                    output.replace(Regex("\\s+"), " ").trim().take(220)
                )
            )
            while (entries.size > LIMIT) entries.removeLast()
        }
    }

    fun recent(limit: Int = 20): List<ToolAuditEntry> = synchronized(entries) {
        entries.take(limit.coerceIn(1, LIMIT))
    }

    fun clear() = synchronized(entries) { entries.clear() }
}

class ToolContext(
    val context: Context,
    val prefs: SharedPreferences,
    val allowWrite: Boolean
)

/** سجل الأدوات + بروتوكول استدعاء موحّد يعمل مع جميع مزودات API. */
object AiTools {
    private data class Def(
        val name: String,
        val group: ToolGroup,
        val signature: String,
        val description: String,
        val write: Boolean = false,
        val connector: String? = null
    )

    private val defs = listOf(
        // GitHub
        Def("github_whoami", ToolGroup.GITHUB, "{}", "حساب GitHub الحالي"),
        Def("github_list_repos", ToolGroup.GITHUB, """{"per_page":30}""", "مستودعات الحساب"),
        Def("github_get_repo", ToolGroup.GITHUB, """{"repo":"owner/name"}""", "بيانات مستودع"),
        Def("github_repo_overview", ToolGroup.GITHUB, """{"repo":"owner/name"}""", "نظرة شاملة: بيانات + لغات + README + شجرة + آخر commits"),
        Def("github_create_repo", ToolGroup.GITHUB, """{"name":"repo","description":"","private":true}""", "إنشاء مستودع جديد", true),
        Def("github_list_tree", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"main"}""", "شجرة الملفات كاملة"),
        Def("github_list_dir", ToolGroup.GITHUB, """{"repo":"owner/name","path":"","branch":""}""", "محتويات مجلد"),
        Def("github_read_file", ToolGroup.GITHUB, """{"repo":"owner/name","path":"README.md","branch":""}""", "قراءة ملف"),
        Def("github_write_file", ToolGroup.GITHUB, """{"repo":"owner/name","path":"file.txt","content":"...","message":"...","branch":""}""", "إنشاء أو تعديل ملف (commit)", true),
        Def("github_delete_file", ToolGroup.GITHUB, """{"repo":"owner/name","path":"file.txt","message":"...","branch":""}""", "حذف ملف", true),
        Def("github_list_branches", ToolGroup.GITHUB, """{"repo":"owner/name"}""", "الفروع"),
        Def("github_create_branch", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"feature","from":"main"}""", "إنشاء فرع", true),
        Def("github_list_commits", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"","path":""}""", "آخر التغييرات"),
        Def("github_list_issues", ToolGroup.GITHUB, """{"repo":"owner/name","state":"open"}""", "Issues"),
        Def("github_create_issue", ToolGroup.GITHUB, """{"repo":"owner/name","title":"...","body":"...","labels":["bug"]}""", "إنشاء Issue", true),
        Def("github_comment_issue", ToolGroup.GITHUB, """{"repo":"owner/name","number":1,"body":"..."}""", "تعليق على Issue/PR", true),
        Def("github_list_prs", ToolGroup.GITHUB, """{"repo":"owner/name","state":"open"}""", "Pull Requests"),
        Def("github_create_pr", ToolGroup.GITHUB, """{"repo":"owner/name","title":"...","head":"feature","base":"main","body":""}""", "إنشاء Pull Request", true),
        Def("github_search_repos", ToolGroup.GITHUB, """{"query":"kotlin android","per_page":10}""", "بحث المستودعات"),
        Def("github_search_code", ToolGroup.GITHUB, """{"query":"repo:owner/name keyword"}""", "بحث داخل الكود"),
        Def("github_workflow_runs", ToolGroup.GITHUB, """{"repo":"owner/name","per_page":10}""", "أحدث عمليات GitHub Actions"),
        Def("github_list_workflows", ToolGroup.GITHUB, """{"repo":"owner/name"}""", "ملفات workflows ومعرّفاتها"),
        Def("github_workflow_jobs", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123}""", "وظائف وخطوات عملية بناء"),
        Def("github_job_log", ToolGroup.GITHUB, """{"repo":"owner/name","job_id":456,"max_chars":12000}""", "سجل وظيفة بناء لمعرفة سبب الفشل"),
        Def("github_rerun", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123}""", "إعادة تشغيل عملية بناء", true),
        Def("github_cancel", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123}""", "إلغاء عملية بناء جارية", true),
        Def("github_dispatch", ToolGroup.GITHUB, """{"repo":"owner/name","workflow_id":123,"ref":"main","inputs":{}}""", "بدء workflow يدوياً (بناء الآن)", true),
        Def("github_list_artifacts", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123}""", "مخرجات البناء (APK وغيرها)"),
        Def("github_download_artifact", ToolGroup.GITHUB, """{"repo":"owner/name","artifact_id":123,"name":"LionAI.zip"}""", "تنزيل مخرج بناء إلى مساحة العمل", true),
        Def("github_clone", ToolGroup.GITHUB, """{"repo":"owner/name","ref":"main","target":"repos/owner/name"}""", "تنزيل المستودع كامل وفك ضغطه في مساحة العمل", true),
        Def("github_commit_files", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"main","message":"...","files":[{"path":"a.kt","content":"..."}],"delete":["old.txt"]}""", "commit واحد لعدة ملفات (تعديل/إنشاء/حذف)", true),

        // Web
        Def("web_fetch", ToolGroup.WEB, """{"url":"https://...","links":true,"max_chars":12000}""", "جلب صفحة واستخراج النص والروابط والصور"),
        Def("web_search", ToolGroup.WEB, """{"query":"..."}""", "بحث ويب سريع بمصادر"),
        Def("web_api", ToolGroup.WEB, """{"url":"https://...","method":"GET","body":"","content_type":"application/json"}""", "استدعاء API وإرجاع JSON/نص"),
        Def("web_download", ToolGroup.WEB, """{"url":"https://...","name":"file.bin"}""", "تنزيل ملف إلى مساحة العمل"),

        // Files
        Def("file_list", ToolGroup.FILES, """{"path":""}""", "عرض ملفات مساحة العمل"),
        Def("file_search", ToolGroup.FILES, """{"query":"TODO","path":"","max_results":50}""", "بحث نصي في مساحة العمل (اسم/مسار/محتوى)"),
        Def("file_read", ToolGroup.FILES, """{"path":"src/Main.kt","max_chars":12000}""", "قراءة ملف من مساحة العمل"),
        Def("file_write", ToolGroup.FILES, """{"path":"src/Main.kt","content":"..."}""", "إنشاء/تعديل ملف", true),
        Def("file_delete", ToolGroup.FILES, """{"path":"old.txt","recursive":false}""", "حذف ملف/مجلد", true),
        Def("file_mkdir", ToolGroup.FILES, """{"path":"src"}""", "إنشاء مجلد", true),
        Def("file_zip_list", ToolGroup.FILES, """{"path":"project.zip"}""", "محتويات أرشيف ZIP"),
        Def("file_zip_read", ToolGroup.FILES, """{"path":"project.zip","entry":"README.md"}""", "قراءة مدخل من ZIP"),
        Def("file_zip_create", ToolGroup.FILES, """{"target":"project.zip","sources":["src","README.md"]}""", "إنشاء ZIP", true),
        Def("file_unzip", ToolGroup.FILES, """{"path":"project.zip","target":"project","overwrite":false}""", "فك ضغط ZIP داخل مساحة العمل", true),
        Def("file_append", ToolGroup.FILES, """{"path":"notes.txt","content":"..."}""", "إضافة نص إلى نهاية ملف", true),
        Def("file_move", ToolGroup.FILES, """{"from":"old.txt","to":"new.txt","overwrite":false}""", "نقل/إعادة تسمية ملف أو مجلد", true),
        Def("file_copy", ToolGroup.FILES, """{"from":"a.kt","to":"b.kt","overwrite":false}""", "نسخ ملف أو مجلد", true),
        Def("file_tree", ToolGroup.FILES, """{"path":"","max_files":300}""", "شجرة ملفات المشروع كاملة", ),
        Def("file_checksum", ToolGroup.FILES, """{"path":"app.apk","algorithm":"sha256"}""", "بصمة MD5/SHA1/SHA256 لملف"),
        Def("file_diff", ToolGroup.FILES, """{"first":"old.kt","second":"new.kt"}""", "مقارنة ملفين وإظهار الفروق"),

        // Database
        Def("db_list", ToolGroup.DATABASE, "{}", "قواعد SQLite الموجودة"),
        Def("db_schema", ToolGroup.DATABASE, """{"db":"app"}""", "مخطط الجداول"),
        Def("db_query", ToolGroup.DATABASE, """{"db":"app","sql":"SELECT * FROM t LIMIT 20"}""", "تنفيذ استعلام SQL"),
        Def("db_import_json", ToolGroup.DATABASE, """{"db":"app","table":"items","json":"[{\\"name\\":\\"A\\"}]","replace":false}""", "استيراد JSON إلى جدول", true),
        Def("db_export_json", ToolGroup.DATABASE, """{"db":"app","table":"items"}""", "تصدير جدول JSON"),
        Def("db_delete", ToolGroup.DATABASE, """{"db":"old"}""", "حذف قاعدة بيانات", true),

        // Code
        Def("code_analyze", ToolGroup.CODE, """{"name":"Main.kt","code":"..."}""", "تحليل كود نصي: مقاييس/تعقيد/TODO/اعتماديات"),
        Def("code_analyze_file", ToolGroup.CODE, """{"path":"src/Main.kt"}""", "تحليل ملف من مساحة العمل"),
        Def("code_project_analyze", ToolGroup.CODE, """{"path":"","max_files":80}""", "تحليل مشروع كامل في مساحة العمل"),
        Def("code_patch", ToolGroup.CODE, """{"path":"Main.kt","find":"...","replace":"...","all":false}""", "تعديل دقيق: استبدال نص داخل ملف (مع نسخة احتياطية)", true),
        Def("code_review", ToolGroup.CODE, """{"path":"","max_files":30}""", "مراجعة آلية سريعة للمشروع واكتشاف المخاطر"),

        // Utilities
        Def("util_json", ToolGroup.UTIL, """{"text":"{...}","indent":2}""", "تنسيق/التحقق من JSON"),
        Def("util_base64", ToolGroup.UTIL, """{"text":"...","mode":"encode|decode"}""", "Base64"),
        Def("util_hash", ToolGroup.UTIL, """{"text":"...","algorithm":"sha256|md5|sha1"}""", "تجزئة Hash"),
        Def("util_url", ToolGroup.UTIL, """{"text":"...","mode":"encode|decode"}""", "URL encode/decode"),
        Def("util_regex", ToolGroup.UTIL, """{"pattern":"\\\\d+","text":"...","ignore_case":false}""", "اختبار تعبير نمطي"),
        Def("util_uuid", ToolGroup.UTIL, "{}", "UUID جديد"),
        Def("util_timestamp", ToolGroup.UTIL, "{}", "الوقت الحالي Unix/ISO"),
        Def("util_jwt", ToolGroup.UTIL, """{"token":"..."}""", "فك JWT وقراءة الحقول والانتهاء (بدون تحقق)"),
        Def("util_cron", ToolGroup.UTIL, """{"cron":"0 9 * * *"}""", "شرح تعبير Cron وتوقع التنفيذ القادم"),
        Def("util_diff", ToolGroup.UTIL, """{"first":"...","second":"..."}""", "مقارنة نصين وإظهار الفروق"),
        Def("util_csv", ToolGroup.UTIL, """{"text":"a,b\n1,2","query":"SELECT * LIMIT 10"}""", "تحليل CSV واستعلام بسيط"),
        Def("util_slug", ToolGroup.UTIL, """{"text":"..."}""", "إنشاء slug آمن من نص"),
        Def("util_color", ToolGroup.UTIL, """{"color":"#FFB800"}""", "تحويل لون HEX إلى RGB/HSL"),
        Def("util_regex_replace", ToolGroup.UTIL, """{"pattern":"\\d+","text":"a1b2","replacement":"#","ignore_case":false}""", "استبدال بتعبير نمطي"),

        // Connectors
        Def("connectors_status", ToolGroup.CONNECTORS, "{}", "حالة الموصلات والحسابات المربوطة"),
        Def("connectors_test", ToolGroup.CONNECTORS, "{}", "اختبار فعلي لصلاحية كل الموصلات المربوطة"),
        Def("drive_search", ToolGroup.CONNECTORS, """{"query":"تقرير","max":10}""", "بحث في ملفات Google Drive", connector = "google_drive"),
        Def("drive_open", ToolGroup.CONNECTORS, """{"url_or_id":"https://drive.google.com/drive/folders/...","max_chars":12000}""", "فتح رابط/معرّف ملف أو مجلد Drive وعرض محتواه", connector = "google_drive"),
        Def("drive_list", ToolGroup.CONNECTORS, """{"max":50,"folder_id":""}""", "أحدث ملفات Drive أو محتويات مجلد", connector = "google_drive"),
        Def("drive_read", ToolGroup.CONNECTORS, """{"file_id":"...","max_chars":12000}""", "قراءة محتوى ملف نصي من Drive", connector = "google_drive"),
        Def("drive_download", ToolGroup.CONNECTORS, """{"file_id":"...","name":"file.pdf"}""", "تنزيل ملف من Drive إلى مساحة العمل", true, "google_drive"),
        Def("drive_upload", ToolGroup.CONNECTORS, """{"name":"report.txt","path":"","content":"...","mime":"text/plain","folder_id":""}""", "رفع ملف/نص إلى Drive", true, "google_drive"),
        Def("drive_update", ToolGroup.CONNECTORS, """{"file_id":"...","content":"...","path":"","mime":"text/plain"}""", "تعديل محتوى ملف نصي موجود", true, "google_drive"),
        Def("drive_mkdir", ToolGroup.CONNECTORS, """{"name":"مجلد","folder_id":""}""", "إنشاء مجلد في Drive", true, "google_drive"),
        Def("drive_move", ToolGroup.CONNECTORS, """{"file_id":"...","folder_id":"...","name":""}""", "نقل ملف إلى مجلد آخر", true, "google_drive"),
        Def("drive_rename", ToolGroup.CONNECTORS, """{"file_id":"...","name":"الاسم الجديد"}""", "إعادة تسمية ملف", true, "google_drive"),
        Def("drive_copy", ToolGroup.CONNECTORS, """{"file_id":"...","name":"نسخة","folder_id":""}""", "نسخ ملف", true, "google_drive"),
        Def("drive_trash", ToolGroup.CONNECTORS, """{"file_id":"...","permanent":false}""", "نقل ملف إلى السلة أو حذفه نهائياً", true, "google_drive"),
        Def("drive_meta", ToolGroup.CONNECTORS, """{"file_id":"..."}""", "بيانات ملف Drive والمالك والصلاحيات", connector = "google_drive"),
        Def("gmail_search", ToolGroup.CONNECTORS, """{"query":"from:someone","max":10}""", "بحث في Gmail", connector = "gmail"),
        Def("gmail_read", ToolGroup.CONNECTORS, """{"message_id":"...","max_chars":12000}""", "قراءة رسالة بريد كاملة مع قائمة المرفقات", connector = "gmail"),
        Def("gmail_send", ToolGroup.CONNECTORS, """{"to":"a@b.com","subject":"...","body":"...","cc":"","bcc":"","html":false,"from":"","attachments":["downloads/a.pdf"],"thread_id":"","in_reply_to":""}""", "إرسال بريد إلكتروني (يدعم المرفقات والرد على محادثة)", true, "gmail"),
        Def("gmail_draft", ToolGroup.CONNECTORS, """{"to":"a@b.com","subject":"...","body":"...","cc":"","bcc":"","html":false,"from":"","attachments":[]}""", "حفظ رسالة كمسودة بدل إرسالها", true, "gmail"),
        Def("gmail_labels", ToolGroup.CONNECTORS, "{}", "تسميات Gmail ومعرّفاتها", connector = "gmail"),
        Def("gmail_modify", ToolGroup.CONNECTORS, """{"message_id":"...","add":["UNREAD"],"remove":["INBOX"]}""", "تعديل تسميات رسالة (قراءة/أرشفة/نجمة)", true, "gmail"),
        Def("gmail_trash", ToolGroup.CONNECTORS, """{"message_id":"...","permanent":false}""", "نقل رسالة إلى المهملات أو حذفها نهائياً", true, "gmail"),
        Def("gmail_attachment", ToolGroup.CONNECTORS, """{"message_id":"...","attachment_id":"...","name":"file.pdf"}""", "تنزيل مرفق Gmail إلى مساحة العمل", true, "gmail"),
        Def("gmail_profile", ToolGroup.CONNECTORS, "{}", "بيانات حساب Gmail وإحصاءات الرسائل", connector = "gmail"),
        Def("calendar_list", ToolGroup.CONNECTORS, """{"days":7,"max":20,"calendar_id":"primary"}""", "المواعيد القادمة", connector = "google_calendar"),
        Def("calendar_calendars", ToolGroup.CONNECTORS, "{}", "قائمة التقاويم ومعرّفاتها", connector = "google_calendar"),
        Def("calendar_create", ToolGroup.CONNECTORS, """{"summary":"اجتماع","start":"2026-10-27T10:00:00+03:00","end":"","description":"","location":"","timezone":"Asia/Riyadh","attendees":["a@b.com"],"calendar_id":"primary"}""", "إنشاء موعد", true, "google_calendar"),
        Def("calendar_update", ToolGroup.CONNECTORS, """{"event_id":"...","summary":"","start":"","end":"","description":"","location":"","timezone":"","calendar_id":"primary"}""", "تعديل موعد موجود", true, "google_calendar"),
        Def("calendar_delete", ToolGroup.CONNECTORS, """{"event_id":"...","calendar_id":"primary"}""", "حذف موعد", true, "google_calendar"),
        Def("calendar_quick_add", ToolGroup.CONNECTORS, """{"text":"اجتماع غداً الساعة 10"}""", "إنشاء موعد بلغة طبيعية", true, "google_calendar"),
        Def("notion_search", ToolGroup.CONNECTORS, """{"query":"...","max":10}""", "بحث في Notion", connector = "notion"),
        Def("notion_read", ToolGroup.CONNECTORS, """{"page_id":"...","max_chars":12000}""", "قراءة صفحة Notion", connector = "notion"),
        Def("notion_create", ToolGroup.CONNECTORS, """{"parent_page":"...","title":"...","content":"..."}""", "إنشاء صفحة Notion", true, "notion"),
        Def("notion_append", ToolGroup.CONNECTORS, """{"page_id":"...","content":"..."}""", "إضافة محتوى إلى صفحة Notion", true, "notion"),
        Def("slack_channels", ToolGroup.CONNECTORS, """{"max":50}""", "قنوات Slack", connector = "slack"),
        Def("slack_post", ToolGroup.CONNECTORS, """{"channel":"general","text":"..."}""", "إرسال رسالة Slack", true, "slack"),
        Def("slack_history", ToolGroup.CONNECTORS, """{"channel":"general","max":20}""", "آخر رسائل قناة Slack", connector = "slack"),
        Def("connector_get", ToolGroup.CONNECTORS, """{"path":"/items"}""", "GET من خدمة REST المخصّصة", connector = "generic"),
        Def("connector_request", ToolGroup.CONNECTORS, """{"path":"/items","method":"POST","body":"{}"}""", "طلب GET/POST/PUT/PATCH إلى خدمة REST المخصّصة", true, "generic")
    )

    private val byName = defs.associateBy { it.name }

    private val MARKER = Regex("\\[\\[TOOL\\]\\](.*?)\\[\\[/TOOL\\]\\]", RegexOption.DOT_MATCHES_ALL)
    private val TOOL_TAG = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val FENCE = Regex("```(?:tool|json|ai-tool)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)

    fun token(prefs: SharedPreferences): String =
        (prefs.getString("github_token", "") ?: "").trim()

    fun githubReady(prefs: SharedPreferences): Boolean = token(prefs).isNotBlank()

    fun agentEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean("agent_tools", true)

    fun allowWrite(prefs: SharedPreferences): Boolean = prefs.getBoolean("agent_write", true)

    fun groupsFor(text: String, prefs: SharedPreferences): Set<ToolGroup> {
        if (!agentEnabled(prefs)) return emptySet()
        val t = TextNorm.forMatch(text)
        val groups = mutableSetOf<ToolGroup>()
        val github = Regex(
            "(github|git\\b|repo|repository|pull request|\\bpr\\b|issue|commit|branch|workflow|actions|readme|" +
                "مستودع|مستودعات|ريبو|فرع|فروع|كوميت|بلاغ|بلاغات|طلب سحب|سحب|دفع|رفع|شجرة الملفات|ملفات المشروع|جيت هاب|غيث هاب|" +
                "workflow|workflows|بناء التطبيق|سجل البناء|إعادة البناء|اعادة البناء|rerun|dispatch|artifacts|مخرجات البناء|apk)"
        )
        if (github.containsMatchIn(t) && githubReady(prefs)) groups.add(ToolGroup.GITHUB)
        val web = Regex(
            "(https?://|www\\.|\\burl\\b|\\bapi\\b|fetch|web|website|" +
                "رابط|روابط|موقع|صفحة|صفحه|ويب|انترنت|إنترنت|جلب|اجلب|أجلب|حمّل|حمل|نزّل|تنزيل|استخرج|مقتطف|بحث ويب)"
        )
        if (web.containsMatchIn(t)) groups.add(ToolGroup.WEB)
        val files = Regex(
            "(\\.zip\\b|\\.csv\\b|\\.json\\b|\\.ya?ml\\b|\\.xml\\b|\\.txt\\b|\\.kt\\b|\\.java\\b|\\.py\\b|\\.js\\b|" +
                "ملف|ملفات|مجلد|مشروع|مساحة العمل|workspace|zip|أرشيف|ارشيف|csv|yaml|xml|احفظ|أحفظ|اكتب ملف|أنشئ ملف|انشئ ملف|اقرأ ملف|قراءة ملف|" +
                "ابحث في الملفات|ابحث في المشروع|بحث في الملفات|بحث في الكود|grep|فك الضغط|فك ضغط|استخرج الملفات|استخراج الملفات|" +
                "شجرة|tree|انقل ملف|انسخ ملف|بصمة|checksum|diff|فرق بين ملفين)"
        )
        if (files.containsMatchIn(t)) groups.add(ToolGroup.FILES)
        val db = Regex("(sql|sqlite|database|قاعدة بيانات|قواعد بيانات|جدول بيانات|استعلام|جدول|قاعدة|db\\b)")
        if (db.containsMatchIn(t)) groups.add(ToolGroup.DATABASE)
        val code = Regex(
            "(\\.kt\\b|\\.java\\b|\\.py\\b|\\.js\\b|\\.ts\\b|\\.cpp\\b|\\.cs\\b|\\.go\\b|\\.rs\\b|code|" +
                "كود|برمج|برمجة|دالة|دوال|كلاس|class|تعقيد|complexity|todo|fixme|اعتماديات|dependencies|" +
                "تحليل الكود|حلل الكود|حلّل الكود|راجع الكود|مراجعة الكود|اصلح|أصلح|صحح|خطأ برمجي|bug|exception|" +
                "حلل المشروع|تحليل المشروع|راجع المشروع|مراجعة المشروع|افحص المشروع|فحص المشروع|" +
                "android|kotlin|python|javascript|typescript|flutter|gradle|تطبيق|تطبيقات|مشروع برمجي|تعديل التطبيق|تطوير)"
        )
        if (code.containsMatchIn(t)) groups.add(ToolGroup.CODE)
        val util = Regex(
            "(base64|hash|sha256|sha1|md5|uuid|timestamp|regex|تعبير نمطي|json|url encode|url decode|تنسيق نص|" +
                "jwt|cron|diff|قارن|مقارنة|csv|slug|لون|color|استبدال|regex replace)"
        )
        if (util.containsMatchIn(t)) groups.add(ToolGroup.UTIL)
        val connectors = Regex(
            "(google drive|drive|gmail|email|e-mail|mail|calendar|notion|slack|connector|webhook|" +
                "درايف|جوجل درايف|بريد|بريد إلكتروني|بريد الكتروني|ايميل|إيميل|جيميل|بريدي|رسائلي|" +
                "تقويم|مواعيد|calendar|اجتماع|نوشن|نوتيون|سلاك|موصل|موصلات|خدمة خارجية|تكامل|ربط حساب|" +
                "docs\\.google\\.com|drive\\.google\\.com|mail\\.google\\.com|calendar\\.google\\.com)"
        )
        if (connectors.containsMatchIn(t) || Connectors.extractDriveId(text) != null) {
            groups.add(ToolGroup.CONNECTORS)
        }
        return groups
    }

    data class GitHubRef(val owner: String, val repo: String, val branch: String, val path: String)

    private val GITHUB_URL = Regex(
        "github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/(?:tree|blob)/([^/\\s?#]+)(?:/([^\\s?#]+))?)?"
    )

    fun detectGitHubUrl(text: String): GitHubRef? {
        val match = GITHUB_URL.find(text) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        val branch = match.groupValues[3]
        val path = match.groupValues[4].trimEnd('.', ',', ')', ']')
        return GitHubRef(owner, repo, branch, path)
    }

    fun contextHint(text: String): String {
        val ref = detectGitHubUrl(text) ?: return ""
        return buildString {
            append("\n\nسياق GitHub من رسالة المستخدم: owner=${ref.owner} repo=${ref.repo}")
            if (ref.branch.isNotBlank()) append(" branch=${ref.branch}")
            if (ref.path.isNotBlank()) append(" path=${ref.path}")
            append("\nاستخدم هذا المستودع/الملف مباشرة عند الحاجة دون سؤال المستخدم عن المالك أو الاسم.")
        }
    }

    fun spec(groups: Set<ToolGroup>, prefs: SharedPreferences): String {
        if (groups.isEmpty()) return ""
        val available = defs.filter { def ->
            def.group != ToolGroup.CONNECTORS || def.connector == null ||
                ConnectorAuth.isConnected(prefs, def.connector)
        }
        if (available.isEmpty()) return ""
        val builder = StringBuilder()
        builder.append("\n\n=== أدوات متاحة ===\n")
        builder.append("عند الحاجة الفعلية، استدعِ أداة واحدة أو أكثر بهذه الصيغة فقط داخل ردك:\n")
        builder.append("[[TOOL]]\n{\"tool\":\"اسم_الأداة\",\"args\":{...}}\n[[/TOOL]]\n")
        builder.append("ستتلقى نتيجة كل أداة ثم تتابع حتى تُنجز المهمة. لا تخترع نتائج. ")
        builder.append("لا تضع أسراراً في الملفات أو الردود. المسارات محصورة في مساحة عمل التطبيق. ")
        builder.append("لا تكتب أو تحذف أو تدفع إلى GitHub، ولا ترسل بريداً أو رسالة Slack، ولا ترفع إلى Drive، ولا تنشئ مواعيد أو صفحات، إلا إذا طلب المستخدم ذلك صراحة. ")
        builder.append("إذا أرسل المستخدم رابط Google Drive أو معرّف ملف/مجلد، استخدم drive_open مباشرة بدلاً من drive_search. ")
        builder.append("بعد انتهاء الأدوات أجب بالعربية بملخص واضح وخطوات ما فعلته.\n")
        if (ToolGroup.CONNECTORS in groups) {
            val connected = ConnectorAuth.all.filter { ConnectorAuth.isConnected(prefs, it.id) }
            if (connected.isEmpty()) {
                builder.append("ملاحظة: لا يوجد أي موصل مربوط حالياً. أخبر المستخدم بلطف أن يفتح «الموصلات» ويربط Google Drive أو Gmail أو التقويم، ثم تابع إن أمكن ببقية الأدوات. لا تدّعِ الوصول إلى بياناته.\n")
            } else {
                builder.append("الموصلات المربوطة: ").append(connected.joinToString("، ") { it.name })
                    .append(". استخدمها عند الحاجة إلى بيانات حقيقية بدل التخمين.\n")
            }
        }
        ToolGroup.values().filter { it in groups }.forEach { group ->
            builder.append("\n[").append(group.icon).append(' ').append(group.label).append("]\n")
            available.filter { it.group == group }.forEach { def ->
                builder.append("- ").append(def.name).append(' ').append(def.signature)
                if (def.write) builder.append(" [كتابة]")
                builder.append(" — ").append(def.description).append('\n')
            }
        }
        return builder.toString()
    }

    fun parseCalls(text: String): List<ToolCall> {
        val found = mutableListOf<ToolCall>()
        val blocks = mutableListOf<String>()
        MARKER.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        TOOL_TAG.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        FENCE.findAll(text).forEach { blocks.add(it.groupValues[1]) }
        if (blocks.isEmpty()) {
            // بعض النماذج ترسل JSON عارياً بلا سياج.
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) blocks.add(text.substring(start, end + 1))
        }
        blocks.forEach { raw ->
            val trimmed = raw.trim()
            val candidates = mutableListOf<JSONObject>()
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { candidates.add(it) }
            runCatching { org.json.JSONArray(trimmed) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { candidates.add(it) }
            }
            if (candidates.isEmpty()) return@forEach
            candidates.forEach { json ->
                val name = json.optString("tool").ifBlank { json.optString("name") }
                    .ifBlank { json.optJSONObject("function")?.optString("name").orEmpty() }
                val rawArgs = json.opt("args") ?: json.opt("arguments")
                    ?: json.optJSONObject("function")?.opt("arguments")
                val args = when (rawArgs) {
                    is JSONObject -> rawArgs
                    is String -> runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                    else -> JSONObject()
                }
                if (byName.containsKey(name) && found.none { it.name == name && it.args.toString() == args.toString() }) {
                    found.add(ToolCall(name, args, trimmed))
                }
            }
        }
        return found
    }

    suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult {
        val def = byName[call.name] ?: return ToolResult(call.name, "❌ أداة غير معروفة: ${call.name}", false)
        if (def.write && !ctx.allowWrite) {
            return ToolResult(call.name, "⛔ الكتابة معطّلة. فعّل «السماح للذكاء بالكتابة» من الإعدادات أولاً.", false)
        }
        val result = try {
            val output = run(ctx, call.name, call.args)
            ToolResult(call.name, output.take(16_000), true)
        } catch (e: GitHubException) {
            ToolResult(call.name, "❌ GitHub ${e.status}: ${e.message}", false)
        } catch (e: Exception) {
            ToolResult(call.name, "❌ ${e.message ?: e.javaClass.simpleName}", false)
        }
        ToolAudit.add(call.name, result.ok, result.output)
        return result
    }

    private fun repoOf(ctx: ToolContext, args: JSONObject): Pair<String, String> {
        val full = args.optString("repo").trim()
        if (full.contains("/")) return GitHubClient.parseFullName(full)
        val fallback = (ctx.prefs.getString("github_default_repo", "") ?: "").trim()
        val owner = args.optString("owner").trim().ifBlank { fallback.substringBefore('/', "") }
        val repo = full.ifBlank { fallback.substringAfter('/', "") }
        if (owner.isBlank() || repo.isBlank()) {
            throw IllegalArgumentException("حدد المستودع بصيغة owner/name")
        }
        return owner to repo
    }

    private fun req(args: JSONObject, key: String): String =
        args.optString(key).trim().ifBlank { throw IllegalArgumentException("المعامل «$key» مطلوب") }

    private fun int(args: JSONObject, key: String, default: Int): Int =
        if (args.has(key) && !args.isNull(key)) args.optInt(key, default) else default

    private suspend fun run(ctx: ToolContext, name: String, args: JSONObject): String {
        val token = token(ctx.prefs)
        return when (name) {
            "github_whoami" -> {
                val user = GitHubClient.whoAmI(token)
                "👤 ${user.optString("login")} (${user.optString("name")}) — ${user.optInt("public_repos")} مستودع عام"
            }
            "github_list_repos" -> {
                val repos = GitHubClient.listRepos(token, int(args, "per_page", 30))
                if (repos.isEmpty()) "لا توجد مستودعات." else repos.joinToString("\n") {
                    "• ${it.fullName}${if (it.private) " 🔒" else ""} — ${it.language.ifBlank { "—" }} ⭐${it.stars} — ${it.description.take(80)}"
                }
            }
            "github_get_repo" -> {
                val (owner, repo) = repoOf(ctx, args)
                val r = GitHubClient.getRepo(token, owner, repo)
                "📦 ${r.fullName}\n${r.description}\nالفرع الافتراضي: ${r.defaultBranch}\nاللغة: ${r.language} | ⭐${r.stars}\n${r.htmlUrl}"
            }
            "github_repo_overview" -> {
                val (owner, repo) = repoOf(ctx, args)
                val r = GitHubClient.getRepo(token, owner, repo)
                val languages = GitHubClient.languages(token, owner, repo)
                val readme = runCatching { GitHubClient.readFile(token, owner, repo, "README.md").text }.getOrDefault("")
                val tree = runCatching { GitHubClient.listTree(token, owner, repo, maxEntries = 80) }.getOrDefault(emptyList())
                val commits = runCatching { GitHubClient.listCommits(token, owner, repo) }.getOrDefault(emptyList())
                buildString {
                    append("📦 ").append(r.fullName).append('\n')
                    append(r.description).append('\n')
                    append("الفرع: ").append(r.defaultBranch).append(" | اللغة: ").append(r.language).append('\n')
                    append("اللغات: ").append(languages.entries.take(6).joinToString("، ") { "${it.key} ${it.value}" }).append('\n')
                    if (readme.isNotBlank()) append("\n📖 README:\n").append(readme.take(4000)).append('\n')
                    if (tree.isNotEmpty()) {
                        append("\n🗂️ أول 60 ملفاً:\n")
                        tree.take(60).forEach { append(if (it.type == "dir") "📁 " else "📄 ").append(it.path).append('\n') }
                    }
                    if (commits.isNotEmpty()) {
                        append("\n🕘 آخر commits:\n")
                        commits.take(8).forEach { append("- ").append(it).append('\n') }
                    }
                }
            }
            "github_create_repo" -> {
                val r = GitHubClient.createRepo(
                    token,
                    req(args, "name"),
                    args.optString("description"),
                    args.optBoolean("private", true),
                    args.optBoolean("auto_init", true)
                )
                "✅ أُنشئ ${r.fullName}\n${r.htmlUrl}"
            }
            "github_list_tree" -> {
                val (owner, repo) = repoOf(ctx, args)
                val entries = GitHubClient.listTree(token, owner, repo, args.optString("branch"), int(args, "max_entries", 300))
                entries.joinToString("\n") { (if (it.type == "dir") "📁 " else "📄 ") + it.path + if (it.size > 0) " (${it.size})" else "" }
            }
            "github_list_dir" -> {
                val (owner, repo) = repoOf(ctx, args)
                val entries = GitHubClient.listDir(token, owner, repo, args.optString("path"), args.optString("branch"))
                if (entries.isEmpty()) "المجلد فارغ." else entries.joinToString("\n") {
                    (if (it.type == "dir") "📁 " else "📄 ") + it.name + if (it.type == "file") " (${it.size} بايت)" else ""
                }
            }
            "github_read_file" -> {
                val (owner, repo) = repoOf(ctx, args)
                val path = req(args, "path")
                val file = GitHubClient.readFile(token, owner, repo, path, args.optString("branch"))
                if (file.binary) "📄 $path — ملف ثنائي (${file.sha})" else "📄 ${file.path} (${file.sha})\n\n${file.text.take(14_000)}"
            }
            "github_write_file" -> {
                val (owner, repo) = repoOf(ctx, args)
                val path = req(args, "path")
                val content = args.optString("content")
                val result = GitHubClient.writeFile(
                    token, owner, repo, path, content,
                    args.optString("message", "تحديث من Lion AI"),
                    args.optString("branch")
                )
                val commit = result.optJSONObject("commit")?.optString("html_url").orEmpty()
                "✅ حُفظ $path\n${commit}"
            }
            "github_delete_file" -> {
                val (owner, repo) = repoOf(ctx, args)
                val path = req(args, "path")
                GitHubClient.deleteFile(
                    token, owner, repo, path,
                    args.optString("message", "حذف من Lion AI"),
                    args.optString("branch")
                )
                "✅ حُذف $path"
            }
            "github_list_branches" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.listBranches(token, owner, repo).joinToString("\n") { "• $it" }
            }
            "github_create_branch" -> {
                val (owner, repo) = repoOf(ctx, args)
                val branch = GitHubClient.createBranch(token, owner, repo, req(args, "branch"), args.optString("from"))
                "✅ أُنشئ الفرع $branch"
            }
            "github_list_commits" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.listCommits(token, owner, repo, args.optString("branch"), args.optString("path")).joinToString("\n") { "• $it" }
            }
            "github_list_issues" -> {
                val (owner, repo) = repoOf(ctx, args)
                val issues = GitHubClient.listIssues(token, owner, repo, args.optString("state", "open"))
                if (issues.isEmpty()) "لا توجد Issues." else issues.joinToString("\n") {
                    "#${it.number} [${it.state}] ${it.title} — @${it.user}${if (it.isPull) " (PR)" else ""}"
                }
            }
            "github_create_issue" -> {
                val (owner, repo) = repoOf(ctx, args)
                val labels = args.optJSONArray("labels")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
                }.orEmpty()
                val issue = GitHubClient.createIssue(
                    token, owner, repo, req(args, "title"),
                    args.optString("body"), labels
                )
                "✅ أُنشئ Issue #${issue.number}: ${issue.title}\n${issue.htmlUrl}"
            }
            "github_comment_issue" -> {
                val (owner, repo) = repoOf(ctx, args)
                val url = GitHubClient.commentIssue(
                    token, owner, repo, int(args, "number", 0), req(args, "body")
                )
                "✅ أُضيف التعليق\n$url"
            }
            "github_list_prs" -> {
                val (owner, repo) = repoOf(ctx, args)
                val pulls = GitHubClient.listPulls(token, owner, repo, args.optString("state", "open"))
                if (pulls.isEmpty()) "لا توجد Pull Requests." else pulls.joinToString("\n") {
                    "#${it.number} [${it.state}] ${it.title} — ${it.head} → ${it.base} (@${it.user})"
                }
            }
            "github_create_pr" -> {
                val (owner, repo) = repoOf(ctx, args)
                val pr = GitHubClient.createPull(
                    token, owner, repo, req(args, "title"), req(args, "head"),
                    args.optString("base"), args.optString("body"), args.optBoolean("draft", false)
                )
                "✅ أُنشئ PR #${pr.number}: ${pr.title}\n${pr.htmlUrl}"
            }
            "github_search_repos" -> {
                val repos = GitHubClient.searchRepos(token, task(args, "query"), int(args, "per_page", 10))
                if (repos.isEmpty()) "لا نتائج." else repos.joinToString("\n") {
                    "• ${it.fullName} ⭐${it.stars} — ${it.description.take(90)}"
                }
            }
            "github_search_code" -> {
                val results = GitHubClient.searchCode(token, task(args, "query"), int(args, "per_page", 10))
                if (results.isEmpty()) "لا نتائج." else results.joinToString("\n") { "• $it" }
            }
            "github_workflow_runs" -> {
                val (owner, repo) = repoOf(ctx, args)
                val runs = GitHubClient.listWorkflowRuns(token, owner, repo, int(args, "per_page", 10))
                if (runs.isEmpty()) "لا توجد عمليات بناء." else runs.joinToString("\n") {
                    "• #${it.number} ${it.name} [${it.branch}] — ${it.status}/${it.conclusion} (id=${it.id})\n  ${it.htmlUrl}"
                }
            }
            "github_list_workflows" -> {
                val (owner, repo) = repoOf(ctx, args)
                val items = GitHubClient.listWorkflows(token, owner, repo)
                if (items.isEmpty()) "لا توجد workflows." else items.joinToString("\n") { "• ${it.second} — id=${it.first}" }
            }
            "github_workflow_jobs" -> {
                val (owner, repo) = repoOf(ctx, args)
                val jobs = GitHubClient.listRunJobs(token, owner, repo, args.optLong("run_id", 0L))
                if (jobs.isEmpty()) "لا توجد وظائف." else jobs.joinToString("\n\n") { job ->
                    buildString {
                        append("• ").append(job.name).append(" — ").append(job.status).append('/').append(job.conclusion)
                        append(" (id=").append(job.id).append(")\n")
                        job.steps.forEach { (step, result) -> append("   - ").append(step).append(": ").append(result).append('\n') }
                    }.trimEnd()
                }
            }
            "github_job_log" -> {
                val (owner, repo) = repoOf(ctx, args)
                val log = GitHubClient.jobLog(token, owner, repo, args.optLong("job_id", 0L))
                log.take(int(args, "max_chars", 12_000).coerceIn(2000, 30_000))
            }
            "github_rerun" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.rerunRun(token, owner, repo, args.optLong("run_id", 0L))
                "✅ أُعيد تشغيل البناء"
            }
            "github_cancel" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.cancelRun(token, owner, repo, args.optLong("run_id", 0L))
                "✅ أُلغي البناء"
            }
            "github_dispatch" -> {
                val (owner, repo) = repoOf(ctx, args)
                val inputs = args.optJSONObject("inputs")?.let { obj ->
                    obj.keys().asSequence().associateWith { obj.optString(it) }
                }.orEmpty()
                GitHubClient.dispatchWorkflow(
                    token, owner, repo, args.optLong("workflow_id", 0L),
                    args.optString("ref"), inputs
                )
                "✅ بدأ تنفيذ workflow"
            }
            "github_list_artifacts" -> {
                val (owner, repo) = repoOf(ctx, args)
                val artifacts = GitHubClient.listArtifacts(token, owner, repo, args.optLong("run_id", 0L))
                if (artifacts.isEmpty()) "لا توجد مخرجات." else artifacts.joinToString("\n") {
                    "• ${it.name} (${FileTools.formatSize(it.size)}) id=${it.id}${if (it.expired) " — منتهي" else ""}"
                }
            }
            "github_download_artifact" -> {
                val (owner, repo) = repoOf(ctx, args)
                val id = args.optLong("artifact_id", 0L)
                val rawName = args.optString("name").ifBlank { "artifact-$id.zip" }
                val safeName = rawName.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80)
                val target = File(File(FileTools.root(ctx.context), "downloads"), safeName)
                val file = GitHubClient.downloadArtifact(token, owner, repo, id, target)
                "✅ نُزّل ${FileTools.relative(ctx.context, file)} (${FileTools.formatSize(file.length())})"
            }
            "github_clone" -> {
                val (owner, repo) = repoOf(ctx, args)
                val zip = File(File(FileTools.root(ctx.context), "downloads"), "$repo.zip")
                val file = GitHubClient.downloadRepoZip(token, owner, repo, args.optString("ref"), zip)
                val target = args.optString("target").ifBlank { "repos/$owner/$repo" }
                val count = FileTools.extractZip(
                    ctx.context,
                    FileTools.relative(ctx.context, file),
                    target,
                    args.optBoolean("overwrite", true),
                    stripRoot = true
                )
                "✅ نُسخ $owner/$repo إلى $target ($count ملفاً)"
            }
            "github_commit_files" -> {
                val (owner, repo) = repoOf(ctx, args)
                val files = linkedMapOf<String, String>()
                args.optJSONArray("files")?.let { array ->
                    for (index in 0 until array.length()) {
                        val o = array.optJSONObject(index) ?: continue
                        val path = o.optString("path").trim()
                        if (path.isNotBlank()) files[path] = o.optString("content")
                    }
                }
                args.optJSONObject("files")?.let { obj ->
                    obj.keys().forEach { key -> files[key] = obj.optString(key) }
                }
                val deletions = (args.optJSONArray("delete") ?: args.optJSONArray("deletions"))?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
                }.orEmpty()
                if (files.isEmpty() && deletions.isEmpty()) throw IllegalArgumentException("حدد files أو delete")
                val sha = GitHubClient.commitFiles(
                    token, owner, repo, args.optString("branch"),
                    args.optString("message", "تحديث من Lion AI"),
                    files, deletions
                )
                "✅ أُنشئ commit ${sha.take(8)} (${files.size} ملف، ${deletions.size} حذف)"
            }

            "connectors_status" -> {
                val list = ConnectorAuth.all.joinToString("\n") { def ->
                    val state = if (ConnectorAuth.isConnected(ctx.prefs, def.id)) "✅ مربوط" else "⚪ غير مربوط"
                    val account = ConnectorAuth.account(ctx.prefs, def.id)
                    "• ${def.icon} ${def.name} — $state${if (account.isNotBlank()) " ($account)" else ""}"
                }
                "الموصلات:\n$list"
            }
            "connectors_test" -> {
                val connected = ConnectorAuth.all.filter { ConnectorAuth.isConnected(ctx.prefs, it.id) }
                if (connected.isEmpty()) "لا توجد موصلات مربوطة بعد. افتح شاشة الموصلات واربط حساباً."
                else {
                    val results = ArrayList<String>(connected.size)
                    for (def in connected) {
                        results.add(
                            try {
                                ConnectorAuth.test(ctx.prefs, def.id)
                            } catch (e: Throwable) {
                                "❌ ${def.name}: ${e.message ?: e.javaClass.simpleName}"
                            }
                        )
                    }
                    results.joinToString("\n")
                }
            }
            "drive_search" -> {
                val query = args.optString("query").ifBlank { args.optString("q") }.trim()
                val id = Connectors.extractDriveId(query)
                when {
                    id != null -> Connectors.driveOpen(ctx, id, int(args, "max_chars", 12_000))
                    query.isBlank() -> Connectors.driveList(ctx, int(args, "max", 20))
                    else -> Connectors.driveSearch(ctx, query, int(args, "max", 10))
                }
            }
            "drive_open" -> Connectors.driveOpen(
                ctx, req(args, "url_or_id"), int(args, "max_chars", 12_000)
            )
            "drive_list" -> Connectors.driveList(
                ctx, int(args, "max", 50), args.optString("folder_id")
            )
            "drive_read" -> Connectors.driveRead(ctx, req(args, "file_id"), int(args, "max_chars", 12_000))
            "drive_download" -> Connectors.driveDownload(
                ctx, req(args, "file_id"), args.optString("name"), args.optString("export_mime")
            )
            "drive_upload" -> Connectors.driveUpload(
                ctx, req(args, "name"), args.optString("path"), args.optString("content"),
                args.optString("mime"), args.optString("folder_id")
            )
            "drive_mkdir" -> Connectors.driveMkdir(ctx, req(args, "name"), args.optString("folder_id"))
            "drive_move" -> Connectors.driveMove(
                ctx, req(args, "file_id"), req(args, "folder_id"), args.optString("name")
            )
            "drive_rename" -> Connectors.driveRename(ctx, req(args, "file_id"), req(args, "name"))
            "drive_copy" -> Connectors.driveCopy(
                ctx, req(args, "file_id"), args.optString("name"), args.optString("folder_id")
            )
            "drive_trash" -> Connectors.driveTrash(
                ctx, req(args, "file_id"), args.optBoolean("permanent", false)
            )
            "drive_meta" -> Connectors.driveMeta(ctx, req(args, "file_id"))
            "drive_update" -> Connectors.driveUpdate(
                ctx, req(args, "file_id"), args.optString("content"),
                args.optString("path"), args.optString("mime")
            )
            "gmail_search" -> Connectors.gmailSearch(ctx, req(args, "query"), int(args, "max", 10))
            "gmail_read" -> Connectors.gmailRead(ctx, req(args, "message_id"), int(args, "max_chars", 12_000))
            "gmail_send" -> Connectors.gmailSend(
                ctx, req(args, "to"), args.optString("subject", "(بدون موضوع)"),
                req(args, "body"), args.optString("cc"), args.optString("bcc"),
                args.optBoolean("html", false), args.optString("from"),
                stringList(args.optJSONArray("attachments")), args.optString("thread_id"),
                args.optString("in_reply_to")
            )
            "gmail_draft" -> Connectors.gmailDraft(
                ctx, req(args, "to"), args.optString("subject", "(بدون موضوع)"),
                req(args, "body"), args.optString("cc"), args.optString("bcc"),
                args.optBoolean("html", false), args.optString("from"),
                stringList(args.optJSONArray("attachments"))
            )
            "gmail_labels" -> Connectors.gmailLabels(ctx)
            "gmail_modify" -> Connectors.gmailModify(
                ctx, req(args, "message_id"),
                stringList(args.optJSONArray("add")), stringList(args.optJSONArray("remove"))
            )
            "gmail_trash" -> Connectors.gmailTrash(
                ctx, req(args, "message_id"), args.optBoolean("permanent", false)
            )
            "gmail_attachment" -> Connectors.gmailAttachment(
                ctx, req(args, "message_id"), req(args, "attachment_id"), args.optString("name")
            )
            "gmail_profile" -> Connectors.gmailProfile(ctx)
            "calendar_list" -> Connectors.calendarList(
                ctx, int(args, "days", 7), int(args, "max", 20), args.optString("calendar_id", "primary")
            )
            "calendar_calendars" -> Connectors.calendarCalendars(ctx)
            "calendar_create" -> Connectors.calendarCreate(
                ctx, req(args, "summary"), req(args, "start"), args.optString("end"),
                args.optString("description"), args.optString("location"), args.optString("timezone"),
                stringList(args.optJSONArray("attendees")), args.optString("calendar_id", "primary")
            )
            "calendar_update" -> Connectors.calendarUpdate(
                ctx, req(args, "event_id"), args.optString("summary"), args.optString("start"),
                args.optString("end"), args.optString("description"), args.optString("location"),
                args.optString("timezone"), args.optString("calendar_id", "primary")
            )
            "calendar_delete" -> Connectors.calendarDelete(
                ctx, req(args, "event_id"), args.optString("calendar_id", "primary")
            )
            "calendar_quick_add" -> Connectors.calendarQuickAdd(ctx, req(args, "text"))
            "notion_search" -> Connectors.notionSearch(ctx, args.optString("query"), int(args, "max", 10))
            "notion_read" -> Connectors.notionRead(ctx, req(args, "page_id"), int(args, "max_chars", 12_000))
            "notion_create" -> Connectors.notionCreate(
                ctx, req(args, "parent_page"), req(args, "title"), req(args, "content")
            )
            "notion_append" -> Connectors.notionAppend(ctx, req(args, "page_id"), req(args, "content"))
            "slack_channels" -> Connectors.slackChannels(ctx, int(args, "max", 50))
            "slack_post" -> Connectors.slackPost(ctx, req(args, "channel"), req(args, "text"))
            "slack_history" -> Connectors.slackHistory(ctx, req(args, "channel"), int(args, "max", 20))
            "connector_get" -> Connectors.genericRequest(ctx, "GET", req(args, "path"), "")
            "connector_request" -> Connectors.genericRequest(
                ctx, args.optString("method", "POST"), req(args, "path"), args.optString("body")
            )

            "web_fetch" -> {
                val page = WebTools.fetch(
                    req(args, "url"),
                    withLinks = args.optBoolean("links", true)
                )
                val maxChars = int(args, "max_chars", 12_000).coerceIn(1000, 30_000)
                buildString {
                    if (page.title.isNotBlank()) append("📄 ").append(page.title).append('\n')
                    append("🔗 ").append(page.finalUrl).append('\n')
                    append("الحجم: ").append(page.bytes).append(" بايت | ").append(page.contentType).append("\n\n")
                    append(page.text.take(maxChars))
                    if (page.text.length > maxChars) append("\n… [اقتُطع النص]")
                    if (page.links.isNotEmpty()) {
                        append("\n\n🔗 الروابط (${page.links.size}):\n")
                        page.links.take(40).forEach { (label, url) -> append("- ").append(label).append(" — ").append(url).append('\n') }
                    }
                    if (page.images.isNotEmpty()) {
                        append("\n🖼️ الصور (${page.images.size}):\n")
                        page.images.take(20).forEach { append("- ").append(it).append('\n') }
                    }
                }
            }
            "web_search" -> {
                val results = WebGrounding.search(task(args, "query"))
                if (results.isEmpty()) "لا نتائج." else results.joinToString("\n\n") {
                    "• ${it.title}\n${it.url}\n${it.snippet.take(240)}"
                }
            }
            "web_api" -> {
                val raw = WebTools.httpRequest(
                    req(args, "url"),
                    args.optString("method", "GET"),
                    args.optString("body"),
                    args.optString("content_type", "application/json")
                )
                val pretty = runCatching {
                    val trimmed = raw.trim()
                    if (trimmed.startsWith("[")) org.json.JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
                }.getOrDefault(raw)
                pretty.take(14_000)
            }
            "web_download" -> {
                val download = WebTools.download(ctx.context, req(args, "url"), args.optString("name"))
                val relative = FileTools.relative(ctx.context, download.file)
                "✅ نُزّل إلى $relative (${download.bytes} بايت)"
            }

            "file_list" -> {
                val entries = FileTools.list(ctx.context, args.optString("path"))
                if (entries.isEmpty()) "المساحة فارغة." else entries.joinToString("\n") {
                    (if (it.directory) "📁 " else "📄 ") + it.path + if (!it.directory) " (${it.size} بايت)" else ""
                }
            }
            "file_search" -> {
                val matches = FileTools.search(
                    ctx.context,
                    req(args, "query"),
                    args.optString("path"),
                    int(args, "max_results", 50).coerceIn(1, 200)
                )
                if (matches.isEmpty()) "لا نتائج." else matches.joinToString("\n") {
                    if (it.line == 0) "• ${it.path}" else "• ${it.path}:${it.line}: ${it.text}"
                }
            }
            "file_read" -> {
                val info = FileTools.read(ctx.context, req(args, "path"))
                if (info.binary) "📄 ${info.path} — ملف ثنائي (${info.size} بايت)" else
                    "📄 ${info.path} (${info.size} بايت${if (info.truncated) "، مقتطع" else ""})\n\n${info.text.take(14_000)}"
            }
            "file_write" -> {
                val entry = FileTools.write(ctx.context, req(args, "path"), args.optString("content"))
                "✅ كُتب ${entry.path} (${entry.size} بايت)"
            }
            "file_delete" -> {
                val ok = FileTools.delete(ctx.context, req(args, "path"), args.optBoolean("recursive", false))
                if (ok) "✅ حُذف" else "لم يوجد الملف"
            }
            "file_mkdir" -> {
                FileTools.mkdir(ctx.context, req(args, "path"))
                "✅ أُنشئ المجلد"
            }
            "file_zip_list" -> {
                val file = resolveWorkspace(ctx, req(args, "path"))
                FileTools.zipList(file).joinToString("\n") { (if (it.directory) "📁 " else "📄 ") + it.path }
            }
            "file_zip_read" -> {
                val file = resolveWorkspace(ctx, req(args, "path"))
                FileTools.zipRead(file, req(args, "entry")).take(14_000)
            }
            "file_zip_create" -> {
                val sources = args.optJSONArray("sources")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
                }.orEmpty()
                val entry = FileTools.zipCreate(ctx.context, req(args, "target"), sources)
                "✅ أُنشئ ${entry.path} (${entry.size} بايت)"
            }
            "file_unzip" -> {
                val count = FileTools.extractZip(
                    ctx.context, req(args, "path"), args.optString("target"),
                    args.optBoolean("overwrite", false)
                )
                "✅ استُخرج $count ملفاً"
            }
            "file_append" -> {
                val entry = FileTools.append(ctx.context, req(args, "path"), args.optString("content"))
                "✅ أُضيف إلى ${entry.path} (${entry.size} بايت)"
            }
            "file_move" -> {
                val entry = FileTools.move(
                    ctx.context, req(args, "from"), req(args, "to"), args.optBoolean("overwrite", false)
                )
                "✅ نُقل إلى ${entry.path}"
            }
            "file_copy" -> {
                val entry = FileTools.copy(
                    ctx.context, req(args, "from"), req(args, "to"), args.optBoolean("overwrite", false)
                )
                "✅ نُسخ إلى ${entry.path}"
            }
            "file_tree" -> {
                val entries = FileTools.walk(ctx.context, args.optString("path"), int(args, "max_files", 300).coerceIn(1, 5000))
                if (entries.isEmpty()) "المجلد فارغ."
                else entries.joinToString("\n") { (if (it.directory) "📁 " else "📄 ") + it.path + if (!it.directory) " (${it.size})" else "" }
            }
            "file_checksum" -> {
                val path = req(args, "path")
                val value = FileTools.checksum(ctx.context, path, args.optString("algorithm", "sha256"))
                "🔐 ${args.optString("algorithm", "sha256").uppercase()} $path\n$value"
            }
            "file_diff" -> FileTools.diff(
                ctx.context, req(args, "first"), req(args, "second")
            ).take(16_000)

            "db_list" -> DatabaseTools.list(ctx.context).joinToString("\n") { "• $it" }.ifBlank { "لا توجد قواعد بيانات." }
            "db_schema" -> DatabaseTools.schema(ctx.context, req(args, "db"))
            "db_query" -> {
                val sql = req(args, "sql")
                val verb = sql.trim().substringBefore(' ').lowercase()
                if (!ctx.allowWrite && verb !in setOf("select", "pragma", "explain", "with")) {
                    throw IllegalArgumentException("الكتابة في قاعدة البيانات معطّلة. فعّل «السماح بالكتابة» أولاً.")
                }
                DatabaseTools.query(ctx.context, req(args, "db"), sql, int(args, "max_rows", 200))
            }
            "db_import_json" -> DatabaseTools.importJson(
                ctx.context, req(args, "db"), req(args, "table"), req(args, "json"), args.optBoolean("replace", false)
            )
            "db_export_json" -> DatabaseTools.exportJson(ctx.context, req(args, "db"), req(args, "table"))
            "db_delete" -> {
                val ok = DatabaseTools.delete(ctx.context, req(args, "db"))
                if (ok) "✅ حُذفت قاعدة البيانات" else "لم توجد"
            }

            "code_analyze" -> {
                val name = args.optString("name", "code.txt")
                CodeAnalyzer.report(CodeAnalyzer.analyze(name, req(args, "code")))
            }
            "code_analyze_file" -> {
                val path = req(args, "path")
                val info = FileTools.read(ctx.context, path)
                if (info.binary) "الملف ثنائي" else CodeAnalyzer.report(CodeAnalyzer.analyze(path, info.text))
            }
            "code_patch" -> {
                val path = req(args, "path")
                val find = req(args, "find")
                val replace = args.optString("replace")
                val info = FileTools.read(ctx.context, path, maxBytes = 4_000_000)
                if (info.binary) throw IllegalArgumentException("الملف ثنائي ولا يمكن تعديله نصياً")
                val occurrences = Regex.escape(find).let { Regex(it) }.findAll(info.text).count()
                if (occurrences == 0) throw IllegalArgumentException("لم أجد النص المطلوب في $path")
                val updated = if (args.optBoolean("all", false)) info.text.replace(find, replace)
                else info.text.replaceFirst(find, replace)
                runCatching {
                    FileTools.write(ctx.context, ".lion-backup/" + path.replace('/', '_') + ".bak", info.text)
                }
                FileTools.write(ctx.context, path, updated)
                "✅ عُدّل $path ($occurrences موضع) — احتُفظ بنسخة احتياطية"
            }
            "code_review" -> {
                val entries = FileTools.walk(ctx.context, args.optString("path"), int(args, "max_files", 30).coerceIn(1, 100))
                val files = entries.asSequence()
                    .filter { CodeAnalyzer.languageFor(it.path) != "غير معروف" }
                    .mapNotNull { entry ->
                        runCatching {
                            val info = FileTools.read(ctx.context, entry.path)
                            if (info.binary) null else entry.path to info.text
                        }.getOrNull()
                    }
                    .toList()
                if (files.isEmpty()) "لا ملفات كود للمراجعة."
                else CodeAnalyzer.projectReport(files) + "\n\n🔎 راجع أيضاً: الأسرار المكشوفة، الاعتماديات غير المثبتة، وعدم معالجة الأخطاء."
            }
            "code_project_analyze" -> {
                val root = FileTools.walk(ctx.context, args.optString("path"), maxFiles = 1500)
                val max = int(args, "max_files", 80).coerceIn(1, 300)
                val files = root.asSequence()
                    .filter { CodeAnalyzer.languageFor(it.path) != "غير معروف" }
                    .take(max)
                    .mapNotNull { entry ->
                        runCatching {
                            val info = FileTools.read(ctx.context, entry.path)
                            if (info.binary) null else entry.path to info.text
                        }.getOrNull()
                    }
                    .toList()
                if (files.isEmpty()) "لا ملفات كود قابلة للتحليل." else CodeAnalyzer.projectReport(files)
            }

            "util_json" -> {
                val raw = req(args, "text").trim()
                val indent = int(args, "indent", 2).coerceIn(0, 8)
                runCatching {
                    if (raw.startsWith("[")) org.json.JSONArray(raw).toString(indent)
                    else JSONObject(raw).toString(indent)
                }.getOrElse { "❌ JSON غير صالح: ${it.message}" }
            }
            "util_base64" -> {
                val text = req(args, "text")
                if (args.optString("mode", "encode") == "decode") {
                    runCatching { String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                        .getOrElse { "❌ Base64 غير صالح" }
                } else {
                    android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                }
            }
            "util_hash" -> {
                val algorithm = when (args.optString("algorithm", "sha256").lowercase()) {
                    "md5" -> "MD5"
                    "sha1", "sha-1" -> "SHA-1"
                    else -> "SHA-256"
                }
                val digest = MessageDigest.getInstance(algorithm)
                digest.digest(req(args, "text").toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
            "util_url" -> {
                val text = req(args, "text")
                if (args.optString("mode", "encode") == "decode") {
                    WebTools.decodeUrl(text)
                } else {
                    java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
                }
            }
            "util_regex" -> {
                val regex = Regex(req(args, "pattern"), if (args.optBoolean("ignore_case", false)) setOf(RegexOption.IGNORE_CASE) else emptySet())
                val matches = regex.findAll(req(args, "text")).take(100).toList()
                if (matches.isEmpty()) "لا نتائج." else matches.joinToString("\n") { match ->
                    "• ${match.value}" + runCatching { " (${match.range.first}..${match.range.last})" }.getOrDefault("")
                }
            }
            "util_uuid" -> UUID.randomUUID().toString()
            "util_timestamp" -> {
                val now = System.currentTimeMillis()
                "Unix ms: $now\nUnix s: ${now / 1000}\nISO: ${java.time.Instant.ofEpochMilli(now)}"
            }
            "util_jwt" -> {
                val token = req(args, "token").removePrefix("Bearer ").trim()
                val parts = token.split('.')
                if (parts.size < 2) throw IllegalArgumentException("JWT غير صالح")
                fun decode(part: String): String = String(
                    java.util.Base64.getUrlDecoder().decode(part + "=".repeat((4 - part.length % 4) % 4)),
                    Charsets.UTF_8
                )
                val header = decode(parts[0])
                val payload = decode(parts[1])
                val exp = runCatching { JSONObject(payload).optLong("exp", 0L) }.getOrDefault(0L)
                val expiry = if (exp > 0) java.time.Instant.ofEpochSecond(exp).toString() else "غير محدد"
                "Header:\n$header\n\nPayload:\n$payload\n\nExpiry: $expiry"
            }
            "util_cron" -> {
                val cron = req(args, "cron").trim().split(Regex("\\s+"))
                if (cron.size != 5) throw IllegalArgumentException("Cron يجب أن يكون 5 حقول")
                val next = com.lion.ai.UtilTools.nextCron(cron)
                "التعبير: ${cron.joinToString(" ")}\n" + com.lion.ai.UtilTools.describeCron(cron) +
                    if (next != null) "\nأقرب تنفيذ (تقريبي): $next" else ""
            }
            "util_diff" -> com.lion.ai.UtilTools.diffText(req(args, "first"), req(args, "second")).take(16_000)
            "util_csv" -> com.lion.ai.UtilTools.csv(req(args, "text"), args.optString("query"))
            "util_slug" -> req(args, "text")
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
                .take(80)
                .ifBlank { "item" }
            "util_color" -> com.lion.ai.UtilTools.color(req(args, "color"))
            "util_regex_replace" -> {
                val options = if (args.optBoolean("ignore_case", false)) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(req(args, "pattern"), options)
                    .replace(req(args, "text"), args.optString("replacement"))
                    .take(16_000)
            }
            else -> "❌ أداة غير معروفة: $name"
        }
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { value -> value.isNotBlank() } }
    }

    private fun task(args: JSONObject, key: String): String =
        args.optString(key).trim().ifBlank { throw IllegalArgumentException("المعامل «$key» مطلوب") }

    private fun resolveWorkspace(ctx: ToolContext, path: String): java.io.File {
        val root = FileTools.root(ctx.context).canonicalFile
        val file = java.io.File(root, path.trim().removePrefix("/")).canonicalFile
        if (file != root && !file.path.startsWith(root.path + java.io.File.separator)) {
            throw IllegalArgumentException("مسار غير مسموح")
        }
        return file
    }
}

package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    CONNECTORS("connectors", "الموصلات", "🔌"),
    COMMS("comms", "الاتصالات", "💬"),
    SHELL("shell", "التنفيذ والبناء", "⚡"),
    QUALITY("quality", "بوابات الجودة", "✅"),
    CONTROL("control", "الحوكمة والمخطط", "🛡️"),
    MEMORY("memory", "الذاكرة ونقاط الاستئناف", "🧠"),
    MCP("mcp", "خوادم MCP", "🔗"),
    META("meta", "إدارة الأدوات", "🧭")
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
    val allowWrite: Boolean,
    val chatId: String = "",
    val mode: ChatMode = ChatMode.CODE
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
        Def("github_bootstrap_project", ToolGroup.GITHUB, """{"name":"my-app","description":"","private":true,"base":"main","branch":"feature/setup","files":[{"path":"README.md","content":"# ..."}],"gitignore":"Kotlin","license":"mit","open_pr":false}""", "إنشاء مشروع GitHub كامل: مستودع + ملفات أولية + فرع + Pull Request اختياري", true),
        Def("github_create_repo_from_template", ToolGroup.GITHUB, """{"template":"owner/template-repo","name":"my-app","description":"","private":true}""", "إنشاء مستودع من قالب GitHub جاهز", true),

        // Web
        Def("web_fetch", ToolGroup.WEB, """{"url":"https://...","links":true,"max_chars":12000}""", "جلب صفحة واستخراج النص والروابط والصور"),
        Def("web_search", ToolGroup.WEB, """{"query":"..."}""", "بحث ويب سريع بمصادر"),
        Def("web_api", ToolGroup.WEB, """{"url":"https://...","method":"GET","body":"","content_type":"application/json"}""", "استدعاء API وإرجاع JSON/نص"),
        Def("web_download", ToolGroup.WEB, """{"url":"https://...","name":"file.bin"}""", "تنزيل ملف إلى مساحة العمل"),

        // Files
        Def("file_list", ToolGroup.FILES, """{"path":""}""", "عرض ملفات مساحة العمل"),
        Def("file_search", ToolGroup.FILES, """{"query":"TODO","path":"","max_results":50}""", "بحث نصي في مساحة العمل (اسم/مسار/محتوى)"),
        Def("file_read", ToolGroup.FILES, """{"path":"src/Main.kt","max_chars":12000}""", "قراءة ملف من مساحة العمل"),
        Def("file_read_many", ToolGroup.FILES, """{"paths":["src/Main.kt","README.md"],"max_chars":6000}""", "قراءة عدة ملفات في طلب واحد"),
        Def("file_stat", ToolGroup.FILES, """{"path":"src"}""", "حجم/نوع/أسطر ملف أو إحصاء مجلد"),
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
        Def("file_verify", ToolGroup.FILES, """{"path":"","algorithm":"sha256","baseline":"integrity-baseline.json","max_files":5000}""", "فحص سلامة المشروع: بصمات كل الملفات، الفارغة، المكررة، والمقارنة بخط أساس"),
        Def("file_integrity_save", ToolGroup.FILES, """{"path":"","output":"integrity-baseline.json","algorithm":"sha256"}""", "حفظ خط أساس لبصمات ملفات المشروع للتحقق لاحقاً", true),
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
        Def("dependency_audit", ToolGroup.CODE, """{"path":"","online":true,"max_files":5000}""", "تدقيق المكتبات: Gradle/Maven/npm/Python/Dart/Rust/Go + ثغرات OSV + تعارض الإصدارات"),
        Def("code_doctor", ToolGroup.CODE, """{"path":"","online":false,"max_files":3000}""", "تقرير جاهزية شامل: بنية، صحة كود، اختبارات، سلسلة بناء، اعتماديات، وحكم نهائي"),

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
        Def("connector_request", ToolGroup.CONNECTORS, """{"path":"/items","method":"POST","body":"{}"}""", "طلب GET/POST/PUT/PATCH إلى خدمة REST المخصّصة", true, "generic"),

        // Developer execution loop
        Def("github_push_workspace", ToolGroup.GITHUB, """{"repo":"owner/name","path":"repos/owner/name","branch":"main","message":"...","delete_missing":false,"dry_run":true}""", "رفع مجلد مساحة العمل دفعة واحدة عبر مقارنة blob (يدعم الثنائي والحذف الاختياري)", true),
        Def("github_get_run", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123}""", "حالة عملية بناء واحدة"),
        Def("github_wait_run", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"main","run_id":0,"timeout_sec":420}""", "انتظار انتهاء أحدث بناء ثم إرجاع سجل الفشل تلقائياً"),
        Def("github_run_logs", ToolGroup.GITHUB, """{"repo":"owner/name","run_id":123,"max_chars":12000}""", "سجل وظائف عملية بناء كامل (للفشل والنجاح)"),
        Def("github_compare", ToolGroup.GITHUB, """{"repo":"owner/name","base":"main","head":"feature"}""", "مقارنة فرعين: commits والملفات والفروق"),
        Def("github_pr_files", ToolGroup.GITHUB, """{"repo":"owner/name","number":1}""", "ملفات PR وتغييراتها"),
        Def("github_merge_pr", ToolGroup.GITHUB, """{"repo":"owner/name","number":1,"method":"squash","commit_title":""}""", "دمج Pull Request", true),
        Def("github_review_pr", ToolGroup.GITHUB, """{"repo":"owner/name","number":1,"event":"APPROVE|REQUEST_CHANGES|COMMENT","body":"..."}""", "مراجعة PR واعتماده أو طلب تعديلات", true),
        Def("github_close_issue", ToolGroup.GITHUB, """{"repo":"owner/name","number":1}""", "إغلاق Issue", true),
        Def("github_list_tags", ToolGroup.GITHUB, """{"repo":"owner/name"}""", "وسوم الإصدارات"),
        Def("github_create_tag", ToolGroup.GITHUB, """{"repo":"owner/name","name":"v1.0.0","from":"main"}""", "إنشاء وسم إصدار", true),
        Def("github_create_release", ToolGroup.GITHUB, """{"repo":"owner/name","tag":"v1.0.0","name":"","body":"","draft":false,"prerelease":false}""", "إنشاء Release", true),
        Def("github_delete_branch", ToolGroup.GITHUB, """{"repo":"owner/name","branch":"feature"}""", "حذف فرع (لا يحذف الفرع الافتراضي عادةً)", true),

        // Files: precise patch
        Def("file_apply_patch", ToolGroup.FILES, """{"path":"src/Main.kt","patch":"@@ -1,2 +1,2 @@\n ...","backup":true}""", "تطبيق unified diff بدقة ورفض عدم التطابق", true),

        // Meta / tool discovery
        Def("tools_schema", ToolGroup.META, """{"group":"github|files|code|comms|web|database|util|connectors"}""", "تحميل مخطط أدوات مجموعة لم تظهر في السياق"),
        Def("tools_groups", ToolGroup.META, "{}", "حالة مجموعات الأدوات والموصلات والصلاحيات"),

        // Telegram Bot API
        Def("telegram_status", ToolGroup.COMMS, "{}", "بيانات البوت وقدراته", connector = "telegram"),
        Def("telegram_updates", ToolGroup.COMMS, """{"offset":0,"limit":50}""", "أحدث تحديثات البوت (رسائل/أزرار) مع تحديث offset تلقائياً", connector = "telegram"),
        Def("telegram_send", ToolGroup.COMMS, """{"chat_id":"...","text":"...","parse_mode":"Markdown","reply_to":0,"thread_id":0}""", "إرسال رسالة Telegram", true, "telegram"),
        Def("telegram_send_media", ToolGroup.COMMS, """{"chat_id":"...","type":"photo","path":"downloads/a.jpg","url":"","caption":""}""", "إرسال صورة/ملف/فيديو/صوت من مساحة العمل أو رابط", true, "telegram"),
        Def("telegram_edit", ToolGroup.COMMS, """{"chat_id":"...","message_id":1,"text":"...","parse_mode":"Markdown"}""", "تعديل رسالة بوت", true, "telegram"),
        Def("telegram_delete", ToolGroup.COMMS, """{"chat_id":"...","message_id":1}""", "حذف رسالة", true, "telegram"),
        Def("telegram_forward", ToolGroup.COMMS, """{"chat_id":"...","from_chat_id":"...","message_id":1}""", "تحويل رسالة", true, "telegram"),
        Def("telegram_pin", ToolGroup.COMMS, """{"chat_id":"...","message_id":1,"notify":false}""", "تثبيت رسالة", true, "telegram"),
        Def("telegram_chat_info", ToolGroup.COMMS, """{"chat_id":"..."}""", "بيانات محادثة/قناة/مجموعة", connector = "telegram"),
        Def("telegram_members", ToolGroup.COMMS, """{"chat_id":"..."}""", "مشرفو المجموعة/القناة", connector = "telegram"),
        Def("telegram_file_download", ToolGroup.COMMS, """{"file_id":"...","name":"file.bin"}""", "تنزيل ملف أرسله البوت إلى مساحة العمل", true, "telegram"),
        Def("telegram_webhook_set", ToolGroup.COMMS, """{"url":"https://...","secret":""}""", "ضبط Webhook للبوت", true, "telegram"),
        Def("telegram_webhook_delete", ToolGroup.COMMS, "{}", "حذف Webhook (لتفعيل polling)", true, "telegram"),
        Def("telegram_webhook_info", ToolGroup.COMMS, "{}", "حالة Webhook", connector = "telegram"),
        Def("telegram_poll", ToolGroup.COMMS, """{"chat_id":"...","question":"...","options":["أ","ب"]}""", "إرسال استطلاع", true, "telegram"),
        Def("telegram_commands", ToolGroup.COMMS, """{"commands":["start:ابدأ","help:مساعدة"]}""", "ضبط أوامر البوت", true, "telegram"),
        Def("telegram_invite", ToolGroup.COMMS, """{"chat_id":"...","name":"","member_limit":0}""", "إنشاء رابط دعوة", true, "telegram"),
        Def("telegram_admin", ToolGroup.COMMS, """{"chat_id":"...","user_id":123,"action":"ban|unban|promote"}""", "إدارة عضو (حظر/رفع حظر/ترقية)", true, "telegram"),

        // WhatsApp Business Cloud API
        Def("whatsapp_status", ToolGroup.COMMS, "{}", "بيانات رقم WhatsApp Business وجودته", connector = "whatsapp"),
        Def("whatsapp_send", ToolGroup.COMMS, """{"to":"9665...","text":"...","preview_url":false}""", "إرسال رسالة نصية WhatsApp Business", true, "whatsapp"),
        Def("whatsapp_template", ToolGroup.COMMS, """{"to":"9665...","name":"hello_world","language":"ar","components":[]}""", "إرسال قالب معتمد", true, "whatsapp"),
        Def("whatsapp_media", ToolGroup.COMMS, """{"to":"9665...","type":"image","link":"","path":"downloads/a.jpg","caption":""}""", "إرسال وسائط (رابط أو ملف)", true, "whatsapp"),
        Def("whatsapp_mark_read", ToolGroup.COMMS, """{"message_id":"wamid..."}""", "تعليم رسالة كمقروءة", true, "whatsapp"),
        Def("whatsapp_templates", ToolGroup.COMMS, """{"waba_id":"..."}""", "قوالب WhatsApp Business", connector = "whatsapp"),
        Def("whatsapp_profile", ToolGroup.COMMS, "{}", "ملف نشاط WhatsApp Business", connector = "whatsapp"),
        Def("whatsapp_numbers", ToolGroup.COMMS, """{"waba_id":"..."}""", "أرقام WABA", connector = "whatsapp"),
        Def("whatsapp_download", ToolGroup.COMMS, """{"media_id":"...","name":"file.bin"}""", "تنزيل وسائط WhatsApp إلى مساحة العمل", true, "whatsapp"),

        // On-device notification bridge (WhatsApp/Telegram personal accounts)
        Def("notifications_status", ToolGroup.COMMS, "{}", "حالة صلاحية الإشعارات وعدد الرسائل المحفوظة"),
        Def("notifications_list", ToolGroup.COMMS, """{"app":"whatsapp|telegram|","query":"","since_minutes":1440,"limit":50}""", "قراءة إشعارات WhatsApp/Telegram المحفوظة محلياً"),
        Def("notifications_chats", ToolGroup.COMMS, """{"since_minutes":1440}""", "ملخص المحادثات من الإشعارات"),
        Def("notifications_reply", ToolGroup.COMMS, """{"key":"...","text":"..."}""", "رد مباشر على إشعار نشط (RemoteInput)", true),
        Def("notifications_dismiss", ToolGroup.COMMS, """{"key":"..."}""", "إغلاق إشعار", true),
        Def("notifications_clear", ToolGroup.COMMS, "{}", "مسح سجل الإشعارات المحلي", true),

        // Control plane: Manifest + DAG + gates
        Def("manifest_create", ToolGroup.CONTROL, """{"project":"my-app","root":"","entry_points":["app/src/main/java/.../MainActivity.kt"],"files":[{"path":"README.md","purpose":"...","contract":"..."}],"tasks":[{"id":"t1","role":"implementer","title":"...","depends_on":[],"files":["..."]}]}""", "إنشاء Manifest إجباري: شجرة ملفات وعقود ومهام DAG (لا كتابة كود قبله)", true),
        Def("manifest_read", ToolGroup.CONTROL, "{}", "قراءة Manifest المحادثة الحالية"),
        Def("manifest_update", ToolGroup.CONTROL, """{"path":"src/Main.kt","status":"written","purpose":"","contract":""}""", "تحديث حالة ملف في Manifest", true),
        Def("manifest_validate", ToolGroup.CONTROL, """{"root":""}""", "فحص بنيوي قبل الاعتماد، وبوابة كاملة بعده"),
        Def("gate_run", ToolGroup.QUALITY, """{"root":""}""", "بوابة الجودة الميكانيكية: Manifest + ملفات + lint + Root Reachability + HMAC"),
        Def("root_reachability", ToolGroup.QUALITY, """{"root":""}""", "فحص وصول كل ملف مصدري إلى نقطة دخول وكشف الملفات اليتيمة"),
        Def("gate_sign_tests", ToolGroup.QUALITY, """{"root":""}""", "توقيع ملفات الاختبار الحالية بـ HMAC-SHA256", true),
        Def("gate_verify_tests", ToolGroup.QUALITY, """{"root":""}""", "التحقق من عدم العبث بملفات الاختبار الموقّعة"),
        Def("pipeline_start", ToolGroup.CONTROL, """{"project":"my-app","root":""}""", "بدء دورة DAG: تخطيط ← Manifest ← تنفيذ ← بوابة ← مراجعة ← QA ← بناء", true),
        Def("pipeline_status", ToolGroup.CONTROL, "{}", "حالة دورة التطوير الحالية والمرحلة التالية"),
        Def("pipeline_approve", ToolGroup.CONTROL, "{}", "اعتماد Manifest بعد التحقق البنيوي والانتقال للتنفيذ", true),
        Def("pipeline_advance", ToolGroup.CONTROL, """{"note":""}""", "الانتقال للمرحلة التالية مع فحص البوابة عند اللزوم", true),
        Def("pipeline_reset", ToolGroup.CONTROL, "{}", "إعادة ضبط دورة التطوير لهذه المحادثة", true),

        // Persistent checkpoints and long-term memory
        Def("checkpoint_save", ToolGroup.MEMORY, """{"note":""}""", "حفظ نقطة استئناف دائمة بعد خطوة فرعية", true),
        Def("checkpoint_resume", ToolGroup.MEMORY, "{}", "استئناف آخر نقطة محفوظة لهذه المحادثة"),
        Def("checkpoint_list", ToolGroup.MEMORY, "{}", "قائمة نقاط الاستئناف المحفوظة"),
        Def("checkpoint_clear", ToolGroup.MEMORY, "{}", "حذف نقطة الاستئناف الحالية", true),
        Def("memory_save", ToolGroup.MEMORY, """{"project":"","title":"قرار معماري","body":"...","tags":["architecture"]}""", "حفظ قرار/درس معماري في الذاكرة طويلة المدى", true),
        Def("memory_search", ToolGroup.MEMORY, """{"query":"...","limit":10}""", "البحث في قرارات المشاريع السابقة"),
        Def("memory_list", ToolGroup.MEMORY, """{"limit":20}""", "أحدث قرارات الذاكرة طويلة المدى"),
        Def("memory_delete", ToolGroup.MEMORY, """{"id":""}""", "حذف قرار من الذاكرة", true),

        // Shell / Android build
        Def("shell_exec", ToolGroup.SHELL, """{"command":"git status","cwd":"","timeout_sec":120}""", "تنفيذ أمر مسموح داخل مساحة العمل مع مهلة وسقف مخرجات", true),
        Def("shell_which", ToolGroup.SHELL, """{"name":"git"}""", "التحقق من وجود أداة على الجهاز"),
        Def("android_build", ToolGroup.SHELL, """{"root":"","task":":app:assembleDebug","timeout_sec":600}""", "بناء Gradle محلي وأخطاء محللة file:line (يتطلب JDK/SDK)", true),
        Def("android_install_run", ToolGroup.SHELL, """{"apk":"downloads/app.apk","package_name":"com.example.app","activity":".MainActivity"}""", "تثبيت APK وتشغيله عبر adb", true),
        Def("android_logcat", ToolGroup.SHELL, """{"filter":"","lines":300}""", "قراءة logcat مفلتر"),

        // MCP servers
        Def("mcp_servers", ToolGroup.MCP, "{}", "خوادم MCP المضافة وحالتها"),
        Def("mcp_add_server", ToolGroup.MCP, """{"id":"files","name":"Filesystem MCP","url":"http://127.0.0.1:3001/mcp","token":"","enabled":true}""", "إضافة خادم MCP عبر Streamable HTTP", true),
        Def("mcp_remove_server", ToolGroup.MCP, """{"id":"files"}""", "حذف خادم MCP", true),
        Def("mcp_list_tools", ToolGroup.MCP, """{"server":"files"}""", "سرد أدوات خادم MCP"),
        Def("mcp_call", ToolGroup.MCP, """{"server":"files","tool":"read_file","args":{"path":"README.md"}}""", "استدعاء أداة من خادم MCP", true),
        Def("mcp_ping", ToolGroup.MCP, """{"server":"files"}""", "اختبار اتصال خادم MCP")
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
            "(github|\\bgit\\b|\\brepo\\b|\\brepository\\b|pull request|\\bpr\\b|issue|commit|branch|workflow|actions|readme|" +
                "merge|release|tag|compare|diff|log|" +
                "مستودع|مستودعات|ريبو|فرع|فروع|كوميت|بلاغ|بلاغات|طلب سحب|سحب|دفع|ارفع|رفع|دمج|إصدار|اصدار|وسم|" +
                "شجرة الملفات|ملفات المشروع|جيت هاب|غيث هاب|github|workflows|بناء التطبيق|سجل البناء|" +
                "إعادة البناء|اعادة البناء|rerun|dispatch|artifacts|مخرجات البناء|apk|فشل البناء|أصلح البناء|اصلح البناء)"
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
                "شجرة|tree|انقل ملف|انسخ ملف|بصمة|checksum|diff|فرق بين ملفين|patch|تعديل ملف|احذف ملف)"
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
        val comms = Regex(
            "(whatsapp|whats app|telegram|notification|inbox|chat|message|dm\\b|" +
                "واتساب|واتس اب|واتس|تلغرام|تيليجرام|تليجرام|تلجرام|إشعار|اشعار|إشعارات|اشعارات|" +
                "رسالة|رسائل|محادثة|محادثات|دردشة|صندوق الوارد|بوت|قناة|قنوات|مجموعة|مجموعات|رد تلقائي|" +
                "WhatsApp Business|Meta Cloud|remoteinput|تليغرام)"
        )
        if (comms.containsMatchIn(t)) groups.add(ToolGroup.COMMS)

        // أوامر تطويرية عامة: لا تحتاج كلمة مفتاحية مثل "كود" حتى تُفعّل أدوات العمل.
        val devAction = Regex(
            "(نفذ|أنفذ|انفذ|أكمل|اكمل|طور|طوّر|أصلح|اصلح|صحح|دقق|تدقيق|راجع|مراجعة|ابن|ابني|بناء|اختبر|جرّب|ادفع|ارفع|" +
                "عدل|عدّل|أضف|اضف|احذف|برمج|اكتب كود|حلل|حلّل|افحص|فحص|أعد|اعد|اعمل|انشئ|أنشئ|" +
                "implement|fix|audit|review|lint|inspect|build|test|deploy|push|commit|refactor|add|remove|update|run|verify|" +
                "debug|create|write|patch|merge|release)"
        )
        if (devAction.containsMatchIn(t)) {
            groups.add(ToolGroup.FILES)
            groups.add(ToolGroup.CODE)
            if (githubReady(prefs)) groups.add(ToolGroup.GITHUB)
        }
        val defaultRepo = prefs.getString("github_default_repo", "")?.trim().orEmpty()
        if (defaultRepo.isNotBlank() && githubReady(prefs) &&
            (ToolGroup.FILES in groups || ToolGroup.CODE in groups)
        ) {
            groups.add(ToolGroup.GITHUB)
        }
        val wantsTools = Regex("(أداة|اداة|أدوات|ادوات|tool|tools|وكيل|agent)").containsMatchIn(t)
        if (groups.isNotEmpty() || wantsTools) groups.add(ToolGroup.META)
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

    fun githubMentioned(text: String): Boolean =
        Regex("(github|\\bgit\\b|\\brepo\\b|\\brepository\\b|مستودع|مستودعات|جيت هاب|غيث هاب)")
            .containsMatchIn(TextNorm.forMatch(text))

    fun contextHint(text: String, prefs: SharedPreferences? = null): String {
        val ref = detectGitHubUrl(text)
        val fallback = prefs?.getString("github_default_repo", "")?.trim().orEmpty()
        if (ref == null && fallback.isBlank()) return ""
        return buildString {
            append("\n\nسياق GitHub")
            if (ref != null) {
                append(" من رسالة المستخدم: owner=${ref.owner} repo=${ref.repo}")
                if (ref.branch.isNotBlank()) append(" branch=${ref.branch}")
                if (ref.path.isNotBlank()) append(" path=${ref.path}")
            } else {
                append(": المستودع الافتراضي هو $fallback")
            }
            if (prefs == null || githubReady(prefs)) {
                append("\nلديك وصول حقيقي إلى GitHub عبر الأدوات المذكورة أدناه؛ استخدم المستودع/الملف مباشرة دون سؤال المستخدم عن المالك أو الاسم، ولا تعتذر عن عدم القدرة على الوصول قبل استدعاء الأداة.")
            } else {
                append("\nGitHub غير مربوط حالياً؛ اطلب من المستخدم إضافة توكن GitHub من الإعدادات ولا تدّعِ الوصول.")
            }
        }
    }

    suspend fun githubContext(prefs: SharedPreferences, text: String, maxChars: Int = 6000): String {
        val ref = detectGitHubUrl(text) ?: return ""
        val token = token(prefs)
        if (token.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            runCatching {
                val repo = GitHubClient.getRepo(token, ref.owner, ref.repo)
                val branch = ref.branch.ifBlank { repo.defaultBranch }
                buildString {
                    append("=== محتوى GitHub الحقيقي من الرابط في رسالة المستخدم ===\n")
                    append("المستودع: ").append(repo.fullName).append('\n')
                    if (repo.description.isNotBlank()) append("الوصف: ").append(repo.description).append('\n')
                    append("الفرع: ").append(branch).append(" | اللغة: ").append(repo.language).append('\n')
                    if (ref.path.isNotBlank()) {
                        val file = runCatching {
                            GitHubClient.readFile(token, ref.owner, ref.repo, ref.path, branch)
                        }.getOrNull()
                        if (file != null) {
                            if (file.binary) {
                                append("الملف ثنائي: ").append(file.path)
                            } else {
                                append("\n--- ").append(file.path).append(" ---\n").append(file.text)
                            }
                        } else {
                            val prefix = ref.path.trimEnd('/') + "/"
                            val tree = runCatching {
                                GitHubClient.listTree(token, ref.owner, ref.repo, branch, maxEntries = 300)
                            }.getOrDefault(emptyList())
                            val under = tree.filter {
                                it.path == ref.path || it.path.startsWith(prefix)
                            }.take(150)
                            if (under.isNotEmpty()) {
                                append("\n--- محتويات ").append(ref.path).append(" ---\n")
                                under.forEach {
                                    append(if (it.type == "dir") "📁 " else "📄 ").append(it.path).append('\n')
                                }
                            } else {
                                append("تعذر قراءة المسار ").append(ref.path)
                            }
                        }
                    } else {
                        val readme = runCatching {
                            GitHubClient.readFile(token, ref.owner, ref.repo, "README.md", branch)
                        }.getOrNull()
                        if (readme != null && !readme.binary && readme.text.isNotBlank()) {
                            append("\n--- README ---\n").append(readme.text.take(3000))
                        }
                        val tree = runCatching {
                            GitHubClient.listTree(token, ref.owner, ref.repo, branch, maxEntries = 120)
                        }.getOrDefault(emptyList())
                        if (tree.isNotEmpty()) {
                            append("\n--- شجرة الملفات ---\n")
                            tree.take(120).forEach {
                                append(if (it.type == "dir") "📁 " else "📄 ").append(it.path).append('\n')
                            }
                        }
                        val commits = runCatching {
                            GitHubClient.listCommits(token, ref.owner, ref.repo, branch)
                        }.getOrDefault(emptyList())
                        if (commits.isNotEmpty()) {
                            append("\n--- آخر التغييرات ---\n")
                            commits.take(8).forEach { append("• ").append(it).append('\n') }
                        }
                    }
                    append("\nاستخدم هذه البيانات للإجابة. للتعديل استخدم أدوات GitHub المذكورة في التعليمات.")
                }.take(maxChars.coerceIn(500, 20_000))
            }.getOrDefault("")
        }
    }

    fun spec(groups: Set<ToolGroup>, prefs: SharedPreferences): String {
        if (groups.isEmpty()) return ""
        val available = defs.filter { def ->
            def.connector == null || ConnectorAuth.isConnected(prefs, def.connector)
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
        if (ToolGroup.META in groups) {
            builder.append("إذا احتجت أداة غير ظاهرة في القائمة، استدعِ أولاً tools_schema مع اسم المجموعة ")
                .append("(github/files/code/comms/web/database/util/connectors/meta) ثم استخدم الأدوات التي تعود. ")
                .append("هذا يسمح بإنجاز مهام غير متوقعة دون تخمين أسماء الأدوات.\n")
        }
        if (ToolGroup.GITHUB in groups) {
            builder.append("GitHub مربوط بتوكن صالح، ولديك وصول حقيقي عبر الأدوات التالية. ")
                .append("ابدأ بـ github_repo_overview أو github_list_tree أو github_read_file، ولا تقل إنك لا تستطيع الوصول إلى GitHub. ")
                .append("حلقة التطوير: clone أو اقرأ الملفات، عدّل، راجع file_diff، ارفع عبر github_push_workspace ")
                .append("(استخدم dry_run أولاً عند الشك)، ثم github_wait_run لقراءة فشل CI، ثم أصلح وأعد الرفع. ")
                .append("لا تدفع إلى main مباشرة إن كان ذلك خطراً؛ أنشئ فرعاً وPR.\n")
        }
        if (ToolGroup.COMMS in groups) {
            builder.append("الاتصالات: WhatsApp Business Cloud API لإرسال القوالب/الرسائل الرسمية، ")
                .append("Telegram Bot API للبوت والقنوات التي أُضيف إليها، ")
                .append("وإشعارات WhatsApp/Telegram الشخصية تُقرأ محلياً بعد منح صلاحية الإشعارات. ")
                .append("لا تدّعِ قراءة سجل شخصي كامل لم يُلتقط، ولا ترسل لأحد دون طلب صريح.\n")
        }
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

    private val CODE_WRITE_TOOLS = setOf(
        "file_write", "file_append", "file_apply_patch", "file_delete", "file_move",
        "file_copy", "file_mkdir", "file_unzip", "file_integrity_save", "code_patch", "github_write_file",
        "github_commit_files", "github_push_workspace", "github_delete_file",
        "github_bootstrap_project", "github_create_repo", "github_create_branch"
    )

    suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult {
        val def = byName[call.name] ?: return ToolResult(call.name, "❌ أداة غير معروفة: ${call.name}", false)
        if (ctx.mode != ChatMode.CODE) {
            return ToolResult(
                call.name,
                "⛔ منع عزل السياق: الأدوات لا تعمل في وضع ${ctx.mode.label}. " +
                    "ارقِ المحادثة صراحةً إلى «هندسة وبرمجة» أولاً.",
                false
            )
        }
        if (def.write && !ctx.allowWrite) {
            return ToolResult(call.name, "⛔ الكتابة معطّلة. فعّل «السماح للذكاء بالكتابة» من الإعدادات أولاً.", false)
        }
        val shellWrite = call.name == "shell_exec" &&
            ShellTools.looksLikeWrite(call.args.optString("command"))
        if ((call.name in CODE_WRITE_TOOLS || shellWrite) && ctx.chatId.isNotBlank() &&
            !PipelineStore.canWrite(ctx.prefs, ctx.chatId)
        ) {
            return ToolResult(
                call.name,
                "⛔ حوكمة Manifest: لا يُسمح بكتابة/رفع كود قبل اعتماد Manifest هذه الجلسة. " +
                    "استخدم manifest_create ثم pipeline_approve (أو أنهِ الدورة الحالية) أولاً.",
                false
            )
        }
        val result = try {
            val output = run(ctx, call.name, call.args)
            if (def.write && ctx.chatId.isNotBlank()) {
                val path = call.args.optString("path").ifBlank { call.args.optString("from") }
                if (path.isNotBlank()) ManifestStore.markFile(ctx.prefs, ctx.chatId, path, "written")
            }
            ToolResult(call.name, output.take(16_000), true)
        } catch (e: GitHubException) {
            ToolResult(call.name, "❌ GitHub ${e.status}: ${e.message}", false)
        } catch (e: McpException) {
            ToolResult(call.name, "❌ MCP: ${e.message}", false)
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
            "github_bootstrap_project" -> {
                val name = req(args, "name")
                val repo = GitHubClient.createRepo(
                    token, name, args.optString("description"),
                    args.optBoolean("private", true), true,
                    args.optString("gitignore"), args.optString("license")
                )
                val (owner, repoName) = GitHubClient.parseFullName(repo.fullName)
                val base = args.optString("base").ifBlank { repo.defaultBranch }
                val branch = args.optString("branch").trim().ifBlank { base }
                if (branch != base) {
                    GitHubClient.createBranch(token, owner, repoName, branch, base)
                }
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
                val commit = if (files.isEmpty()) "" else GitHubClient.commitFiles(
                    token, owner, repoName, branch,
                    args.optString("message", "مشروع أولي من Lion AI"), files
                )
                var prUrl = ""
                if (args.optBoolean("open_pr", false) && branch != base) {
                    prUrl = GitHubClient.createPull(
                        token, owner, repoName,
                        args.optString("pr_title", "تهيئة المشروع"),
                        branch, base,
                        args.optString("pr_body", "أُنشئ تلقائياً بواسطة Lion AI.")
                    ).htmlUrl
                }
                buildString {
                    append("✅ أُنشئ المشروع ").append(repo.fullName).append('\n')
                    append("الفرع الأساسي: ").append(base)
                    if (branch != base) append(" | فرع العمل: ").append(branch)
                    append('\n')
                    if (files.isNotEmpty()) {
                        append("الملفات الأولية: ").append(files.size)
                        if (commit.isNotBlank()) append(" (commit ").append(commit.take(8)).append(")")
                        append('\n')
                    }
                    if (prUrl.isNotBlank()) append("PR: ").append(prUrl).append('\n')
                    append(repo.htmlUrl)
                }
            }
            "github_create_repo_from_template" -> {
                val (templateOwner, templateRepo) = GitHubClient.parseFullName(req(args, "template"))
                val repo = GitHubClient.createRepoFromTemplate(
                    token, templateOwner, templateRepo, req(args, "name"),
                    args.optString("description"), args.optBoolean("private", true),
                    args.optBoolean("include_all_branches", false)
                )
                "✅ أُنشئ ${repo.fullName} من القالب $templateOwner/$templateRepo\n${repo.htmlUrl}"
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
            "file_read_many" -> {
                val paths = stringList(args.optJSONArray("paths"))
                if (paths.isEmpty()) throw IllegalArgumentException("أرسل paths كمصفوفة مسارات")
                val max = int(args, "max_chars", 6000).coerceIn(500, 20_000)
                paths.take(12).joinToString("\n\n") { path ->
                    runCatching {
                        val info = FileTools.read(ctx.context, path)
                        "=== ${info.path} (${info.size} بايت${if (info.truncated) "، مقتطع" else ""}) ===\n" +
                            if (info.binary) "[ملف ثنائي]" else info.text.take(max)
                    }.getOrElse { "=== $path ===\n❌ ${it.message ?: it.javaClass.simpleName}" }
                }
            }
            "file_stat" -> {
                val path = args.optString("path").ifBlank { "" }
                val file = resolveWorkspace(ctx, path)
                if (!file.exists()) throw IllegalArgumentException("المسار غير موجود: $path")
                if (file.isDirectory) {
                    val entries = FileTools.walk(ctx.context, path, maxFiles = 5000)
                    val total = entries.sumOf { it.size }
                    val byExt = entries.groupBy { it.path.substringAfterLast('.', "").lowercase().ifBlank { "بدون امتداد" } }
                        .entries.sortedByDescending { group -> group.value.sumOf { it.size } }.take(8)
                    buildString {
                        append("📁 ").append(FileTools.relative(ctx.context, file)).append('\n')
                        append("الملفات: ").append(entries.size).append('\n')
                        append("الحجم: ").append(FileTools.formatSize(total)).append('\n')
                        append("الامتدادات: ").append(byExt.joinToString("، ") { "${it.key} (${it.value.size})" })
                    }
                } else {
                    val info = FileTools.read(ctx.context, FileTools.relative(ctx.context, file), 2_000_000)
                    val lines = if (info.binary) 0 else info.text.lineSequence().count()
                    buildString {
                        append("📄 ").append(info.path).append('\n')
                        append("النوع: ").append(FileTools.fileType(info.path)).append('\n')
                        FileTools.language(info.path)?.let { append("اللغة: ").append(it).append('\n') }
                        append("الحجم: ").append(FileTools.formatSize(info.size)).append('\n')
                        append("الأسطر: ").append(lines).append('\n')
                        append("ثنائي: ").append(if (info.binary) "نعم" else "لا").append('\n')
                        append("آخر تعديل: ").append(java.time.Instant.ofEpochMilli(file.lastModified()))
                    }
                }
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
            "file_verify" -> IntegrityTools.verify(
                ctx.context,
                args.optString("path"),
                args.optString("algorithm", "sha256"),
                args.optString("baseline"),
                int(args, "max_files", 5000).coerceIn(1, 5000)
            )
            "file_integrity_save" -> IntegrityTools.saveBaseline(
                ctx.context,
                args.optString("path"),
                args.optString("output", "integrity-baseline.json"),
                args.optString("algorithm", "sha256"),
                int(args, "max_files", 5000).coerceIn(1, 5000)
            )
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
            "dependency_audit" -> DependencyAudit.audit(
                ctx.context,
                args.optString("path"),
                args.optBoolean("online", true),
                int(args, "max_files", 5000).coerceIn(1, 5000)
            )
            "code_doctor" -> CodeDoctor.report(
                ctx.context,
                ctx.prefs,
                args.optString("path"),
                ctx.chatId,
                args.optBoolean("online", false),
                int(args, "max_files", 3000).coerceIn(1, 5000)
            )
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
            "tools_schema" -> {
                val raw = args.optString("group").trim().lowercase()
                val group = ToolGroup.values().firstOrNull { it.id == raw || it.name.lowercase() == raw }
                    ?: throw IllegalArgumentException(
                        "مجموعة غير معروفة. المتاح: " + ToolGroup.values().joinToString(", ") { it.id }
                    )
                spec(setOf(group), ctx.prefs).ifBlank {
                    "لا أدوات متاحة في «$raw» حالياً. قد تحتاج ربط موصل أو تفعيل الأدوات من الإعدادات."
                }
            }
            "tools_groups" -> buildString {
                append("🧭 حالة الأدوات\n")
                append("التشغيل التلقائي: ").append(if (agentEnabled(ctx.prefs)) "مفعّل" else "متوقف").append('\n')
                append("الكتابة والتنفيذ: ").append(if (ctx.allowWrite) "مسموح" else "معطّل").append('\n')
                ToolGroup.values().forEach { group ->
                    val count = defs.count { def ->
                        def.group == group && (def.connector == null || ConnectorAuth.isConnected(ctx.prefs, def.connector))
                    }
                    append("• ").append(group.icon).append(' ').append(group.label)
                        .append(" (").append(group.id).append("): ").append(count).append(" أداة")
                    if (group == ToolGroup.COMMS) {
                        append(" — إشعارات: ")
                            .append(if (NotificationAccess.isEnabled(ctx.context)) "مفعّلة" else "غير مفعّلة")
                    }
                    append('\n')
                }
                append("\nالموصلات المربوطة: ")
                    .append(ConnectorAuth.all.filter { ConnectorAuth.isConnected(ctx.prefs, it.id) }
                        .joinToString("، ") { it.name }.ifBlank { "لا شيء" })
            }
            "github_push_workspace" -> {
                val (owner, repo) = repoOf(ctx, args)
                val path = args.optString("path").trim().ifBlank { "repos/$owner/$repo" }
                val dir = resolveWorkspace(ctx, path)
                if (!dir.isDirectory) throw IllegalArgumentException("المسار ليس مجلداً: $path")
                GitHubClient.commitWorkspace(
                    token, owner, repo,
                    branch = args.optString("branch"),
                    message = args.optString("message", "تحديث من Lion AI"),
                    localRoot = dir,
                    deleteMissing = args.optBoolean("delete_missing", false),
                    dryRun = args.optBoolean("dry_run", false)
                )
            }
            "github_get_run" -> {
                val (owner, repo) = repoOf(ctx, args)
                val run = GitHubClient.getWorkflowRun(token, owner, repo, args.optLong("run_id"))
                runSummary(run) + "\n" + GitHubClient.listRunJobs(token, owner, repo, run.id)
                    .joinToString("\n") { "• ${it.name}: ${it.status}/${it.conclusion}" }
            }
            "github_wait_run" -> {
                val (owner, repo) = repoOf(ctx, args)
                val timeout = int(args, "timeout_sec", 420).coerceIn(30, 900)
                val interval = int(args, "interval_sec", 10).coerceIn(5, 60)
                val branch = args.optString("branch")
                val requestedId = args.optLong("run_id", 0L)
                val started = System.currentTimeMillis()
                var run: GitHubClient.WorkflowRun? = if (requestedId > 0) {
                    GitHubClient.getWorkflowRun(token, owner, repo, requestedId)
                } else {
                    null
                }
                if (run == null) {
                    while (run == null && System.currentTimeMillis() - started < 90_000) {
                        run = GitHubClient.listWorkflowRuns(
                            token, owner, repo, perPage = 15, branch = branch
                        ).firstOrNull { isoEpoch(it.createdAt) >= started - 120_000 }
                        if (run == null) kotlinx.coroutines.delay(5_000)
                    }
                }
                val target = run ?: throw IllegalStateException(
                    "لم أجد عملية بناء حديثة على الفرع ${branch.ifBlank { "الافتراضي" }}. تأكد من الدفع أو استخدم github_dispatch."
                )
                var current = target
                while (current.status != "completed" && System.currentTimeMillis() - started < timeout * 1000L) {
                    kotlinx.coroutines.delay(interval * 1000L)
                    current = GitHubClient.getWorkflowRun(token, owner, repo, current.id)
                }
                if (current.status != "completed") {
                    return "⏳ البناء #${current.number} ما زال ${current.status}.\n${runSummary(current)}"
                }
                val artifacts = runCatching {
                    GitHubClient.listArtifacts(token, owner, repo, current.id)
                }.getOrDefault(emptyList())
                if (current.conclusion == "success") {
                    return buildString {
                        append("✅ اكتمل البناء #${current.number} بنجاح.\n")
                        append(runSummary(current))
                        if (artifacts.isNotEmpty()) {
                            append("\nالمخرجات: ").append(artifacts.joinToString("، ") { "${it.name} (id=${it.id})" })
                        }
                    }
                }
                val jobs = GitHubClient.listRunJobs(token, owner, repo, current.id)
                val failed = jobs.filter { it.conclusion !in setOf("success", "skipped", "neutral", "") }
                val logs = failed.take(3).joinToString("\n\n") { job ->
                    val text = runCatching {
                        GitHubClient.jobLog(token, owner, repo, job.id, 12_000)
                    }.getOrDefault("(تعذر جلب السجل)")
                    "===== ${job.name} (${job.conclusion}) =====\n$text"
                }
                buildString {
                    append("❌ فشل البناء #${current.number} (${current.conclusion}).\n")
                    append(runSummary(current)).append('\n')
                    append(failed.joinToString("\n") { "• ${it.name}: ${it.conclusion}" })
                    if (logs.isNotBlank()) append("\n\nسجل الفشل:\n").append(logs.take(16_000))
                    append("\n\nأصلح السبب ثم أعد الرفع. يمكنك أيضاً github_rerun أو github_job_log.")
                }
            }
            "github_run_logs" -> {
                val (owner, repo) = repoOf(ctx, args)
                val runId = args.optLong("run_id")
                val max = int(args, "max_chars", 12_000).coerceIn(2_000, 16_000)
                val jobs = GitHubClient.listRunJobs(token, owner, repo, runId)
                if (jobs.isEmpty()) "لا وظائف لهذه العملية."
                else jobs.joinToString("\n\n") { job ->
                    "===== ${job.name}: ${job.status}/${job.conclusion} =====\n" +
                        runCatching { GitHubClient.jobLog(token, owner, repo, job.id, max) }
                            .getOrDefault("(تعذر جلب السجل)")
                }.take(16_000)
            }
            "github_compare" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.compare(token, owner, repo, req(args, "base"), req(args, "head"))
            }
            "github_pr_files" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.pullFiles(token, owner, repo, args.optInt("number"))
            }
            "github_merge_pr" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.mergePull(
                    token, owner, repo, args.optInt("number"),
                    args.optString("commit_title"), args.optString("method", "merge")
                )
            }
            "github_review_pr" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.reviewPull(
                    token, owner, repo, args.optInt("number"),
                    args.optString("event", "COMMENT"), args.optString("body")
                )
            }
            "github_close_issue" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.closeIssue(token, owner, repo, args.optInt("number"))
            }
            "github_list_tags" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.listTags(token, owner, repo).joinToString("\n").ifBlank { "لا وسوم." }
            }
            "github_create_tag" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.createTag(token, owner, repo, req(args, "name"), args.optString("from"))
            }
            "github_create_release" -> {
                val (owner, repo) = repoOf(ctx, args)
                GitHubClient.createRelease(
                    token, owner, repo, req(args, "tag"), args.optString("name"),
                    args.optString("body"), args.optBoolean("draft", false), args.optBoolean("prerelease", false)
                )
            }
            "github_delete_branch" -> {
                val (owner, repo) = repoOf(ctx, args)
                val branch = req(args, "branch")
                GitHubClient.deleteBranch(token, owner, repo, branch)
                "✅ حُذف الفرع $branch"
            }
            "file_apply_patch" -> {
                val path = req(args, "path")
                val info = FileTools.read(ctx.context, path, maxBytes = 4_000_000)
                if (info.binary) throw IllegalArgumentException("الملف ثنائي ولا يمكن تعديله نصياً")
                val applied = UnifiedDiff.apply(info.text, req(args, "patch"))
                if (args.optBoolean("backup", true)) {
                    runCatching {
                        FileTools.write(ctx.context, ".lion-backup/" + path.replace('/', '_') + ".bak", info.text)
                    }
                }
                FileTools.write(ctx.context, path, applied.text)
                "✅ طُبّق الـ patch على $path — +${applied.added}/-${applied.removed} (${applied.hunks} hunk)"
            }
            "telegram_status" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val me = TelegramClient.getMe(bot)
                buildString {
                    append("🤖 ").append(TelegramClient.formatUser(me))
                    if (me.optString("username").isNotBlank()) append(" — @").append(me.optString("username"))
                    append("\nid: ").append(me.optLong("id"))
                    append("\ngroups: ").append(me.optBoolean("can_join_groups"))
                    append(" · read_all_group_messages: ").append(me.optBoolean("can_read_all_group_messages"))
                    append(" · inline: ").append(me.optBoolean("supports_inline_queries"))
                }
            }
            "telegram_updates" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val saved = ctx.prefs.getLong("telegram_update_offset", 0L)
                val offset = if (args.has("offset") && args.optLong("offset") > 0) args.optLong("offset") else saved
                val updates = TelegramClient.getUpdates(bot, offset, int(args, "limit", 50).coerceIn(1, 100), 0)
                if (updates.length() == 0) {
                    "لا تحديثات جديدة. إن كان Webhook مفعّلاً فاحذفه أولاً (telegram_webhook_delete) ليصل polling."
                } else {
                    var maxId = offset
                    val lines = (0 until updates.length()).mapNotNull { index ->
                        val update = updates.optJSONObject(index) ?: return@mapNotNull null
                        maxId = maxOf(maxId, update.optLong("update_id") + 1)
                        telegramUpdateLine(update)
                    }
                    ctx.prefs.edit().putLong("telegram_update_offset", maxId).apply()
                    "آخر ${lines.size} تحديث (offset التالي: $maxId):\n" + lines.joinToString("\n")
                }
            }
            "telegram_send" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val message = TelegramClient.sendMessage(
                    bot, req(args, "chat_id"), req(args, "text"),
                    args.optString("parse_mode"), args.optLong("reply_to", 0L),
                    args.optBoolean("disable_preview", false), args.optLong("thread_id", 0L)
                )
                "✅ أُرسلت إلى ${message.optJSONObject("chat")?.optString("title") ?: req(args, "chat_id")} — " +
                    "message_id=${message.optLong("message_id")}"
            }
            "telegram_send_media" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val path = args.optString("path").trim()
                val file = if (path.isNotBlank()) resolveWorkspace(ctx, path) else null
                val message = TelegramClient.sendMedia(
                    bot, req(args, "chat_id"), args.optString("type", "photo"),
                    file, args.optString("url"), args.optString("caption"),
                    args.optString("parse_mode"), args.optLong("reply_to", 0L), args.optLong("thread_id", 0L)
                )
                "✅ أُرسلت الوسائط — message_id=${message.optLong("message_id")}"
            }
            "telegram_edit" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val result = TelegramClient.editMessageText(
                    bot, req(args, "chat_id"), args.optLong("message_id"), req(args, "text"),
                    args.optString("parse_mode")
                )
                "✅ عُدّلت الرسالة ${result.optLong("message_id")}"
            }
            "telegram_delete" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                TelegramClient.deleteMessage(bot, req(args, "chat_id"), args.optLong("message_id"))
                "✅ حُذفت الرسالة"
            }
            "telegram_forward" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val result = TelegramClient.forwardMessage(
                    bot, req(args, "chat_id"), req(args, "from_chat_id"), args.optLong("message_id")
                )
                "✅ حُوّلت الرسالة — message_id=${result.optLong("message_id")}"
            }
            "telegram_pin" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                TelegramClient.pinMessage(
                    bot, req(args, "chat_id"), args.optLong("message_id"), args.optBoolean("notify", false)
                )
                "✅ ثُبّتت الرسالة"
            }
            "telegram_chat_info" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val chat = TelegramClient.getChat(bot, req(args, "chat_id"))
                val members = runCatching { TelegramClient.getChatMemberCount(bot, req(args, "chat_id")) }.getOrDefault(0)
                buildString {
                    append("💬 ").append(chat.optString("title").ifBlank { TelegramClient.formatUser(chat) })
                    append("\ntype: ").append(chat.optString("type"))
                    append(" · id: ").append(chat.optLong("id"))
                    if (chat.optString("username").isNotBlank()) append(" · @").append(chat.optString("username"))
                    if (members > 0) append(" · members: ").append(members)
                    if (chat.optString("description").isNotBlank()) {
                        append("\n").append(chat.optString("description").take(1200))
                    }
                }
            }
            "telegram_members" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val admins = TelegramClient.getChatAdministrators(bot, req(args, "chat_id"))
                if (admins.length() == 0) "لا مشرفين أو البوت ليس مشرفاً."
                else (0 until admins.length()).joinToString("\n") { index ->
                    val item = admins.optJSONObject(index) ?: return@joinToString ""
                    "• ${TelegramClient.formatUser(item.optJSONObject("user"))} — ${item.optString("status")}"
                }
            }
            "telegram_file_download" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val name = args.optString("name").ifBlank { "telegram-file.bin" }.replace('/', '_')
                val target = resolveWorkspace(ctx, "downloads/$name")
                val file = TelegramClient.downloadFile(bot, req(args, "file_id"), target)
                "✅ نُزّل ${FileTools.relative(ctx.context, file)} (${FileTools.formatSize(file.length())})"
            }
            "telegram_webhook_set" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                TelegramClient.setWebhook(bot, req(args, "url"), args.optString("secret"))
                "✅ ضُبط Webhook"
            }
            "telegram_webhook_delete" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                TelegramClient.deleteWebhook(bot)
                "✅ حُذف Webhook (polling متاح الآن)"
            }
            "telegram_webhook_info" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val info = TelegramClient.getWebhookInfo(bot)
                "url: ${info.optString("url").ifBlank { "—" }}\n" +
                    "pending: ${info.optInt("pending_update_count")}\n" +
                    "last_error: ${info.optString("last_error_message").ifBlank { "—" }}"
            }
            "telegram_poll" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val options = stringList(args.optJSONArray("options"))
                if (options.size < 2) throw IllegalArgumentException("أرسل خيارين على الأقل في options")
                val result = TelegramClient.sendPoll(bot, req(args, "chat_id"), req(args, "question"), options)
                "✅ أُنشئ استطلاع — message_id=${result.optLong("message_id")}"
            }
            "telegram_commands" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val commands = stringList(args.optJSONArray("commands")).mapNotNull { raw ->
                    val parts = raw.split(':', limit = 2)
                    parts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { command ->
                        command to parts.getOrElse(1) { command }
                    }
                }
                if (commands.isEmpty()) throw IllegalArgumentException("أرسل commands بصيغة command:description")
                TelegramClient.setMyCommands(bot, commands)
                "✅ ضُبطت ${commands.size} أوامر"
            }
            "telegram_invite" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val link = TelegramClient.createInviteLink(
                    bot, req(args, "chat_id"), args.optString("name"), args.optInt("member_limit", 0)
                )
                "✅ ${link.optString("invite_link")}"
            }
            "telegram_admin" -> {
                val bot = ConnectorAuth.token(ctx.prefs, "telegram")
                val chatId = req(args, "chat_id")
                val userId = args.optLong("user_id")
                when (args.optString("action", "ban").lowercase()) {
                    "unban" -> TelegramClient.unbanChatMember(bot, chatId, userId)
                    "promote" -> TelegramClient.promoteChatMember(bot, chatId, userId, true)
                    else -> TelegramClient.banChatMember(bot, chatId, userId)
                }
                "✅ نُفّذ إجراء ${args.optString("action", "ban")} على $userId"
            }
            "whatsapp_status" -> WhatsAppClient.status(ConnectorAuth.token(ctx.prefs, "whatsapp"))
            "whatsapp_send" -> WhatsAppClient.sendText(
                ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "to"), req(args, "text"),
                args.optBoolean("preview_url", false)
            )
            "whatsapp_template" -> {
                val components = when (val raw = args.opt("components")) {
                    is JSONArray -> raw
                    is String -> runCatching { JSONArray(raw) }.getOrNull()
                    else -> null
                }
                WhatsAppClient.sendTemplate(
                    ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "to"),
                    req(args, "name"), args.optString("language", "ar"), components
                )
            }
            "whatsapp_media" -> {
                val path = args.optString("path").trim()
                WhatsAppClient.sendMedia(
                    ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "to"),
                    args.optString("type", "image"), args.optString("link"),
                    if (path.isBlank()) "" else resolveWorkspace(ctx, path).absolutePath,
                    args.optString("caption"), args.optString("filename")
                )
            }
            "whatsapp_mark_read" -> WhatsAppClient.markRead(
                ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "message_id")
            )
            "whatsapp_templates" -> WhatsAppClient.listTemplates(
                ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "waba_id")
            )
            "whatsapp_profile" -> WhatsAppClient.profile(ConnectorAuth.token(ctx.prefs, "whatsapp"))
            "whatsapp_numbers" -> WhatsAppClient.phoneNumbers(
                ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "waba_id")
            )
            "whatsapp_download" -> {
                val name = args.optString("name").ifBlank { "whatsapp-media.bin" }.replace('/', '_')
                val target = resolveWorkspace(ctx, "downloads/$name")
                val file = WhatsAppClient.downloadMedia(
                    ConnectorAuth.token(ctx.prefs, "whatsapp"), req(args, "media_id"), target
                )
                "✅ نُزّل ${FileTools.relative(ctx.context, file)} (${FileTools.formatSize(file.length())})"
            }
            "notifications_status" -> NotificationAccess.describe(ctx.context, ctx.prefs) +
                "\nخدمة الاستماع: " + (if (LionNotificationListener.isConnected()) "متصلة ✅" else "غير متصلة ❌") +
                (if (!NotificationAccess.isEnabled(ctx.context)) {
                    "\nافتح: الأدوات ← الاتصالات ← منح صلاحية الإشعارات."
                } else "")
            "notifications_list" -> {
                val limit = int(args, "limit", 50).coerceIn(1, 200)
                val minutes = int(args, "since_minutes", 1440).coerceIn(0, 60 * 24 * 30)
                val since = if (minutes > 0) System.currentTimeMillis() - minutes * 60_000L else 0L
                val query = args.optString("query")
                val packages = notificationPackages(args.optString("app"))
                val rows = packages.flatMap { pkg ->
                    NotificationStore.recent(ctx.context, pkg, query, since, limit)
                }.sortedByDescending { it.postTime }.take(limit)
                if (rows.isEmpty()) "لا رسائل محفوظة. تأكد من صلاحية الإشعارات ووصول رسائل جديدة."
                else rows.joinToString("\n") { item ->
                    "• [${item.appName}] ${item.conversation} — ${item.sender}: " +
                        "${item.body.replace("\n", " ").take(400)} " +
                        "(${NotificationStore.formatTime(item.postTime)} · key=${item.key})"
                }
            }
            "notifications_chats" -> {
                val minutes = int(args, "since_minutes", 1440).coerceIn(0, 60 * 24 * 30)
                val since = if (minutes > 0) System.currentTimeMillis() - minutes * 60_000L else 0L
                val chats = NotificationStore.chats(ctx.context, since, int(args, "limit", 60))
                if (chats.isEmpty()) "لا محادثات محفوظة."
                else chats.joinToString("\n") { (name, count) -> "• $name — $count رسالة" }
            }
            "notifications_reply" -> {
                val text = req(args, "text")
                val key = req(args, "key")
                when {
                    !NotificationAccess.isEnabled(ctx.context) ->
                        "⛔ صلاحية الإشعارات غير مفعّلة. فعّلها من إعدادات النظام."
                    !LionNotificationListener.isConnected() ->
                        "⛔ خدمة الإشعارات غير متصلة. أعد فتح التطبيق أو أعد منح الصلاحية."
                    LionNotificationListener.reply(key, text) -> "✅ أُرسل الرد المباشر عبر إشعار $key"
                    else -> "❌ الإشعار لم يعد نشطاً أو لا يدعم الرد المباشر. افتح المحادثة وأرسل يدوياً."
                }
            }
            "notifications_dismiss" -> {
                if (LionNotificationListener.dismiss(req(args, "key"))) "✅ أُغلق الإشعار"
                else "❌ الإشعار غير نشط أو الخدمة غير متصلة"
            }
            "notifications_clear" -> {
                NotificationStore.clear(ctx.context)
                "✅ مُسح سجل الإشعارات المحلي"
            }

            // ===== Control plane / Manifest =====
            "manifest_create" -> {
                requireChat(ctx)
                val project = req(args, "project")
                val root = args.optString("root").trim()
                val entries = stringList(args.optJSONArray("entry_points"))
                val files = mutableListOf<ManifestFile>()
                args.optJSONArray("files")?.let { array ->
                    for (i in 0 until array.length()) {
                        when (val item = array.opt(i)) {
                            is JSONObject -> {
                                val path = item.optString("path").trim()
                                if (path.isNotBlank()) {
                                    files.add(
                                        ManifestFile(
                                            path = path,
                                            purpose = item.optString("purpose"),
                                            contract = item.optString("contract"),
                                            required = item.optBoolean("required", true),
                                            entry = item.optBoolean("entry", false)
                                        )
                                    )
                                }
                            }
                            is String -> if (item.isNotBlank()) files.add(ManifestFile(item.trim()))
                        }
                    }
                }
                val tasks = mutableListOf<ManifestTask>()
                args.optJSONArray("tasks")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim().ifBlank { "task_${i + 1}" }
                        tasks.add(
                            ManifestTask(
                                id = id,
                                role = item.optString("role", "implementer"),
                                title = item.optString("title"),
                                dependsOn = stringList(item.optJSONArray("depends_on")),
                                files = stringList(item.optJSONArray("files")),
                                status = item.optString("status", "pending")
                            )
                        )
                    }
                }
                if (files.isEmpty()) throw IllegalArgumentException("Manifest يحتاج ملفاً واحداً على الأقل")
                val manifest = BuildManifest(
                    project = project,
                    root = root,
                    entryPoints = entries,
                    files = files,
                    tasks = tasks
                )
                ManifestStore.write(ctx.prefs, ctx.chatId, manifest)
                PipelineStore.write(
                    ctx.prefs,
                    PipelineState(ctx.chatId, project, root, PipelinePhase.MANIFEST)
                )
                buildString {
                    append("✅ أُنشئ Manifest «").append(project).append("» — ")
                    append(files.size).append(" ملف، ").append(tasks.size).append(" مهمة DAG.\n")
                    append("الخطوة التالية: اعرض الملفات/العقود على المستخدم ثم استخدم pipeline_approve بعد موافقته. لا تكتب كوداً قبل ذلك.\n")
                    files.take(60).forEach { file ->
                        append("• ").append(ManifestPaths.join(root, file.path))
                        if (file.purpose.isNotBlank()) append(" — ").append(file.purpose)
                        if (file.required) append(" [إلزامي]")
                        append('\n')
                    }
                }
            }
            "manifest_read" -> {
                val manifest = ManifestStore.read(ctx.prefs, ctx.chatId)
                    ?: return "لا يوجد Manifest لهذه المحادثة. استخدم manifest_create أولاً."
                buildString {
                    append("📋 ").append(manifest.project).append(" | root=").append(manifest.root.ifBlank { "." })
                    append(" | معتمد: ").append(if (manifest.approved) "نعم" else "لا").append('\n')
                    append("نقاط الدخول: ").append(manifest.entryPoints.joinToString("، ").ifBlank { "تلقائي" }).append('\n')
                    manifest.files.forEach { file ->
                        append("- ").append(file.path).append(" [").append(file.status).append(']')
                        if (file.contract.isNotBlank()) append(" | ").append(file.contract)
                        append('\n')
                    }
                    if (manifest.tasks.isNotEmpty()) {
                        append("\nDAG:\n")
                        manifest.tasks.forEach { task ->
                            append("• ").append(task.id).append(" (").append(task.role).append(") ")
                                .append(task.title).append(" ← ").append(task.dependsOn.joinToString("، ").ifBlank { "بداية" })
                                .append(" [").append(task.status).append("]\n")
                        }
                    }
                }
            }
            "manifest_update" -> {
                requireChat(ctx)
                val current = ManifestStore.read(ctx.prefs, ctx.chatId)
                    ?: throw IllegalArgumentException("لا يوجد Manifest. استخدم manifest_create أولاً.")
                val path = ManifestPaths.normalize(req(args, "path"))
                if (current.files.none { ManifestPaths.normalize(it.path) == path }) {
                    throw IllegalArgumentException("المسار ليس ضمن Manifest: $path")
                }
                val status = args.optString("status", "written")
                val updated = current.copy(
                    files = current.files.map { file ->
                        if (ManifestPaths.normalize(file.path) != path) file
                        else file.copy(
                            status = status,
                            purpose = args.optString("purpose", file.purpose),
                            contract = args.optString("contract", file.contract)
                        )
                    },
                    updatedAt = System.currentTimeMillis()
                )
                ManifestStore.write(ctx.prefs, ctx.chatId, updated)
                "✅ حُدّث $path في Manifest → $status"
            }
            "manifest_validate" -> {
                requireChat(ctx)
                val manifest = ManifestStore.read(ctx.prefs, ctx.chatId)
                    ?: return "⛔ لا يوجد Manifest. لا يمكن التحقق."
                // قبل الاعتماد: تحقق بنيوي فقط لأن الملفات تُكتب في مرحلة التنفيذ.
                // بعد الاعتماد: بوابة كاملة تشمل وجود الملفات وتوازن الأقواس وRoot Reachability.
                val check = if (manifest.approved) {
                    val snapshot = WorkspaceSnapshot.of(ctx.context, args.optString("root").ifBlank { manifest.root })
                    ControlPlane.evaluate(
                        snapshot,
                        manifest,
                        ManifestStore.signatures(ctx.prefs, ctx.chatId),
                        ManifestStore.hmacKey(ctx.prefs)
                    )
                } else {
                    ControlPlane.validateManifest(manifest)
                }
                check.text()
            }
            "gate_run" -> {
                requireChat(ctx)
                ManifestStore.gate(ctx.prefs, ctx.chatId, args.optString("root")).text()
            }
            "root_reachability" -> {
                val snapshot = WorkspaceSnapshot.of(ctx.context, args.optString("root"))
                val manifest = ManifestStore.read(ctx.prefs, ctx.chatId)
                val entries = ControlPlane.buildEntryPoints(snapshot, manifest)
                val result = ControlPlane.reachability(snapshot, entries)
                buildString {
                    append("🌳 Root Reachability — مصادر: ").append(result.totalSource)
                    append(" | واصل: ").append(result.reachable.size)
                    append(" | يتيم: ").append(result.orphans.size).append('\n')
                    append("نقاط الدخول: ").append(result.entryPoints.joinToString("، ").ifBlank { "لم أجد نقطة دخول" }).append('\n')
                    if (result.orphans.isEmpty()) append("✅ لا ملفات يتيمة.")
                    else result.orphans.forEach { append("❌ يتيم: ").append(it).append('\n') }
                }
            }
            "gate_sign_tests" -> {
                requireChat(ctx)
                val snapshot = WorkspaceSnapshot.of(ctx.context, args.optString("root"))
                val tests = snapshot.files.keys.filter { ControlPlane.isTest(it) }
                if (tests.isEmpty()) return "⚠️ لا ملفات اختبار في المشروع؛ لا شيء لتوقيعه."
                val signatures = ManifestStore.signTests(ctx.prefs, ctx.chatId, snapshot)
                "✅ وُقّعت ${signatures.size} ملف اختبار بـ HMAC-SHA256. أي تعديل لاحق غير موقّع سيكشفه gate_verify_tests."
            }
            "gate_verify_tests" -> {
                requireChat(ctx)
                val signatures = ManifestStore.signatures(ctx.prefs, ctx.chatId)
                if (signatures.isEmpty()) return "⚠️ لا توجد بصمات اختبار محفوظة. استخدم gate_sign_tests بعد تثبيت الاختبارات."
                val snapshot = WorkspaceSnapshot.of(ctx.context, args.optString("root"))
                val issues = ControlPlane.verifySignatures(snapshot, signatures, ManifestStore.hmacKey(ctx.prefs))
                if (issues.isEmpty()) "✅ سلامة الاختبارات سليمة — لم يُكتشف عبث HMAC."
                else issues.joinToString("\n") { it.lineText() }
            }

            // ===== DAG pipeline =====
            "pipeline_start" -> {
                requireChat(ctx)
                val project = req(args, "project")
                val root = args.optString("root")
                PipelineStore.write(ctx.prefs, PipelineState(ctx.chatId, project, root))
                "✅ بدأت دورة DAG لـ «$project». المرحلة: التخطيط. أنشئ Manifest ثم اعتمده قبل أي كتابة كود."
            }
            "pipeline_status" -> {
                val state = PipelineStore.read(ctx.prefs, ctx.chatId)
                    ?: return "لا دورة تطوير لهذه المحادثة."
                buildString {
                    append("🛠️ ").append(state.project).append(" — المرحلة: ").append(state.phase.label)
                    append(" | Manifest معتمد: ").append(if (state.manifestApproved) "نعم" else "لا")
                    append(" | آخر بوابة: ").append(if (state.lastGateOk) "ناجحة" else "غير مجتازة").append('\n')
                    if (state.note.isNotBlank()) append("ملاحظة: ").append(state.note).append('\n')
                    append("التالي: ").append(PipelinePhase.next(state.phase).label)
                }
            }
            "pipeline_approve" -> {
                requireChat(ctx)
                val state = PipelineStore.read(ctx.prefs, ctx.chatId)
                    ?: throw IllegalArgumentException("لا دورة تطوير. استخدم pipeline_start أولاً.")
                val manifest = ManifestStore.read(ctx.prefs, ctx.chatId)
                    ?: throw IllegalArgumentException("لا Manifest. استخدم manifest_create أولاً.")
                val check = ControlPlane.validateManifest(manifest)
                if (!check.ok) return "⛔ لا يمكن اعتماد Manifest قبل إصلاح أخطائه البنيوية:\n" + check.text()
                ManifestStore.approve(ctx.prefs, ctx.chatId)
                PipelineStore.write(
                    ctx.prefs,
                    state.copy(
                        phase = PipelinePhase.IMPLEMENT,
                        manifestApproved = true,
                        lastGateOk = true,
                        note = "اعتُمد Manifest بعد نجاح البوابة"
                    )
                )
                "✅ اعتُمد Manifest وانتقلنا إلى التنفيذ. اكتب وحدة واحدة فقط ثم شغّل gate_run."
            }
            "pipeline_advance" -> {
                requireChat(ctx)
                val state = PipelineStore.read(ctx.prefs, ctx.chatId)
                    ?: return "لا دورة تطوير لهذه المحادثة."
                val note = args.optString("note")
                when (state.phase) {
                    PipelinePhase.PLAN -> {
                        val next = state.copy(phase = PipelinePhase.MANIFEST, note = note)
                        PipelineStore.write(ctx.prefs, next)
                        "➡️ المرحلة: اعتماد Manifest."
                    }
                    PipelinePhase.MANIFEST -> {
                        val manifest = ManifestStore.read(ctx.prefs, ctx.chatId)
                            ?: return "⛔ لا Manifest بعد."
                        val check = ControlPlane.validateManifest(manifest)
                        if (!check.ok) return "⛔ رفض اعتماد Manifest — أخطاء بنيوية:\n" + check.text()
                        ManifestStore.approve(ctx.prefs, ctx.chatId)
                        PipelineStore.write(
                            ctx.prefs,
                            state.copy(
                                phase = PipelinePhase.IMPLEMENT,
                                manifestApproved = true,
                                lastGateOk = true,
                                note = note
                            )
                        )
                        "✅ Manifest معتمد. ابدأ تنفيذ وحدة واحدة."
                    }
                    PipelinePhase.IMPLEMENT -> {
                        val gate = ManifestStore.gate(ctx.prefs, ctx.chatId, state.root)
                        if (!gate.ok) return "⛔ البوابة رفضت الانتقال — أصلح ثم أعد gate_run:\n" + gate.text()
                        PipelineStore.write(ctx.prefs, state.copy(phase = PipelinePhase.GATE, lastGateOk = true, note = note))
                        "✅ اجتازت البوابة. انتقل للدور: reviewer."
                    }
                    PipelinePhase.GATE -> {
                        PipelineStore.write(ctx.prefs, state.copy(phase = PipelinePhase.REVIEW, note = note))
                        "➡️ المرحلة: المراجعة."
                    }
                    PipelinePhase.REVIEW -> {
                        PipelineStore.write(ctx.prefs, state.copy(phase = PipelinePhase.QA, note = note))
                        "➡️ المرحلة: الاختبار."
                    }
                    PipelinePhase.QA -> {
                        PipelineStore.write(ctx.prefs, state.copy(phase = PipelinePhase.BUILD, note = note))
                        "➡️ المرحلة: البناء."
                    }
                    PipelinePhase.BUILD -> {
                        PipelineStore.write(ctx.prefs, state.copy(phase = PipelinePhase.DONE, note = note))
                        "✅ المرحلة النهائية: التسليم. لا تعلن النجاح إلا إذا نجح البناء/الاختبار فعلياً."
                    }
                    PipelinePhase.DONE -> "الدورة مكتملة بالفعل. استخدم pipeline_reset لمشروع جديد."
                }
            }
            "pipeline_reset" -> {
                PipelineStore.delete(ctx.prefs, ctx.chatId)
                "✅ أُعيد ضبط دورة التطوير."
            }

            // ===== Checkpoints / memory =====
            "checkpoint_save" -> {
                requireChat(ctx)
                val session = AgentSessions.of(ctx.prefs, ctx.chatId)
                session.recordNote(args.optString("note").ifBlank { "checkpoint يدوي" })
                CheckpointStore.save(ctx.prefs, session)
                "✅ حُفظ checkpoint: ${session.stepCount()} خطوة، ${session.touchedFiles().size} ملف."
            }
            "checkpoint_resume" -> {
                requireChat(ctx)
                val session = AgentSessions.of(ctx.prefs, ctx.chatId)
                val block = session.contextBlock()
                if (block.isBlank()) "لا checkpoint محفوظ لهذه المحادثة."
                else "♻️ استؤنفت آخر نقطة:\n$block"
            }
            "checkpoint_list" -> {
                val list = CheckpointStore.list(ctx.prefs)
                if (list.isEmpty()) "لا نقاط استئناف محفوظة."
                else list.joinToString("\n") { info ->
                    "• ${info.chatId} — ${info.task.take(60)} (${info.steps} خطوة، ${info.files} ملف)" +
                        if (info.pending != null) " — سؤال معلّق" else ""
                }
            }
            "checkpoint_clear" -> {
                requireChat(ctx)
                AgentSessions.clear(ctx.prefs, ctx.chatId)
                "✅ حُذفت نقطة الاستئناف."
            }
            "memory_save" -> {
                val entry = ProjectMemory.save(
                    ctx.prefs,
                    args.optString("project"),
                    req(args, "title"),
                    req(args, "body"),
                    stringList(args.optJSONArray("tags"))
                )
                "✅ حُفظ قرار في الذاكرة: ${entry.title} (${entry.id})"
            }
            "memory_search" -> {
                val results = ProjectMemory.search(ctx.prefs, args.optString("query"), int(args, "limit", 10))
                if (results.isEmpty()) "لا نتائج في الذاكرة."
                else results.joinToString("\n\n") { entry ->
                    "🧠 ${entry.title} [${entry.project}] (${entry.id})\n${entry.body.take(1200)}"
                }
            }
            "memory_list" -> {
                val results = ProjectMemory.all(ctx.prefs).take(int(args, "limit", 20))
                if (results.isEmpty()) "الذاكرة فارغة."
                else results.joinToString("\n") { entry ->
                    "• ${entry.title} [${entry.project}] — ${entry.body.take(160)} (${entry.id})"
                }
            }
            "memory_delete" -> if (ProjectMemory.delete(ctx.prefs, req(args, "id"))) "✅ حُذف القرار." else "❌ لم أجد القرار."

            // ===== Shell / Android =====
            "shell_exec" -> {
                val result = ShellTools.exec(
                    ctx.context,
                    req(args, "command"),
                    int(args, "timeout_sec", 120),
                    args.optString("cwd")
                )
                buildString {
                    append(if (result.ok) "✅" else "❌").append(" exit=").append(result.exitCode)
                    append(" (").append(result.elapsedMs).append("ms)")
                    if (result.timedOut) append(" — انتهت المهلة")
                    append('\n').append(result.output)
                }
            }
            "shell_which" -> ShellTools.which(ctx.context, req(args, "name"))
            "android_build" -> {
                val result = ShellTools.buildAndroid(
                    ctx.context,
                    args.optString("root"),
                    args.optString("task", ":app:assembleDebug"),
                    int(args, "timeout_sec", 600)
                )
                (if (result.ok) "✅" else "❌") + " Android build exit=${result.exitCode}\n" + result.output
            }
            "android_install_run" -> {
                val result = ShellTools.installAndRun(
                    ctx.context,
                    req(args, "apk"),
                    args.optString("package_name"),
                    args.optString("activity")
                )
                result.output
            }
            "android_logcat" -> ShellTools.logcat(
                ctx.context,
                args.optString("filter"),
                int(args, "lines", 300)
            ).output

            // ===== MCP =====
            "mcp_servers" -> {
                val list = McpClient.servers(ctx.prefs)
                if (list.isEmpty()) "لا خوادم MCP مضافة. استخدم mcp_add_server (Streamable HTTP) لربط filesystem/shell/git/android-build/session-mesh."
                else list.joinToString("\n") { McpConfig.mask(it) }
            }
            "mcp_add_server" -> {
                val name = req(args, "name")
                val id = args.optString("id").trim().ifBlank {
                    name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(32)
                }
                if (id.isBlank()) throw IllegalArgumentException("معرّف الخادم مطلوب")
                McpClient.addServer(
                    ctx.prefs,
                    McpServer(
                        id = id,
                        name = name,
                        url = req(args, "url"),
                        token = args.optString("token"),
                        enabled = args.optBoolean("enabled", true)
                    )
                )
                "✅ أُضيف خادم MCP $id. استخدم mcp_ping ثم mcp_list_tools."
            }
            "mcp_remove_server" -> if (McpClient.removeServer(ctx.prefs, req(args, "id"))) "✅ حُذف خادم MCP." else "❌ غير موجود."
            "mcp_list_tools" -> {
                val tools = McpClient.listTools(ctx.prefs, req(args, "server"))
                if (tools.length() == 0) "لا أدوات في هذا الخادم."
                else buildString {
                    append("🔗 ").append(tools.length()).append(" أداة:\n")
                    for (i in 0 until tools.length()) {
                        val item = tools.optJSONObject(i) ?: continue
                        append("• ").append(item.optString("name"))
                        val desc = item.optString("description")
                        if (desc.isNotBlank()) append(" — ").append(desc.take(160))
                        append('\n')
                    }
                }
            }
            "mcp_call" -> {
                val toolArgs = when (val raw = args.opt("args")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    else -> JSONObject()
                }
                McpClient.callTool(ctx.prefs, req(args, "server"), req(args, "tool"), toolArgs)
            }
            "mcp_ping" -> McpClient.ping(ctx.prefs, req(args, "server"))

            else -> "❌ أداة غير معروفة: $name"
        }
    }

    private fun requireChat(ctx: ToolContext): String {
        if (ctx.chatId.isBlank()) {
            throw IllegalArgumentException("معرّف المحادثة مطلوب لهذه الأداة")
        }
        return ctx.chatId
    }

    private fun runSummary(run: GitHubClient.WorkflowRun): String =
        "#${run.number} ${run.name} [${run.branch}] — ${run.status}/${run.conclusion}\n${run.htmlUrl}"

    private fun isoEpoch(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun notificationPackages(raw: String): List<String> = when (raw.trim().lowercase()) {
        "whatsapp", "واتساب", "واتس" -> listOf("com.whatsapp", "com.whatsapp.w4b")
        "telegram", "تلغرام", "تيليجرام" ->
            listOf(
                "org.telegram.messenger", "org.telegram.messenger.web",
                "org.telegram.plus", "org.thunderdog.challegram"
            )
        "" -> listOf("")
        else -> listOf(raw.trim())
    }

    private fun telegramUpdateLine(update: JSONObject): String {
        val callback = update.optJSONObject("callback_query")
        if (callback != null) {
            return "• [callback] ${TelegramClient.formatUser(callback.optJSONObject("from"))}: " +
                callback.optString("data").take(200) + " (id=${callback.optString("id")})"
        }
        val message = update.optJSONObject("message")
            ?: update.optJSONObject("edited_message")
            ?: update.optJSONObject("channel_post")
            ?: return "• تحديث ${update.optLong("update_id")}"
        val chat = message.optJSONObject("chat")
        val from = message.optJSONObject("from") ?: chat
        val title = chat?.optString("title").orEmpty().ifBlank { chat?.optString("username").orEmpty() }
            .ifBlank { TelegramClient.formatUser(from) }
        val text = message.optString("text").ifBlank { message.optString("caption") }
            .replace("\n", " ").take(500)
        return "• [$title|${chat?.optLong("id")}] ${TelegramClient.formatUser(from)}: $text " +
            "(msg_id=${message.optLong("message_id")})"
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

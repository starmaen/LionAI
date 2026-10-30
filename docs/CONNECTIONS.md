# دليل الربط والتأمين (Connections & Security)

هذا الدليل يشرح ما هو مربوط فعلاً، وما يحتاج مفتاحاً، وكيف تؤمّن كل قناة. أي أداة غير مربوطة تظهر للوكيل كغير متاحة، ولن يدّعي الوصول إليها.

## GitHub
- الإعداد: `الإعدادات ← أدوات الذكاء والتطوير ← توكن GitHub`.
- النوع: Fine-grained PAT.
- الصلاحيات الدنيا: `Contents: Read/Write`، `Pull requests: Read/Write`، `Actions: Read`، `Metadata: Read`.
- التخزين: `SecurePrefs` (مشفّر محلياً)، ولا يُرسل إلا إلى `api.github.com`.
- أدوات: `github_repo_overview`, `github_list_tree`, `github_read_file`, `github_search_code`, `github_clone`, `github_write_file`, `github_commit_files`, `github_push_workspace`, `github_wait_run`, `github_run_logs`, `github_create_branch`, `github_create_repo`, `github_bootstrap_project`.

## Google Drive
- الإعداد: `الموصلات ← Google Drive ← ربط`.
- يحتاج OAuth Client (Android/Desktop) في Google Cloud + scope `drive.file` (أو `drive` للوصول الكامل).
- أدوات: `drive_search`, `drive_open`, `drive_list`, `drive_read`, `drive_download`, `drive_upload`, `drive_update`, `drive_mkdir`, `drive_move`, `drive_rename`, `drive_copy`, `drive_trash`, `drive_meta`.

## Gmail (البريد الإلكتروني)
- الإعداد: `الموصلات ← Gmail ← ربط`.
- Scopes: `gmail.modify` (قراءة/تسميات) و`gmail.send`.
- أدوات: `gmail_search`, `gmail_read`, `gmail_send`, `gmail_draft`, `gmail_labels`, `gmail_modify`, `gmail_trash`, `gmail_attachment`, `gmail_profile`.
- بديل بلا OAuth: لا يوجد حالياً (SMTP يحتاج مكتبة/منفذ 465، أُجّل عمداً لتفادي أسرار داخل APK).

## Google Calendar
- Scopes: `calendar`.
- أدوات: `calendar_list`, `calendar_calendars`, `calendar_create`, `calendar_update`.

## Firebase (REST بدون SDK)
- ما نحتاجه في الإعدادات: `firebase_project` (Project ID) و`firebase_api_key` (Web API key)، واختياري `firebase_id_token` لمستخدم Auth.
- Firestore REST: `https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents/...`
- Storage REST: `https://storage.googleapis.com/storage/v1/b/{bucket}/o`
- خط الدفاع: قواعد أمان Firestore/Storage. المفتاح العام ليس تصريحاً.
- أدوات مقترحة/مضافة: `firebase_status`, `firestore_get`, `firestore_set`, `firestore_query`, `storage_list`.

## ربط أي موقع/خدمة أخرى
1. `site_probe {url}`: يعيد رمز الحالة، نوع المحتوى، الرؤوس الأمنية، وهل الموقع متاح.
2. `web_api {url, method, body}`: استدعاء بعد الحصول على مفتاح.
3. `web_fetch`: قراءة صفحة عامة (نص + روابط).
4. خزّن المفتاح في موصل/إعداد مشفّر، وقيّده في لوحة الخدمة (IP/Referrer/Scope) إن أمكن.

## قواعد أمنية صارمة
- لا مفاتيح في الكود أو الردود أو `.env` مرفوع: استخدم `secret_scan` قبل الدفع.
- HTTPS فقط للخوادم العامة؛ HTTP محلي مقصور على localhost/192.168/10.x/172.16-31.
- لا تدفع إلى `main` دون طلب صريح؛ استخدم فرعاً وPR.
- أي عملية كتابة/إرسال/حذف تحتاج تأكيداً صريحاً في رسالة المستخدم الحالية.
- التوكنات تُخزَّن مشفّرة محلياً، ولا تُسجَّل في Logcat.

# منهجية العمل الهندسي (Playbook)

هذه الوثيقة هي العقد التشغيلي للوكيل داخل LionAI. تُطبَّق في وضع «هندسة وبرمجة» فقط، وتُختصر في أوضاع الدردشة/التعلّم.

## 1) دورة التنفيذ الإلزامية
1. **استكشاف**: `file_tree` / `github_repo_overview` / `github_list_tree` / `code_analyze_file`. لا تخمّن بنية مشروع.
2. **تخطيط**: `manifest_create` (ملفات + عقود + مهام DAG) ثم `manifest_validate` ثم `pipeline_approve`.
3. **تنفيذ وحدة واحدة**: أقل تعديل كافٍ، عبر `file_apply_patch` أو `github_write_file` أو PR.
4. **تحقق ميكانيكي**: `gate_run` (وجود + بنية + Root Reachability + HMAC للاختبارات) ثم `code_analyze_file` وبناء إن توفر JDK.
5. **تسليم**: `file_diff`/`git diff`، تحديث Manifest، وCheckpoint. لا تعلن الانتهاء قبل بوابة ناجحة.

## 2) GitHub
- ابدأ القراءة عبر `github_repo_overview` ثم شجرة الملفات؛ لا تكلون كامل المستودع إلا عند الحاجة.
- لا تدفع إلى `main` مباشرة: فرع + PR، إلا بطلب صريح.
- قبل الدفع: `github_push_workspace` بـ `dry_run=true`، ثم الدفع، ثم `github_wait_run` وقراءة السجل عند الفشل.
- التوكن المطلوب: Fine-grained PAT بصلاحيات `Contents` (R/W) و`Actions` (R) و`Pull requests` (R/W) للمستودع المطلوب فقط.
- الأسرار لا تدخل الكود: استخدم Secrets في Actions، وملف `.gitignore` يمنع `*.jks`/`keystore.properties`/`.env`.

## 3) Google Drive / Gmail / Calendar
- الربط عبر OAuth من شاشة «الموصلات» (Google Cloud OAuth Client + scopes أدناه). التوكن يُخزَّن مشفّراً محلياً.
- Scopes: Drive (`drive.file` أو `drive`)، Gmail (`gmail.modify`, `gmail.send`)، Calendar (`calendar`).
- إن كان تطبيق OAuth في وضع Testing ستنتهي صلاحية refresh token بعد ~7 أيام؛ انشر التطبيق (In production) للاستمرار.
- قبل أي إرسال/حذف/رفع: تأكيد صريح من المستخدم في الرسالة الحالية.

## 4) Firebase
لا نضمّن Firebase SDK كي لا يتضخم الـAPK؛ نستخدم REST مع مفاتيح المشروع:
- `firebase_project`: Project ID.
- `firebase_api_key`: Web API key (عام، ليس سراً خطيراً لكن يُقيَّد بالاستخدام).
- `firebase_id_token`: اختياري، توكن مستخدم Auth عند الحاجة لقواعد أمان مقيّدة.
- قواعد أمان Firestore/Storage هي خط الدفاع الحقيقي؛ لا تعتمد على إخفاء المفتاح.
- الأدوات: `firebase_status`, `firestore_get`, `firestore_set`, `firestore_query`, `storage_list`.

## 5) ربط أي موقع/خدمة (API Linking)
1. `site_probe` لفحص الاستجابة والرؤوس ومتطلبات المصادقة.
2. `web_api` لاستدعاء الـAPI بعد الحصول على مفتاح/توكن.
3. خزّن المفاتيح في إعدادات الموصلات/الأسرار، لا في ملفات المشروع ولا في سجل المحادثة.
4. تحقق من:`https` + مهلة + تحقق من `content-type` + عدم إعادة توجيه حساسة.

## 6) الأدوات على الجهاز/الخادم
- `tools_catalog` يعرض كل أداة وحالتها (متاح / يحتاج ربط موصل / يحتاج تفعيل كتابة).
- `toolchain_status` يفحص الحزم، و`toolchain_install` يثبت من قائمة بيضاء عبر pkg/apt.
- الأدوات مفعّلة افتراضياً في الأوضاع الثلاثة (يمكن إيقافها من «أدوات الذكاء والتطوير»).

## 7) المكتبات والاعتماديات
- لا نضيف مكتبة إلا بعد فحص `dependency_audit`/`library_audit` وحجم الأثر.
- المفضّل: الأدوات القياسية (HttpURLConnection/JSONObject) لتفادي حجم APK ومشاكل الانعكاس.
- ممنوع: مكتبات تسحب تتبعاً، أو تطلب صلاحيات زائدة، أو تتصل بخوادم غير معروفة.
- إن لزمت إضافة: `dependencies { }` في `app/build.gradle.kts` + تثبيت الإصدار + بناء CI.

## 8) الأمان (إلزامي)
- لا تطبع المفاتيح أو التوكنات في الردود أو السجلات؛ استخدم `secret_scan` قبل أي رفع.
- لا تنفذ أوامر صدفة مدمّرة (`rm -rf /`، `git push --force`، `chmod 777`)؛ `shell_exec` يمنعها.
- الملفات محصورة في مساحة عمل التطبيق؛ لا وصول لجذر الجهاز.
- أي كتابة كود تحتاج Manifest معتمداً (`pipeline_approve`)، وأي تعديل لملفات الاختبار يكشفه HMAC.

## 9) قائمة تحقق قبل التسليم
- [ ] Manifest معتمد ومحدّث.
- [ ] `gate_run` أخضر (ملفات موجودة، بنية سليمة، لا يتيم، اختبارات سليمة).
- [ ] `secret_scan` نظيف.
- [ ] فرق واضح (`file_diff`) ورسالة commit وصفية.
- [ ] CI أخضر أو سبب واضح للفشل وخطة إصلاح.

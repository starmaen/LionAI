# HANDOFF — حالة العمل وتقرير الفحص

آخر تحديث: 2026-11-01 · راجع `git log --oneline -5` لمعرفة أحدث commit.

## ملخص سريع
LionAI أصبح: أوضاع محادثة معزولة (GENERAL / CODE / LEARN) + Control Plane (Manifest + بوابات جودة + توقيع HMAC) + مزود مخصص OpenAI-Compatible (LiteLLM/Ollama/Gateway) + طبقة أدوات موسّعة (SHELL/QUALITY/CONTROL/MEMORY/MCP).

- البناء والاختبارات تُشغَّل عبر GitHub Actions فقط (لا JDK/Android SDK في بيئة العمل).
- Workflow: `.github/workflows/build.yml` → `./gradlew testDebugUnitTest` ثم APK موقّع كـArtifact.
- مستودع: `https://github.com/starmaen/LionAI` — العمل على `main`.

## نتيجة الفحص الأول (بلاغ المستخدم)
المستخدم لصق **رابط التوثيق** `https://docs.litellm.ai` في حقل Base URL، فظهر:
`failed to connect to docs.litellm.ai ... after 10000ms`.
السبب ليس خللاً في الشبكة: المزود المخصص يحتاج **خادمًا شغّالًا** (LiteLLM/Ollama/بوابة)، لا موقع توثيق.

إصلاحات أُضيفت بعد البلاغ:
- `LlmClient.customUrlProblem()`: يرفض روابط التوثيق/المواقع (`docs.litellm.ai`, `github.com`, `/docs`, `.html` …) برسالة عربية تشرح البديل.
- إضافة المخطط تلقائيًا: `192.168.1.5:11434` → `http://.../v1/chat/completions`، و`host.com` → `https://`.
- منع الطلب قبل التحقق في: الإرسال (`ask`)، جلب الموديلات (`listModels`)، وزر «تحديث» في الإعدادات.
- تحذير فوري تحت حقل Base URL أثناء الكتابة، وزر «اختبار الاتصال بالخادم» أصبح بعد حقل الرابط (بدل اختبار المفتاح).
- اختبارات جديدة في `CustomProviderTest` (scheme + رفض روابط التوثيق).

## مضاف في هذه الجولة (Function Calling + Firebase + أمن)
- `LlmClient.askWithTools` + `callOpenAiTools`: أدوات `tools` أصلية لمزود `custom`، مع fallback تلقائي لبروتوكول النص.
- `ToolAgent`: الخادم المحلي يحصل على **كل** مجموعات الأدوات، مع تعليمات function-calling مخصصة.
- مجموعة أدوات `FIREBASE`: `firebase_status`, `firestore_get`, `firestore_query`, `firestore_set`, `storage_list` (REST، بلا SDK).
- `secret_scan` (فحص أسرار قبل الدفع) و`site_probe` (فحص موقع/API قبل الربط).
- إعدادات Firebase في «أدوات الذكاء والتطوير» (Project/API key/ID token/Bucket).
- وثائق: `docs/PLAYBOOK.md` و`docs/CONNECTIONS.md`.
- إصلاح اختبار: أرقام org.json قد تكون BigDecimal → استخدم `is Number`.

## جولة «كل الأدوات في كل الأوضاع»
- `ContextProfiles.of`: `tools_everywhere` (افتراضي true) يمنح العام/التعلّم/البرمجة كل المجموعات، مع ملاحظات بردود مناسبة لكل وضع.
- `LionApp`: إزالة شرط CODE عن `toolGroups`، وتعطيل حوار الترقية عند تفعيل الأدوات الشاملة.
- `LocalAIProxy`: يمرّر system + history + آخر رسالة (كان يُسقط تعليمات الأدوات — سبب عدم استخدامها).
- `LocalAIProxy`: اختيار مزود الخلفية من أدوات المطور (`local_proxy_backend`).
- Qwen: تجربة dashscope الدولي/الصيني تلقائياً في المحادثة وجلب الموديلات.
- `testProvider`: للخوادم يجلب الموديلات ويختار أول موديل ويختبره، ويعرض العدد.
- ShellTools: `which` + `install` (pkg/apt) + قائمة حزم بيضاء؛ `toolchain_status`/`toolchain_install`/`tools_catalog`.
- DevTools: حقول مزود الخلفية + تبديل الأدوات الشاملة + فحص/تثبيت الحزم.

## جولة التشخيص وإصلاح الخادم
- `LocalAIProxy`: قراءة الجسم بالبايت (كانت تُقرأ محارف → قصّ UTF-8 العربي وإفشال الطلبات الكبيرة).
- إضافة `GET /v1/models` و`GET /health` و`selfTest()` لاختبار الخادم من طرف إلى طرف.
- `LlmClient`: سجل تشخيص آخر أخطاء/نجاحات لكل مزود (`diagnostics()`)، وتسجيل النجاح عند الرد.
- LionApp: عند فشل مسار الأدوات يُجرَّب رد عادي بنفس المزود (حتى لا تتوقف المحادثة).
- أدوات المطور: أزرار «اختبار الخادم» و«فحص المزودات» و«تشخيص».

## قائمة الفحص المطلوبة (أرسل النتائج لكل بند)
1. **مزود مخصص حقيقي**: شغّل LiteLLM أو Ollama (حاسوب/Termux) على نفس الشبكة، ثم:
   - Base URL مثل `http://192.168.1.5:4000/v1` (LiteLLM) أو `http://192.168.1.5:11434/v1` (Ollama).
   - المفتاح: اتركه فارغًا للخادم المحلي.
   - اضغط «تحديث قائمة الموديلات» ثم أرسل رسالة قصيرة.
   - المتوقع: قائمة موديلات + رد نصي.
2. **لصق رابط توثيق**: يجب أن تظهر رسالة «هذا رابط توثيق وليس خادمًا» فورًا بدون انتظار 10 ثوانٍ.
3. **الأوضاع**: محادثة General → اطلب «اكتب ملف Main.kt» → المتوقع حوار ترقية للوضع CODE.
4. **وضع CODE**: اطلب كتابة ملف صغير → المتوقع طلب Manifest قبل أي كتابة.
5. **المزودات المجانية** (DeepSeek / Gemini / Qwen): أرسل نص الخطأ الحرفي + اسم الموديل.

## قالب الإبلاغ
```
المزود:
الموديل:
Base URL (إن وُجد):
الخطأ الحرفي:
ما توقّعته:
```

## خطوات تالية مقترحة
1. تشخيص أخطاء DeepSeek/Gemini/Qwen المجانية (مفتاح/حصة/اسم موديل/رابط إقليمي مثل `dashscope-intl.aliyuncs.com`).
2. إضافة «اختبار اتصال» مستقل في بطاقة المخصص يعرض رمز HTTP والمسار النهائي المطبَّع.
3. توثيق وصفة تشغيل Ollama عبر Termux داخل التطبيق نفسه (بدون خادم خارجي).

## ملاحظات تقنية للجلسة القادمة
- `LlmClient.endpoint()` يقرأ `endpointOverrides` المعبأة من `prefs` عبر `reloadOverrides`.
- المخصص متاح في `SmartRouter.available()` بمجرد وجود `endpoint_custom`.
- لا تُستخدم `const val` مع `trimIndent()` (فشل ترجمة سابق).
- بعد أي تعديل: commit + `git push origin main`، ثم تحقق من Actions:
  `curl -s "https://api.github.com/repos/starmaen/LionAI/actions/runs?branch=main&per_page=1"`


## جولة 2026-11-01 (1): إصلاح الواجهة والتوجيه
- **المستودع كان لا يترجم**: 26 خطأً في `InputHub.kt` و`SettingsParts.kt`. أُصلحت، وبعدها
  BUILD SUCCESSFUL + 127 اختبار وحدة. (آخر بناءين على `main` قبلها كانا فاشلين.)
- حُذف زر الأوضاع من الشريط العلوي، ودُمجت الأوضاع الثلاثة في كبسولة `💬` أسفل صندوق الكتابة.
- النوافذ السفلية صارت ديناميكية (سقف 85% من الشاشة + تمرير داخلي) بدل القصّ من الأسفل.
- قنوات تنفيذ مستقلة: `Channel { DIRECT, GATEWAY, LOCAL }` + `Route.via`
  (`RoutePolicy.channelEnabled/channelVariants`, `LlmClient.ask(..., via=)`).
- `CentralBrain.rankRoutes()`: ترتيب المزودات حسب الاتصال ثم الكفاءة ثم زمن الاستجابة،
  واعٍ بوضع المحادثة، مع قياس لكل (مزود + قناة).
- زر الإرسال = زر الإيقاف (ويرسل المداخلة إن وُجد نص).
- `LocalAIProxy.backendProvider` صار يتبع المزود المختار؛ وتوفر البوابة يتبع قناة الخادم الخارجي.

## جولة 2026-11-01 (2): تنظيف المستودع
- الفروع: `main` فقط (فرع الإصلاح حُذف تلقائياً بعد الدمج). وسم واحد `v1.6.1` + إصدار APK.
- فحص أسرار على 777 كائناً في التاريخ بـ10 أنماط: **لا شيء**.
- كود ميت محذوف: `LionInputBar`، `InputAction`، `RoutePolicy.effective*`(4)،
  `LionApp.manualRoute`، `ArenaActivity.getNavBarHeight`، `ContextStore.setDefault`.
- `.gitignore`: صار يغطي `build/` على أي مستوى (كان `/build` فقط فظهر `app/build/` كغير متتبَّع)
  + `worker/.dev.vars` و`gateway/__pycache__` و`.venv` و`.idea/` وأخرى.
- README: قسم «بنية المستودع» + توثيق قنوات التنفيذ وأيقونة الوضع وزر الإيقاف.
- **مؤجَّل بقرار المستخدم**: تصفية التاريخ من 238 ملفاً عالقاً (~1.3MB: مشروع Accounting-Star،
  وحدات ماجيسك، ملفات zip، `hf-space/`) تحتاج force push؛ وحذف `gateway/` و`make-keystore.yml`
  و`HANDOFF.md`؛ وإغلاق Issue #1.

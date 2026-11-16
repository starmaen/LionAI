# HANDOFF — حالة العمل وتقرير الفحص

آخر تحديث: 2026-11-16 · راجع `git log --oneline -5` لمعرفة أحدث commit.

## جولة 2026-11-16 (3): عزل مزودات الويب — إصلاح بلاغ «صفحات الدخول ليست كأي مزود»

**البلاغ:** مزودات الويب (صفحات الدخول: Arena/Gemini/DeepSeek/Claude…) كانت تعمل ممتازاً وتستخدم
أدوات المطور من واجهة التطبيق عبر الجسر، ثم «بعض التعديلات أثرت سلباً» فصارت المهام لا تُنفَّذ عليها
كأي مزود فردي.

**السبب الجذري (موثّق من التاريخ):** commit `96e0b79` («Emoji removal v2 + failover + state glyphs»)
أعاد `FailoverPolicy` وألحق بدائل فشل من مزودات API خلف **هوية الويب المختارة** في `RoutePolicy.appPlan`:
`return listOf(primary) + FailoverPolicy.backupsFor(prefs, kind, primary)`.
النتيجة: عند اختيار صفحة ويب، أي فشل/مهلة/حاجة تسجيل دخول كان يُجيب مزود API بديل بصمت —
فالصفحة لم تعد «مزوداً فردياً» معزولاً، والوكيل/الأدوات يظهران وكأنهما لا يعملان عليها.
`WebRouteIsolationTest` (الذي يقفل هذا العزل) بدأ يفشل، فاستُبعد من CI في `ea86a7b`
(`excludeTestsMatching("*WebRouteIsolationTest*")`) بدل إصلاح الكود.

**الإصلاح (هذا الجانب فقط):**
- `RoutePolicy.appPlan`: فرع الويب عاد معزولاً بالكامل — `listOf(chosen.copy(via = Channel.WEB))`
  بلا أي بدائل. تعليق الكود نفسه («must never turn into an API answer») أصبح محقّقاً.
- `FailoverPolicy.backupsFor`: حماية داخلية — هوية الويب تُعيد `emptyList()` دائماً حتى لو
  أُعيد استدعاؤها مستقبلاً. بدائل الفشل تعمل لمزودات API اليدوية عبر `assistEnabled`/`rescueRoutes`.
- `app/build.gradle.kts`: حُذف استبعاد `WebRouteIsolationTest` من CI — العزل محميّ بالاختبار من جديد،
  مع اختبار إضافي `failoverBackupsNeverFollowAWebIdentityEvenWhenFailoverIsEnabled`.
- `LionChatSettingsCard`: أظهر مفتاح «أدوات المطور عبر الجسر» (`web_agent_tools`) في بطاقة Lion Chat —
  كان مفتاحاً بلا واجهة، وتفعيله شرط تشغيل حلقة الأدوات لصفحات الويب في `LionApp`.

**التحقق:** kotlinc 2.0.21 على كل مصادر app غير الـCompose مقابل android-35.jar: صفر أخطاء في
`RoutePolicy.kt` و`FailoverPolicy.kt`، وصفر أخطاء صياغة في الملفات المعدّلة (أخطاء Compose-only
مرجعية مكتبات غير متاحة محلياً). يجري `testDebugUnitTest` الكامل على GitHub Actions.


## ملخص سريع
LionAI أصبح: أوضاع محادثة معزولة (GENERAL / CODE؛ التعلّم مدمج في CODE) + Control Plane (Manifest + بوابات جودة + توقيع HMAC) + مزود مخصص OpenAI-Compatible (LiteLLM/Ollama/Gateway) + طبقة أدوات موسّعة (SHELL/QUALITY/CONTROL/MEMORY/MCP).

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
  + `worker/.dev.vars` و`.venv` و`.idea/` وأخرى.
- README: قسم «بنية المستودع» + توثيق قنوات التنفيذ وأيقونة الوضع وزر الإيقاف.
- **حُذف `gateway/`**: نسخة Python مكرَّرة من بوابة الويب نفسها. الفحص أثبت أن
  `worker/` (Cloudflare) هو الأشمل: 38 مزوداً مقابل 28، و**لا مزود وحيد** في `gateway/`
  غير موجود في `worker/`، و`gateway/` لا يربطه أي Workflow ولا أي كود في التطبيق
  (الإشارة الوحيدة كانت سطراً في `worker/README.md` صُحِّح). النسخة تبقى في سجلّ Git.
- التحقق بعد الحذف: `:app:compileDebugKotlin` + `:app:testDebugUnitTest` ناجحان،
  127 اختباراً و0 فشل.

- **مؤجَّل بقرار المستخدم**: تصفية التاريخ من 238 ملفاً عالقاً (~1.3MB: مشروع Accounting-Star،
  وحدات ماجيسك، ملفات zip، `hf-space/`) تحتاج force push؛ وحذف `make-keystore.yml`
  (عملية نُفِّذت لمرة واحدة وتحمل كلمة سر الشهادة بداخلها)؛ وإغلاق Issue #1
  (قالب فارغ بلا تفاصيل).

## جولة 2026-11-01 (3): ضبط المتصفح المدمج (دردشات الويب)
المشكلة المُبلَّغة: في دردشات الويب (Arena وGemini وDeepSeek وغيرها) كان **صندوق الكتابة
مخفياً جزئياً تحت أسفل الشاشة**، وجزء صغير من أعلى الصفحة مخفياً تحت شريط الحالة.

السبب الجذري (كان في `ArenaActivity`):
1. الـ WebView يملأ الشاشة `MATCH_PARENT` بلا أي حساب للحواف، ومع `targetSdk = 35`
   أصبح الرسم من الحافة إلى الحافة إجبارياً على أندرويد 15+، فمرّ المحتوى تحت
   شريط التنقل (الأسفل) وشريط الحالة (الأعلى).
2. لا يوجد `windowSoftInputMode` للنشاط → لوحة المفاتيح تدفع المحتوى دون أن يعلم
   الـ WebView فيبقى صندوق الكتابة تحتها.
3. سكربت محقون كان يصفّر `body.style.paddingBottom` و`marginBottom` صراحةً،
   فيهدم هوامش الأمان التي تضعها المواقع بنفسها.

الإصلاح (`WebBrowser.kt` جديد + إعادة كتابة `ArenaActivity` بـ Compose):
- الشاشة كلها داخل `WindowInsets.systemBars ∪ displayCutout` فلا يمرّ أي محتوى تحت System UI.
- مساحة الصفحة وحدها تأخذ `imePadding()` فتتقلّص تلقائياً عند ظهور لوحة المفاتيح،
  ويبقى صندوق الكتابة ظاهراً فوقها (مع `adjustResize` و`enableEdgeToEdge`).
- حُذف السكربت الذي يصفّر الحشو؛ لم يبقَ إلا إخفاء `navigator.webdriver`.
- تحسين التصفح: شريط علوي (رجوع/تقدّم/تحديث أو إيقاف/تبديل سطح المكتب ↔ الجوال/
  فتح في المتصفح/إغلاق) + عنوان الصفحة + شريط تقدّم.
- `onCreateWindow` صار يفتح `target="_blank"` في العرض نفسه بدل تجاهله بصمت.
- الروابط غير القابلة للعرض (mailto/tel/intent/market) تُسلَّم لتطبيق خارجي،
  والتنزيلات تُسلَّم للتطبيق المناسب بدل أن تختفي.
- استعادة حالة الصفحة عند تدوير الشاشة وموت العملية (`saveState`/`restoreState`
  + `android:configChanges`) فلا تضيع المحادثة بالتدوير.
- رسائل خطأ عربية مع زر «إعادة المحاولة» بدل شاشة سوداء فارغة.
- الرجوع بالزر الخلفي يتنقّل داخل الصفحة قبل إغلاق النشاط.

التحقق: `:app:compileDebugKotlin` + `:app:testDebugUnitTest` ناجحان، **131 اختباراً و0 فشل**
(أُضيف `WebBrowserTest` بـ4 اختبارات على المنطق الصرف: الروابط والمضيف والوكيل والخطأ).

## جولة إعادة تنظيم الإعدادات (الاتصال + صندوق المحادثة)
- صندوق المحادثة أعلى الإعدادات: «عام/المطوّر» كما هما، حُذف «ميديا» (موجود في المزودات)، وأُضيف «تلقائي/يدوي» لاختيار المزود (مفتاح `auto_route` نفسه الذي يقرؤه المسار والشريط السفلي): يدوي = مزودك فقط بلا أي تدخل للعقل المركزي، تلقائي = العقل المركزي يختار ولا يبدّل ما دام المزود يعمل.
- تبويب «الاتصال» أُعيد بناؤه: `ServerRegistry` (سجل الخوادم) + `ServersUi` (الواجهة). إضافة/تعديل/حذف خوادم محلية وخارجية (OpenAI-Compatible تقبل `provider/model`)، فحص كل خادم «بدون مزود» و«مع مزود»، واختيار «مباشر / خادم واحد / كل الخوادم»، ومفتاح «المباشر أولاً». أُلغي عرض المزودات داخل الخوادم (المزودات مشتركة).
- `WebGateway.shouldRoute` و`LlmClient` يمرّان الآن على `WebGateway.relays()` (كل خادم مفعّل بالترتيب) بدل خادم واحد. `KEY_MODE=off` ما زال يعني «مباشر بلا خوادم» (توافق مع الشريط السفلي والاختبارات).
- «غرفة العمليات المركزية» صارت قائمة منسدلة داخل تبويب الاتصال أسفل الخوادم.
- اختبارات جديدة: `ServerRegistryTest`.

## جولة 2026-11-14: Lion-1 صار مزوّداً حقيقياً (محرّك على الجهاز، بلا مزوّد خلفي)
**المشكلة:** lion-1 كان واجهة تمرير فقط — يجمع الهوية ثم يرسل النص إلى خادم 44045 (لم يكن التطبيق
يشغّله) أو إلى بوابة worker. أي أنه لم يملك عقلاً ولا قدرة، ويتوقف كلياً إذا غاب المزوّد الخلفي.

**الحل (`LionEngine.kt` + `LionEngineUi.kt` + إعادة كتابة `LionOne.kt`):**
- `llama-server` (llama.cpp) يعمل داخل بيئة Alpine/proot الموجودة أصلاً في التطبيق على
  `127.0.0.1:44045`، بنموذج GGUF في `workspace/models/lion/`. الحزمة تُثبَّت من مستودع Alpine
  الرسمي (`apk add llama-server`، community) فلا NDK ولا مكتبات native جديدة.
- بعد التثبيت: استدلال على الجهاز، بلا إنترنت وبلا مفتاح وبلا بوابة.
- `--jinja` يفعّل tools الأصلية، و`LlmClient.askWithTools` يوجّه `provider == "lion1"` إلى
  `callLionOneTools`، فيستعمل lion-1 أدوات التطبيق بـ function calling ثم يعود للبروتوكول النصي
  تلقائياً إن رفض المسار الأدوات.
- `ToolAgent`: lion-1 في `LOCAL_PROVIDERS` ويأخذ كل مجموعات الأدوات، بسقف تعليمات 12K حرف
  (سياق النموذج المحلي محدود).
- أداة تطبيق جديدة `lion_engine` (SHELL): status/models/install/download/start/stop/test —
  الوكيل يدير محرّكه بنفسه.
- مسارات lion-1 بالترتيب: المحرّك المدمج ← رابط المستخدم ← الخادم المحلي 44045 ← البوابة (احتياطي).
- تنزيل GGUF قابل للاستئناف (`Range` + `.part` + تحقق حجم)، واختيار النموذج/السياق/الخيوط/
  التشغيل التلقائي من بطاقة «🦁 محرّك Lion-1» داخل تبويب البيئة المحلية.
- الاختبارات: `LionEngineRoutesTest` (الكتالوج، توجيه المهام، ترتيب المسارات وتجاوُز الفاشل).
- التوثيق: `docs/LION1_ENGINE_AR.md` (+ تحديث README و`model/README_AR.md`).

**التحقق المحلي في هذه الجولة:** لا JDK/Android SDK في بيئة العمل الكاملة (CI فقط)، لذا تم
typecheck بـ kotlinc على `LionEngine.kt`/`LionOne.kt`/`LionEngineUi.kt` مقابل `android-35.jar`
وjars Compose الحقيقية: **0 أخطاء أمامية**. يلزم تأكيد `:app:testDebugUnitTest` على GitHub Actions.

### ملحق (نفس الجولة): تركيبة تلقائية حسب الجهاز + نموذج Nano
- أُضيف `lion-1-nano` (Qwen2.5 0.5B · Q4_K_M ≈ 380MB) للأجهزة الضعيفة.
- `LionEngine.recommendFor(totalRamMb)` دالة صرفة: ≤3.5GB → nano/4096، 4GB → mini/4096،
  6–8GB → mini/8192، 12GB+ → base/8192. تُطبَّق مرة واحدة (`lion_engine_profile_set`)
  ولا تُلغي اختيار المستخدم اليدوي بعدها.
- `deviceProfile` + `preflight` + `ramWarning`: أرقام فعلية (رام متاحة/مساحة حرة) ورسالة صريحة
  قبل التنزيل والتشغيل، مع قاعدة: حاجة الذاكرة = حجم النموذج + (السياق÷1024×40MB) + 350MB.
- الرام الافتراضية (Memory Fusion مثل 4+3GB) **لا تُحسب** لأنها تخزين بطيء ينهار مع النموذج.
- اختبارات: `recommendationFollowsRealRamNotVirtualRam`، `everyRecommendedModelExistsInCatalog`،
  `memoryRequirementGrowsWithModelAndContext`.

## جولة 2026-11-14 (٢): خط تدريب Lion-1 كامل وقابل للتشغيل
**الطلب:** «أريد تحسين الأسلوب والأدوات» + خط LoRA كامل على Colab.
- `model/data/import_tools.py` (جديد): يقرأ `AiTools.kt` ويولّد `data/tools.json` و
  `data/tools_catalog.json` (236 أداة، مع **وسائط نموذجية حقيقية** من الكود). `--check` يفشل
  إذا كان الكتالوج قديماً. (أُصلحت مطابقة Def: صيغة `"{}"` المفردة والحقول المسماة `connector =`.)
- `data/generate_dataset.py`: قسم `catalog_tools()` + `catalog_followups()` يولّد أمثلة أدوات من
  الكتالوج الحقيقي (اسم الأداة + مفاتيح وسائطها الصحيحة) بدل أسماء مكتوبة يدوياً. الناتج الآن
  182 مثالاً (منها 118 فيها استدعاء أداة) و0 مشكلة في `eval_lion.py data`.
- `train_lora.py`: أُعيد بناؤه ليتكيّف مع إصدار TRL المثبّت (`max_length`/`max_seq_length`،
  `eval_strategy`/`evaluation_strategy`، `processing_class`/`tokenizer`)، + `--dry_run` (12 خطوة)،
  + gradient checkpointing + بذرة ثابتة + رسائل أخطاء عربية.
- `export_gguf.py` (جديد): يجلب llama.cpp ويحوّل HF→GGUF ثم يكمّم (Q4_K_M افتراضياً) ويطبع
  الحجم وSHA-256 وخطوات الاستخدام في التطبيق — بلا أوامر يدوية.
- `Lion1_LoRA_Colab.ipynb` (جديد): 8 خلايا تنفيذية بالترتيب (جلب ← تثبيت ← بيانات ← فحص ←
  تجربة ← تدريب ← دمج ← GGUF ← تنزيل) + تحذيرات overfit والحقوق.
- CI: خطوة «Validate Lion-1 training data» تشغّل `import_tools.py --check` و`eval_lion.py data`
  على كل بناء، فلا تُدمج بيانات قديمة أو صيغة أدوات مكسورة.
- التوثيق: `docs/LION1_TRAINING_AR.md` (دليل كامل + جدول أخطاء) وتحديث `model/README_AR.md`.

### ملحق (٢): دليل Colab بالنقرات
- `docs/LION1_COLAB_STEPBYSTEP_AR.md`: من تنزيل الدفتر إلى تشغيل النموذج في التطبيق، بالنقرات
  والزمن المتوقع والمخرجات الحرفية لكل خلية، وجدول أعطال.
- توضيح صريح: **لا يوجد ملف LoRA يُرفع** — الدفتر يصنعه (الخلية ٥)، ويُدمج (٦) ويُحوَّل GGUF (٨)؛
  يُرفع إلى HuggingFace ملف GGUF النهائي فقط.
- الدفتر: خلية ١ تدعم مستودعاً خاصاً (توكن)، وأُضيفت خلية حفظ على Google Drive كاحتياط
  ضد انقطاع الجلسة، وأُعيد ترقيم الخلايا ١→٩.

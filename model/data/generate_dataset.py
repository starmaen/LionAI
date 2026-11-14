#!/usr/bin/env python3
"""مولّد بيانات Lion-1: هوية + مهام متعددة + مسارات أدوات بصيغة LionAI الحقيقية.

الناتج: data/train.jsonl و data/val.jsonl بصيغة {"messages":[...]} (تقبلها TRL مباشرة).
أضف أمثلتك الخاصة في data/seed_extra.jsonl (سطر JSON لكل مثال بنفس الصيغة).
تشغيل: python data/generate_dataset.py
"""
import json, os, random, re

HERE = os.path.dirname(os.path.abspath(__file__))
random.seed(7)

SYSTEM = (
    "أنت «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria.\n"
    "- أجب بلغة المستخدم، وبدقة وإيجاز، وابدأ بالجواب مباشرة.\n"
    "- إن لم تكن متأكداً قل ذلك ولا تخترع معلومات.\n"
    "- لا تذكر اسم أي مزود أو نموذج خلفي. إن سُئلت عن هويتك فأنت Lion-1 من Star Syria.\n"
    "- في مهام التطوير استدعِ الأدوات بالصيغة: [[TOOL]]{\"name\":\"اسم_الأداة\",\"args\":{...}}[[/TOOL]] "
    "(استدعاء واحد في كل رد، بلا نص خارج JSON)، وتحقق بأداة قبل إعلان الانتهاء."
)

def conv(user, assistant):
    return {"messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": user},
                         {"role": "assistant", "content": assistant}]}

def tool(name, **args):
    return "[[TOOL]]" + json.dumps({"name": name, "args": args}, ensure_ascii=False) + "[[/TOOL]]"

def tool_json(name, args):
    """مثل tool() لكن بوسائط dict جاهزة — بعض الأدوات فيها مفتاح اسمه "name" فيتعارض مع المعامل."""
    return "[[TOOL]]" + json.dumps({"name": name, "args": args}, ensure_ascii=False) + "[[/TOOL]]"

def result(name, text):
    return f"نتائج الأدوات:\n[{name}] {text}"

# ---------- 1) الهوية ----------
IDENT_Q = ["من أنت؟", "ما اسمك؟", "عرّفني بنفسك", "من صنعك؟", "ما هو النموذج الذي تعمل عليه؟",
           "هل أنت ChatGPT؟", "هل أنت Gemini أو Claude؟", "who are you?", "what model are you?", "who made you?"]
IDENT_A_AR = ("أنا «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria. "
              "أساعدك في البرمجة والتعلّم والكتابة والترجمة والتلخيص والتحليل.")
IDENT_A_EN = ("I'm Lion-1 («أسد»), a multi-task AI assistant from Star Syria. "
              "I help with coding, learning, writing, translation, summarizing and analysis.")

def identity():
    out = []
    for q in IDENT_Q:
        en = re.match(r"[A-Za-z]", q) is not None
        out.append(conv(q, IDENT_A_EN if en else IDENT_A_AR))
    return out

# ---------- 2) مهام نصية (إجابات مكتوبة يدوياً وصحيحة) ----------
TASKS = [
 # برمجة
 ("اكتب دالة Kotlin تعكس نصاً",
  "```kotlin\nfun reverse(s: String): String = s.reversed()\n```\nمثال: `reverse(\"أسد\")` تعيد `دسأ`. الدالة تستخدم `reversed()` الجاهزة في المكتبة القياسية."),
 ("اكتب دالة Python تتحقق إن كان العدد أولياً",
  "```python\ndef is_prime(n: int) -> bool:\n    if n < 2:\n        return False\n    i = 2\n    while i * i <= n:\n        if n % i == 0:\n            return False\n        i += 1\n    return True\n```\nنفحص القواسم حتى الجذر التربيعي فقط، فالتعقيد O(√n)."),
 ("كيف أقرأ ملفاً نصياً كاملاً في Kotlin؟",
  "```kotlin\nval text = java.io.File(\"notes.txt\").readText(Charsets.UTF_8)\n```\nللملفات الكبيرة اقرأ سطراً سطراً: `File(\"notes.txt\").useLines { lines -> lines.forEach(::println) }`."),
 ("اكتب طلب HTTP GET في Kotlin بدون مكتبات",
  "```kotlin\nfun httpGet(url: String): String {\n    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection\n    c.connectTimeout = 8000\n    c.readTimeout = 20000\n    return c.inputStream.bufferedReader().use { it.readText() }\n}\n```\nشغّلها من خيط خلفي (`Dispatchers.IO`) وليس من الخيط الرئيسي في أندرويد."),
 ("لماذا يظهر لي NullPointerException في Kotlin؟",
  "غالباً بسبب `!!` على قيمة null، أو متغير Java لم يُهيَّأ، أو `lateinit` استُخدم قبل إسناده.\nالحل: استخدم `?.` و`?:` بدل `!!`، مثل `val n = name?.length ?: 0`، وأرسل لي السطر والتتبع (stack trace) لأحدد السبب بدقة."),
 ("ما الفرق بين val و var؟",
  "`val` مرجع ثابت لا يُعاد إسناده بعد التهيئة، و`var` قابل لإعادة الإسناد.\n```kotlin\nval a = 1   // a = 2 خطأ\nvar b = 1\nb = 2       // مسموح\n```\nفضّل `val` افتراضياً."),
 # تعليم
 ("اشرح لي الـ coroutines في Kotlin للمبتدئين",
  "الـ coroutine عمل خفيف يمكن إيقافه مؤقتاً دون حجز خيط.\n1) `suspend fun` دالة قابلة للإيقاف.\n2) `launch { ... }` يبدأ عملاً دون انتظار نتيجة.\n3) `async { ... }.await()` يعيد نتيجة.\nمثال:\n```kotlin\nscope.launch(Dispatchers.IO) {\n    val data = httpGet(url)\n    withContext(Dispatchers.Main) { show(data) }\n}\n```\nتمرين: اكتب دالة تجلب رابطين معاً بـ `async` وتطبع مجموع طول الردّين."),
 ("اشرح الفرق بين Git branch و commit",
  "الـ commit لقطة محفوظة من ملفاتك مع رسالة. الـ branch مؤشر متحرك يشير إلى آخر commit في خط عمل.\nنفتح branch لنجرّب ميزة دون المساس بـ main، ثم ندمجها بـ PR.\n```bash\ngit switch -c feature/login\ngit commit -am \"add login\"\n```\nتمرين: أنشئ فرعاً جديداً وأجرِ commitين ثم اعرض `git log --oneline`."),
 ("علّمني الاستدعاء الذاتي recursion بمثال",
  "الاستدعاء الذاتي دالة تنادي نفسها مع حالة أبسط حتى تصل لحالة توقف.\n```python\ndef fact(n):\n    if n <= 1:      # حالة التوقف\n        return 1\n    return n * fact(n - 1)\n```\n`fact(4)` ← 4×3×2×1 = 24. تمرين: اكتب دالة تجمع أرقام قائمة بالاستدعاء الذاتي."),
 ("اشرح REST API ببساطة",
  "REST أسلوب تتحدث فيه التطبيقات عبر HTTP بموارد لها روابط:\n- GET لقراءة، POST لإنشاء، PUT/PATCH لتعديل، DELETE لحذف.\n- الاستجابة غالباً JSON، ورموز الحالة مثل 200 نجاح و404 غير موجود و401 غير مصرّح.\nمثال: `GET /v1/models` يعيد قائمة النماذج. تمرين: استدعِ `/v1/models` بـ curl."),
 # كتابة
 ("اكتب رسالة قصيرة أعتذر فيها عن تأخري في تسليم المشروع",
  "السلام عليكم،\nأعتذر عن تأخري في تسليم المشروع، وقد ظهرت مشكلة تقنية أخّرت الإنهاء. سأسلّمه بحلول [التاريخ]، وأبقى على تواصل لأي ملاحظات.\nمع التقدير،\n[الاسم]"),
 ("اكتب وصفاً قصيراً لتطبيق ملاحظات للمتجر",
  "ملاحظاتي: دوّن أفكارك بسرعة ونظّمها في دفاتر ملوّنة. بحث فوري، تذكيرات، ونسخ احتياطي آمن. بسيط وسريع ويعمل دون إنترنت."),
 # تحليل
 ("أيهما أفضل لتطبيقي: SQLite أم Firebase؟",
  "يعتمد على الحاجة:\n- **SQLite**: محلي وسريع ويعمل دون إنترنت ومجاني، لكن المزامنة بين الأجهزة عليك.\n- **Firebase**: مزامنة وصلاحيات جاهزة، لكنه يعتمد على الشبكة والتكلفة تكبر مع الاستخدام.\nالخلاصة: بيانات شخصية محلية ← SQLite؛ حسابات ومزامنة فورية ← Firebase. أخبرني بنوع تطبيقك لأحدد."),
 ("قارن بين JSON وXML باختصار",
  "JSON أخفّ وأسهل قراءة ويناسب الـ APIs الحديثة. XML أثقل لكنه يدعم السمات والمخططات (XSD) ويُستخدم في ملفات الإعداد والمستندات. للويب والموبايل اختر JSON غالباً."),
 # ترجمة
 ("ترجم إلى الإنجليزية: أريد حجز غرفة لشخصين لمدة ثلاث ليال",
  "I would like to book a room for two people for three nights."),
 ("Translate to Arabic: The build failed because of a missing dependency.",
  "فشل البناء بسبب اعتمادية (مكتبة) مفقودة."),
 # تلخيص
 ("لخّص: يعمل التطبيق محلياً على الهاتف ويتصل بعدة مزودات ذكاء اصطناعي، ويحفظ المحادثات على الجهاز، ويشفّر المفاتيح، ويتيح للمستخدم اختيار المزود والنموذج.",
  "تطبيق محلي متعدد المزودات يحفظ المحادثات ويشفّر المفاتيح ويترك اختيار المزود والنموذج للمستخدم."),
 # عامة
 ("صباح الخير", "صباح النور! كيف أساعدك اليوم؟"),
 ("شكراً لك", "العفو! أنا جاهز متى احتجت."),
 ("ما عاصمة سوريا؟", "دمشق."),
]
PREFIX = ["", "", "", "لو سمحت، ", "بسرعة: ", "باختصار: "]

def tasks():
    out = []
    for q, a in TASKS:
        for p in random.sample(PREFIX, 3):
            out.append(conv(p + q, a))
    return out

# ---------- 3) مسارات أدوات (بصيغة LionAI) ----------
REPOS = ["starmaen/LionAI", "starmaen/star-cloud", "starmaen/wolf-browser", "starmaen/demo-app"]
FILES = ["app/src/main/java/com/lion/ai/Main.kt", "src/Utils.kt", "app/build.gradle.kts", "scripts/build.sh"]

def traj(user, steps):
    """steps: قائمة (ردّ_المساعد, نتيجة_الأداة_أو_None) والأخير جواب نهائي."""
    m = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]
    for a, r in steps:
        m.append({"role": "assistant", "content": a})
        if r is not None:
            m.append({"role": "user", "content": r})
    return {"messages": m}

def trajectories():
    out = []
    for repo in REPOS:
        f = random.choice(FILES)
        # أ) استكشاف قبل الإصلاح
        out.append(traj(f"افحص مشروع {repo} وقل لي ما بنيته",
            [(tool("github_repo_overview", repo=repo),
              result("github_repo_overview", f"{repo}: Kotlin, 120 ملفاً، آخر commit قبل يومين")),
             ("المشروع {0} مكتوب بـ Kotlin ويضم نحو 120 ملفاً، وآخر تعديل قبل يومين. هل تريد مراجعة ملف محدد أو فحص البناء؟".format(repo), None)]))
        # ب) قراءة ثم تحليل
        out.append(traj(f"اقرأ الملف {f} وحلله",
            [(tool("file_read", path=f, max_chars=12000), result("file_read", "(محتوى الملف: 85 سطراً)")),
             (tool("code_analyze_file", path=f), result("code_analyze_file", "0 أخطاء، تحذيران: متغير غير مستخدم في السطرين 12 و40")),
             (f"الملف {f} سليم بنيوياً (0 أخطاء). وُجد تحذيران: متغير غير مستخدم في السطرين 12 و40. أقترح حذفهما، هل أنفّذ ذلك؟", None)]))
        # ج) فشل بناء → تشخيص → سجل
        out.append(traj(f"فشل البناء في {repo}، أصلحه",
            [(tool("build_diagnose", repo=repo), result("build_diagnose", "أول خطأ: Unresolved reference: foo في Main.kt:34، job_id=456")),
             (tool("github_job_log", repo=repo, job_id=456, max_chars=12000), result("github_job_log", "e: Main.kt:34 Unresolved reference: foo")),
             (tool("file_search", query="foo", path="", max_results=50), result("file_search", "Main.kt:34 foo(); Helper.kt:3 fun bar()")),
             ("السبب الجذري: استدعاء دالة `foo` غير معرّفة في Main.kt:34، والموجود في Helper.kt هو `bar`. سأصحح الاستدعاء إلى `bar` ثم أتحقق بالبناء.", None)]))
        # د) كتابة آمنة على فرع (لا main مباشرة)
        out.append(traj(f"أضف ملف CHANGELOG.md إلى {repo}",
            [(tool("github_create_branch", repo=repo, branch="docs/changelog", **{"from": "main"}),
              result("github_create_branch", "تم إنشاء الفرع docs/changelog")),
             (tool("github_write_file", repo=repo, path="CHANGELOG.md", content="# Changelog\n\n- أول إصدار\n", message="docs: add changelog", branch="docs/changelog"),
              result("github_write_file", "تم الحفظ")),
             (tool("github_create_pr", repo=repo, title="docs: add changelog", head="docs/changelog", base="main", body="إضافة CHANGELOG.md"),
              result("github_create_pr", "PR #12")),
             (f"أنشأت الفرع docs/changelog وأضفت CHANGELOG.md وفتحت PR #12 على main. لم أدفع إلى main مباشرة. الدمج بانتظار موافقتك.", None)]))
        # هـ) فحص أسرار قبل الدفع
        out.append(traj("افحص المشروع بحثاً عن مفاتيح مسرّبة قبل الدفع",
            [(tool("secret_scan", path=""), result("secret_scan", "وُجد مفتاح محتمل في scripts/build.sh:7 (نمط ghp_)")),
             ("وُجد مفتاح محتمل في scripts/build.sh:7. لا تدفع قبل إزالته واستبداله بسر في GitHub Actions، وبعدها اعتبر المفتاح مكشوفاً فألغِه وأنشئ بديلاً.", None)]))
    # و) طلب بلا أداة: لا نستدعي أداة لسؤال معرفي
    out.append(conv("ما الفرق بين HTTP و HTTPS؟", "HTTPS هو HTTP مع تشفير TLS: يحمي البيانات من التنصت والتعديل أثناء النقل ويتحقق من هوية الخادم بالشهادة. استخدمه دائماً للمفاتيح وكلمات المرور."))
    # ز) سؤال قبل عملية خطرة
    out.append(traj("احذف الفرع main من المستودع",
        [("[[ASK]]ما أنجزته: لم أنفّذ شيئاً.\nما أحتاجه منك: حذف main عملية خطرة وقد يكسر المستودع. هل تقصد فرعاً آخر، أم تؤكد الحذف فعلاً؟[[/ASK]]", None)]))
    return out


# ---------- 4) أمثلة أدوات مولّدة من كتالوج التطبيق الحقيقي ----------
# الهدف: يتعلّم النموذج **اسم الأداة الصحيح ومفاتيح وسائطها** كما هي في AiTools.kt، لا أسماء مخترعة.
# الكتالوج يأتي من data/import_tools.py (يُقرأ من الكود مباشرة)، فإن أضفت أداة في التطبيق
# وأعدت توليد البيانات صار النموذج يعرفها.
CATALOG_PATH = os.path.join(HERE, "tools_catalog.json")

# أدوات أساسية يتكرر استخدامها + صياغات المستخدم التي تستدعيها (٢ لكل أداة على الأقل).
CORE_INTENTS = {
    "file_read": ["اقرأ الملف src/Main.kt وأخبرني ماذا يفعل", "افتح app/build.gradle.kts واعرض محتواه"],
    "read_file": ["اقرأ لي محتوى README.md", "اعرض ملف src/Utils.kt"],
    "file_write": ["اكتب ملف notes.txt فيه سطر ترحيب", "أنشئ ملف src/Config.kt بمحتوى إعدادات بسيط"],
    "file_append": ["أضف سطراً في نهاية CHANGELOG.md", "ألحق ملاحظة بملف notes.txt"],
    "file_search": ["ابحث عن TODO في المشروع", "أين استُخدمت الدالة fetchUser؟"],
    "file_tree": ["اعرض شجرة ملفات المشروع", "كم ملف Kotlin في app/src؟"],
    "file_stat": ["ما حجم مجلد src وعدد ملفاته؟", "أعطني إحصاء ملف app.apk"],
    "file_delete": ["احذف old.txt", "امسح مجلد build المؤقت"],
    "file_present": ["سلّمني ملف dist/app.zip", "أرسل لي مجلد المخرجات كملف مضغوط"],
    "file_diff": ["قارن old.kt وnew.kt", "ما الفرق بين النسختين قبل التعديل وبعده؟"],
    "file_checksum": ["احسب sha256 لملف app.apk", "أعطني بصمة MD5 للنسخة المرفوعة"],
    "file_verify": ["افحص سلامة ملفات المشروع", "هل توجد ملفات فارغة أو مكررة في المشروع؟"],
    "shell_exec": ["نفّذ git status وأخبرني بالنتيجة", "شغّل ./gradlew test وأعطني الخلاصة", "نفّذ ls -la في مساحة العمل"],
    "execute_command": ["نفّذ الأمر git log --oneline -5", "شغّل python --version وتحقق من الإصدار"],
    "code_run": ["شغّل هذا الكود JavaScript وقل لي المخرجات", "نفّذ استعلام SQL على قاعدة app"],
    "shell_which": ["هل git مثبّت على الجهاز؟", "تحقق من وجود node"],
    "toolchain_status": ["ما حالة أدوات التطوير على الجهاز؟", "هل Python وGradle جاهزان؟"],
    "lion_engine": ["ما حالة محرّك Lion-1 المدمج؟", "شغّل المحرّك المدمج واختبره على الجهاز"],
    "toolchain_install": ["ثبّت git على الجهاز", "ثبّت حزمة jq"],
    "android_build": ["ابنِ APK للتطبيق", "شغّل :app:assembleDebug وأخبرني بالنتيجة"],
    "android_logcat": ["اعرض آخر أخطاء logcat", "ما سبب انهيار التطبيق في السجل؟"],
    "web_fetch": ["اجلب صفحة https://example.com واستخرج النص والروابط", "افتح هذا الرابط ولخّص محتواه"],
    "web_search": ["ابحث في الويب عن أحدث إصدار Kotlin", "ابحث عن حل خطأ Gradle: Unsupported class file major version"],
    "web_api": ["استدعِ https://api.github.com/repos/starmaen/LionAI وأعطني stars", "نفّذ طلب POST على هذا الرابط بجسم JSON"],
    "web_download": ["نزّل هذا الملف إلى مساحة العمل", "حمّل صورة من الرابط واحفظها باسم logo.png"],
    "site_probe": ["افحص موقع example.com قبل أن أربطه", "هل هذا الـ API يحتاج مصادقة؟"],
    "github_get_repo": ["اعطني بيانات مستودع starmaen/LionAI", "كم نجمة لمستودع starmaen/LionAI؟"],
    "github_read_file": ["اقرأ README.md من starmaen/LionAI", "اعرض ملف app/build.gradle.kts من المستودع"],
    "github_list_tree": ["اعرض شجرة ملفات starmaen/LionAI", "كم ملف اختبار في المستودع؟"],
    "github_list_commits": ["ما آخر التعديلات في starmaen/LionAI؟", "اعرض آخر 5 commits"],
    "github_write_file": ["عدّل README.md في مستودع starmaen/LionAI وأضف قسماً جديداً", "أنشئ ملف docs/note.md في الفرع docs"],
    "github_create_branch": ["أنشئ فرع feature/login من main", "افتح فرعاً جديداً للتعديل"],
    "github_create_pr": ["افتح Pull Request من feature/login إلى main", "أنشئ PR يشرح التعديلات"],
    "github_list_prs": ["ما الـ Pull Requests المفتوحة؟", "اعرض طلبات الدمج"],
    "github_workflow_runs": ["ما نتيجة آخر عملية بناء على GitHub؟", "هل نجح البناء الأخير؟"],
    "github_job_log": ["اعرض سجل الوظيفة 456 لمعرفة سبب الفشل", "أعطني سجل البناء الكامل"],
    "github_clone": ["نزّل مستودع starmaen/LionAI إلى مساحة العمل", "استنسخ المستودع كاملاً"],
    "code_analyze_file": ["حلّل ملف src/Main.kt", "افحص جودة ملف Kotlin هذا"],
    "code_review": ["راجع المشروع سريعاً واكتشف المخاطر", "أعطني مراجعة كود للمشروع"],
    "code_patch": ["عدّل Main.kt: استبدل foo بـ bar", "طبّق تعديلاً دقيقاً في ملف Utils.kt"],
    "code_doctor": ["أعطني تقرير جاهزية المشروع", "هل المشروع جاهز للإصدار؟"],
    "dependency_audit": ["دقّق مكتبات المشروع عن ثغرات", "هل توجد مكتبات قديمة أو متعارضة؟"],
    "build_diagnose": ["شخّص فشل البناء في starmaen/LionAI", "لماذا فشل آخر بناء؟"],
    "db_query": ["نفّذ استعلاماً على قاعدة app", "اعرض أول 20 صفاً من جدول notes"],
    "db_schema": ["اعرض مخطط قاعدة app", "ما جداول قاعدة البيانات؟"],
    "secret_scan": ["افحص المشروع بحثاً عن مفاتيح مسرّبة", "هل يوجد توكن في الكود قبل الدفع؟"],
    "pdf_read": ["اقرأ صفحات 1-5 من uploads/a.pdf", "لخّص ملف PDF المرفق"],
    "memory_save": ["احفظ قراراً معمارياً: استخدام Compose للواجهة", "سجّل درساً: تجنّب proot مع SELinux"],
    "tools_schema": ["ما مخطط أدوات مجموعة الملفات؟", "أعطني وسائط أدوات GitHub"],
    "calendar_create": ["أنشئ موعداً غداً الساعة 10", "أضف اجتماعاً في التقويم"],
    "gmail_send": ["أرسل بريداً إلى a@b.com بعنوان تجربة", "أرسل رسالة بريد إلكتروني"],
}

# أمثلة تستخدم أسماء أدوات غير موجودة: تُمنع صراحةً كسلوك خاطئ في التقييم، ولا نولّدها.
def load_catalog():
    if not os.path.exists(CATALOG_PATH):
        return {}
    return {t["name"]: t for t in json.load(open(CATALOG_PATH, encoding="utf-8"))}

def catalog_tools():
    """مثال «طلب مستخدم ← استدعاء أداة واحدة بصيغة صحيحة» من الكتالوج الحقيقي."""
    cat = load_catalog()
    if not cat:
        print("⚠️ tools_catalog.json غير موجود — شغّل data/import_tools.py لتحسين أمثلة الأدوات.")
        return []
    out = []
    for name, prompts in CORE_INTENTS.items():
        tool_def = cat.get(name)
        if not tool_def:
            continue  # أداة غير موجودة في هذا الإصدار من التطبيق
        args = tool_def.get("args") or {}
        for p in prompts:
            out.append(conv(p, tool_json(name, args)))
    return out

def catalog_followups():
    """استدعاء ← نتيجة ← جواب عربي نهائي (يتعلّم أن يقرأ نتيجة الأداة ولا يخترعها)."""
    cat = load_catalog()
    if not cat:
        return []
    plans = [
        ("كم نجمة لمستودع starmaen/LionAI؟", "github_get_repo", {"repo": "starmaen/LionAI"},
         "starmaen/LionAI: Kotlin، 41 نجمة، آخر تحديث اليوم",
         "مستودع starmaen/LionAI مكتوب بـ Kotlin ولديه 41 نجمة، وآخر تحديث اليوم."),
        ("هل git مثبّت؟", "shell_which", {"name": "git"},
         "/usr/bin/git", "نعم، git مثبّت في المسار /usr/bin/git."),
        ("ما حالة محرّك Lion-1؟", "lion_engine", {"action": "status"},
         "الحالة: يعمل — الاستدلال على الجهاز · النموذج: lion-1-mini (940MB) · السياق 4096",
         "المحرّك يعمل على جهازك بنموذج lion-1-mini وسياق 4096 رمز، أي أن الاستدلال محلي بلا إنترنت."),
        ("اخلص لي: هل المشروع جاهز؟", "code_doctor", {"path": "", "online": False, "max_files": 3000},
         "البنية: سليمة · الاختبارات: 131 ناجحاً · الاعتماديات: 4 تحذيرات · الحكم: جاهز مع ملاحظات",
         "المشروع جاهز مع ملاحظات: البنية سليمة و131 اختباراً ناجحاً، وهناك 4 تحذيرات في الاعتماديات يُستحسن معالجتها قبل الإصدار."),
    ]
    out = []
    for user, name, args, result_text, final in plans:
        if name not in cat:
            continue
        out.append(traj(user, [(tool_json(name, args), result(name, result_text)), (final, None)]))
    return out

def extra():
    p = os.path.join(HERE, "seed_extra.jsonl")
    if not os.path.exists(p):
        return []
    rows = []
    for ln, line in enumerate(open(p, encoding="utf-8"), 1):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        assert "messages" in d, f"seed_extra.jsonl:{ln} بلا messages"
        rows.append(d)
    return rows

def main():
    rows = identity() + tasks() + trajectories() + catalog_tools() + catalog_followups() + extra()
    seen, uniq = set(), []
    for r in rows:
        k = json.dumps(r, ensure_ascii=False, sort_keys=True)
        if k not in seen:
            seen.add(k); uniq.append(r)
    random.shuffle(uniq)
    cut = max(1, int(len(uniq) * 0.05))
    val, train = uniq[:cut], uniq[cut:]
    for name, data in (("train.jsonl", train), ("val.jsonl", val)):
        with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
            for r in data:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"train={len(train)} val={len(val)}")

if __name__ == "__main__":
    main()

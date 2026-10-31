---
title: LionAI Free Gateway
emoji: 🦁
colorFrom: yellow
colorTo: gray
sdk: docker
app_port: 7860
pinned: false
---

# LionAI Free Gateway

بوابة واحدة بصيغة **OpenAI-Compatible** تجمع مزودات مجانية/شبه مجانية خلف رابط واحد.
التطبيق يتصل بالبوابة فقط، والبوابة تتصل بالمزودات من شبكة الاستضافة المفتوحة — مفيد إذا كانت شبكتك المحلية تحجب بعض المزودات.

- لا اعتماديات: Python القياسي فقط.
- مفاتيح المزودات تبقى على الخادم (Secrets) ولا تدخل التطبيق.
- التطبيق يحتاج قيمة واحدة فقط: `RELAY_TOKEN`.
- صفحة ويب للعرض على المسار `/` تُظهر المزودات المفعلة والموديلات (JSON على `/api/status`).

---

## الخيار الأسرع: Hugging Face مباشرة (بدون بوابة)

إذا كان `huggingface.co` غير محجوب على جهازك:

1. أنشئ توكن **Fine-grained** من https://huggingface.co/settings/tokens/new?ownUserPermissions=inference.serverless.write&tokenType=fineGrained مع تفعيل **Make calls to Inference Providers**.
2. افتح التطبيق ← الإعدادات ← مزود **Hugging Face Inference** ← الصق التوكن.
3. اضغط «تحديث» واختر الموديل:
   `deepseek-ai/DeepSeek-V4.1-Flash-1017`
4. إن لم يظهر، اكتبه يدوياً في حقل الموديل.

> ⚠️ راوتر Hugging Face **لا يوفّر أي موديل مجاني** حالياً (كل الموديلات مدفوعة بالاستخدام)، وحصة الحساب المجانية صغيرة جداً. إن ظهر خطأ 402/403 فالمشكلة رصيد وليست التوكن. للاستخدام المجاني استخدم Groq أو OpenRouter (موديلات `:free`) عبر البوابة أدناه.

---

## الخيار الموصى به عند الحجب: بوابة على Hugging Face Spaces

لديك حساب HF، وهذه أسهل طريقة مجانية:

1. افتح https://huggingface.co/new-space
2. الاسم: `lionai-gateway` — SDK: **Docker** — Hardware: **CPU basic (free)** — Visibility: **Public**.
3. أنشئ الملفات داخل الـ Space (Files → Add file) بنفس محتوى هذا المجلد:
   - `Dockerfile`
   - `server.py`
   - `requirements.txt`
   - `README.md` (هذا الملف، لضبط المنفذ 7860)
4. من **Settings → Variables and secrets** أضف Secrets:
   - `RELAY_TOKEN` = نص عشوائي طويل (مثال: `openssl rand -hex 24`)
   - `HF_TOKEN` = توكن Hugging Face
   - وأي مفاتيح أخرى تريدها (انظر الجدول أدناه)
5. انتظر حتى تصبح الحالة **Running**.
6. رابط البوابة:
   `https://<اسم-المستخدم>-lionai-gateway.hf.space`
7. في التطبيق: الإعدادات ← مزود **مخصص (OpenAI-Compatible)**:
   - **Base URL**: `https://<اسم-المستخدم>-lionai-gateway.hf.space/v1`
   - **المفتاح**: نفس `RELAY_TOKEN`
   - **الموديل**: `groq/openai/gpt-oss-120b` (مجاني) أو `openrouter/deepseek/deepseek-chat-v3-0324:free`
   - اضغط «تحديث» لعرض كل الموديلات المتاحة.

---

## الخيار البديل: Render

1. ارفع مجلد `gateway` إلى مستودع GitHub.
2. https://dashboard.render.com → **New → Blueprint** → اختر المستودع (يقرأ `render.yaml`).
3. أدخل `RELAY_TOKEN` (يُولَّد تلقائياً) ومفاتيح المزودات.
4. الرابط: `https://<name>.onrender.com`
5. في التطبيق استخدم Base URL: `https://<name>.onrender.com/v1`.

> الخطة المجانية في Render تنام بعد خمول؛ أول طلب بعد النوم يستغرق وقتاً أطول.

---

## المزودات المدعومة والمفاتيح

صيغة الموديل دائماً: `<provider>/<model>` — مثال: `groq/openai/gpt-oss-120b`.

| provider | متغير البيئة | ملاحظة |
|---|---|---|
| `hf` | `HF_TOKEN` | Hugging Face Inference Providers |
| `groq` | `GROQ_API_KEY` | طبقة مجانية سخية وسريعة |
| `cerebras` | `CEREBRAS_API_KEY` | طبقة مجانية، سرعة عالية |
| `gemini` | `GEMINI_API_KEY` | Google AI Studio |
| `mistral` | `MISTRAL_API_KEY` | طبقة مجانية |
| `together` | `TOGETHER_API_KEY` | موديلات `-free` |
| `openrouter` | `OPENROUTER_API_KEY` | موديلات `:free` |
| `nvidia` | `NVIDIA_API_KEY` | رصيد مجاني |
| `sambanova` | `SAMBANOVA_API_KEY` | طبقة مجانية |
| `chutes` | `CHUTES_API_KEY` | رخيص/مجاني |
| `nebius` | `NEBIUS_API_KEY` | رصيد مجاني |
| `hyperbolic` | `HYPERBOLIC_API_KEY` | رصيد مجاني |
| `novita` | `NOVITA_API_KEY` | رصيد مجاني |
| `deepinfra` | `DEEPINFRA_API_KEY` | رخيص |
| `scaleway` | `SCALEWAY_API_KEY` | طبقة مجانية |
| `siliconflow` | `SILICONFLOW_API_KEY` | موديلات مجانية |
| `modelscope` | `MODELSCOPE_API_KEY` | حصة مجانية |
| `aimlapi` | `AIMLAPI_KEY` | طبقة مجانية |
| `requesty` | `REQUESTY_API_KEY` | رصيد مجاني |
| `cohere` | `COHERE_API_KEY` | طبقة تجريبية مجانية |
| `fireworks` | `FIREWORKS_API_KEY` | رصيد مجاني |
| `zhipu` | `ZHIPU_API_KEY` | موديلات GLM-Flash مجانية |
| `qwen` | `DASHSCOPE_API_KEY` | حصة مجانية (DashScope الدولي) |
| `moonshot` | `MOONSHOT_API_KEY` | رصيد مجاني (Kimi) |
| `cloudflare` | `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` | 10k Neuron/يوم مجاناً |
| `pollinations` | — | بلا مفتاح |
| `llm7` | — | بلا مفتاح |

أي مزود بدون مفتاح لا يظهر في `/v1/models` إلا إذا كان بلا مفتاح أصلاً.

### روابط الحصول على المفاتيح المجانية

| المزود | الرابط |
|---|---|
| Hugging Face | https://huggingface.co/settings/tokens |
| Groq | https://console.groq.com/keys |
| Cerebras | https://cloud.cerebras.ai |
| Gemini | https://aistudio.google.com/apikey |
| Mistral | https://console.mistral.ai/api-keys |
| Together | https://api.together.xyz/settings/api-keys |
| OpenRouter | https://openrouter.ai/keys |
| NVIDIA | https://build.nvidia.com |
| SambaNova | https://cloud.sambanova.ai/apis |
| Chutes | https://chutes.ai |
| Nebius | https://studio.nebius.ai |
| Hyperbolic | https://app.hyperbolic.xyz |
| Novita | https://novita.ai/settings/key-management |
| DeepInfra | https://deepinfra.com/dash/api_keys |
| Scaleway | https://console.scaleway.com |
| SiliconFlow | https://cloud.siliconflow.cn/account/ak |
| ModelScope | https://modelscope.cn/my/myaccesstoken |
| AI/ML API | https://aimlapi.com/app/keys |
| Requesty | https://app.requesty.ai/api-keys |
| Cohere | https://dashboard.cohere.com/api-keys |
| Fireworks | https://fireworks.ai/account/api-keys |
| Z.ai / GLM | https://open.bigmodel.cn/usercenter/apikeys |
| Qwen / DashScope | https://bailian.console.aliyun.com |
| Moonshot Kimi | https://platform.moonshot.ai/console/api-keys |
| Cloudflare | https://dash.cloudflare.com/profile/api-tokens |

---

## إضافة مزودات ومنصات بلا تعديل الكود

يمكنك إضافة أي منصة متوافقة مع OpenAI بطريقتين:

**1) متغير بيئة `EXTRA_PROVIDERS`** (الأسهل على الاستضافة):

```json
[
  {
    "id": "myplatform",
    "label": "My Platform",
    "baseUrl": "https://api.example.com/v1",
    "models": ["model-a", "model-b"],
    "envKey": "MYPLATFORM_KEY",
    "headers": {"X-Custom": "value"}
  },
  {
    "id": "keylessdemo",
    "label": "Keyless Demo",
    "baseUrl": "https://text.pollinations.ai/openai",
    "models": ["openai"],
    "keyless": true
  }
]
```

ثم أضف `MYPLATFORM_KEY` كـ Secret. سيظهر المزود تلقائياً في `/v1/models`
بصيغة `myplatform/model-a`.

**2) ملف `providers.json`** بجانب `server.py` بنفس الصيغة (مفيد داخل Space).

مثال جاهز: `providers.example.json`.

> `baseUrl` يقبل `https://.../v1` أو `https://.../v1/chat/completions` (تُطبَّع تلقائياً).
> `keyless: true` يعني بلا مفتاح. `headers` اختيارية لأي منصة تحتاج رؤوساً خاصة.

---

## استيراد مزودين داخل التطبيق مباشرة

التطبيق يدعم لصق JSON في:
**الإعدادات ← بطاقة الخادم المحلي ← «➕ استيراد مزود/منصة»**.

الصيغة:

```json
[
  {
    "label": "Lion Gateway",
    "baseUrl": "https://<host>/v1",
    "model": "hf/deepseek-ai/DeepSeek-V4.1-Flash-1017",
    "apiKey": "RELAY_TOKEN"
  }
]
```

بعد الاستيراد اضغط على اسم المزود في «مزودون إضافيون محفوظون» لتفعيله كخادم خلفي.

---

## التشغيل المحلي للاختبار

```bash
cd gateway
RELAY_TOKEN=test PORT=8080 python3 server.py
curl http://127.0.0.1:8080/health
```

---

## الأمان

- لا تشارك `RELAY_TOKEN` ولا ترفعه إلى GitHub.
- البوابة عامة لكنها محمية بالتوكن؛ من يملك التوكن يستهلك مفاتيحك.
- يمكنك تدوير التوكن من إعدادات الاستضافة في أي وقت.
- استخدم Space بحالة Public (الكود فقط ظاهر، والـ Secrets مخفية) أو Private مع تمرير توكن HF — لكن Private يتطلب مصادقة إضافية من التطبيق.

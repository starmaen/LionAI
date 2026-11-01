# LionAI Gateway on Cloudflare Workers

بوابة OpenAI-Compatible مجانية بلا بطاقة ائتمان، تجمع مزودات الذكاء الاصطناعي خلف رابط واحد.

- المسار `/` يعرض لوحة ويب بكل المزودات وحالتها.
- `/api/status` يعيد نفس البيانات بصيغة JSON.
- `/v1/models` و`/v1/chat/completions` متوافقان مع OpenAI.
- المفاتيح تبقى على Cloudflare (Secrets) ولا تدخل التطبيق.

## النشر السريع (لوحة Cloudflare، بلا بطاقة)

1. أنشئ حساباً مجانياً على https://dash.cloudflare.com/sign-up
2. **Workers & Pages → Create → Workers → Get started**.
3. اسم الـ Worker: `lionai-gateway` ثم **Deploy**.
4. **Edit code** → احذف الكود الموجود والصق كامل محتوى [`src/index.js`](src/index.js) → **Deploy**.
5. **Settings → Variables and Secrets** أضف:
   - `RELAY_TOKEN` = نص عشوائي طويل (مفتاح التطبيق)
   - `GROQ_API_KEY` = مفتاح مجاني من https://console.groq.com/keys
   - `GEMINI_API_KEY` = مفتاح مجاني من https://aistudio.google.com/apikey
6. احفظ. الرابط:
   `https://lionai-gateway.<اسم-حسابك>.workers.dev`

## ربط التطبيق

- الإعدادات ← **مخصص (OpenAI-Compatible)**
- Base URL: `https://lionai-gateway.<اسم-حسابك>.workers.dev/v1`
- المفتاح: `RELAY_TOKEN`
- اضغط **تحديث** واختر موديلاً مثل `groq/openai/gpt-oss-120b` أو `gemini/gemini-2.5-flash`

## النشر عبر CLI (اختياري)

```bash
cd worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put RELAY_TOKEN
npx wrangler secret put GROQ_API_KEY
```

## المزودات

نفس قائمة `gateway/README.md` (27 مزوداً) بما فيها بلا مفتاح: `pollinations` و`llm7`.
صيغة الموديل: `<provider>/<model>`.

## إضافة مزود بلا تعديل الكود

أضف Secret باسم `EXTRA_PROVIDERS` يحوي JSON:

```json
[
  {
    "id": "myplatform",
    "label": "My Platform",
    "baseUrl": "https://api.example.com/v1",
    "models": ["model-a", "model-b"],
    "envKey": "MYPLATFORM_KEY"
  }
]
```

ثم أضف `MYPLATFORM_KEY` كـ Secret. سيظهر المزود في `/v1/models` بصيغة `myplatform/model-a`.

## مفاتيح المزودات على Cloudflare

أضفها من: Workers & Pages → `lionai-gateway` → Settings → Variables and Secrets.
مع `keep_vars = true` تبقى محفوظة بعد كل نشر تلقائي من GitHub.

| المزود | اسم السر | ملاحظة |
| --- | --- | --- |
| Gemini | `GEMINI_API_KEY` | مجاني |
| DeepSeek | `DEEPSEEK_API_KEY` | مدفوع رخيص |
| OpenAI | `OPENAI_API_KEY` | مدفوع |
| Anthropic Claude | `ANTHROPIC_API_KEY` | مدفوع (يُترجم تلقائياً لصيغة OpenAI) |
| Groq | `GROQ_API_KEY` | مجاني |
| UnoRouter | `UNOROUTER_API_KEY` | مجاني |
| Hugging Face | `HF_TOKEN` | مجاني |
| Z AI | `ZAI_API_KEY` | مجاني محدود |
| Ollama Cloud | `OLLAMA_API_KEY` | مدفوع |
| Perplexity | `PERPLEXITY_API_KEY` | مدفوع |
| Grok | `GROK_API_KEY` | مدفوع |

مزودات بلا مفتاح تعمل تلقائياً: `cfai` (Workers AI)، `kilo`، `ovh`، `pollinations`، `llm7`.


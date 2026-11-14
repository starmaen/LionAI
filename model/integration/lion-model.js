// Lion-1 على البوابة الخارجية (Cloudflare Worker).
// الاستخدام داخل معالج /v1/chat/completions الحالي قبل التحويل للمزود:
//   import { lionRewrite, LION_MODELS } from "./lion-model.js";
//   body = lionRewrite(body, env);
// وفي /v1/models أضف: data: [...data, ...LION_MODELS]
// env.LION_BASE_MODEL = اسم الموديل الحقيقي خلف البوابة (اختياري، الافتراضي "auto").

const SYSTEM = `أنت «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria.
- أجب بلغة المستخدم، وبدقة وإيجاز، وابدأ بالجواب مباشرة.
- إن لم تكن متأكداً قل ذلك ولا تخترع معلومات.
- لا تذكر اسم أي مزود أو نموذج خلفي. إن سُئلت عن هويتك فأنت Lion-1 من Star Syria.`;

const TASKS = {
  code:      { temp: 0.2, prompt: "المهمة: برمجة. أعطِ كوداً كاملاً يعمل داخل كتل كود، ثم شرحاً قصيراً." },
  teach:     { temp: 0.4, prompt: "المهمة: تعليم. اشرح خطوة بخطوة بمثال عملي ثم اقترح تمريناً صغيراً." },
  write:     { temp: 0.8, prompt: "المهمة: كتابة. اكتب نصاً سليماً ومنظماً يناسب الغرض." },
  reason:    { temp: 0.3, prompt: "المهمة: تحليل. فكّر بخطوات واضحة وقارن الخيارات ثم قدّم خلاصة." },
  translate: { temp: 0.2, prompt: "المهمة: ترجمة. ترجم بدقة وبأسلوب طبيعي دون إضافات." },
  summary:   { temp: 0.3, prompt: "المهمة: تلخيص. لخّص النقاط الجوهرية فقط." },
  general:   { temp: 0.6, prompt: "المهمة: محادثة عامة. كن ودوداً ومباشراً." },
};

const RULES = [
  ["code", /```|كود|برمج|خطأ|error|function|kotlin|python|gradle|bug/i],
  ["teach", /اشرح|علمني|تعلم|explain|teach/i],
  ["translate", /ترجم|translate/i],
  ["summary", /لخص|تلخيص|summar/i],
  ["write", /اكتب|صغ|رسالة|مقال|write|email/i],
  ["reason", /حلل|قارن|لماذا|احسب|analy|why/i],
];

export const LION_MODELS = ["lion-1", ...Object.keys(TASKS).map((t) => `lion-1-${t}`)].map(
  (id) => ({ id, object: "model", owned_by: "star-syria" })
);

export function lionRewrite(body, env = {}) {
  const name = String(body.model || "");
  if (!name.startsWith("lion-1")) return body;

  const forced = name.split("-")[2]; // lion-1-code -> "code"
  const msgs = Array.isArray(body.messages) ? body.messages : [];
  const lastUser = [...msgs].reverse().find((m) => m.role === "user");
  const text = typeof lastUser?.content === "string" ? lastUser.content : "";

  const key = TASKS[forced] ? forced : (RULES.find(([, re]) => re.test(text)) || ["general"])[0];
  const task = TASKS[key];

  return {
    ...body,
    model: env.LION_BASE_MODEL || "auto",
    temperature: body.temperature ?? task.temp,
    messages: [
      { role: "system", content: `${SYSTEM}\n\n${task.prompt}` },
      ...msgs.filter((m) => m.role !== "system"),
    ],
  };
}

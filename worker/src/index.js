/**
 * LionAI Free Gateway — Cloudflare Worker
 * --------------------------------------
 * بوابة OpenAI-Compatible تجمع مزودات مجانية خلف رابط واحد، تعمل على
 * Cloudflare Workers (خطة Free، بلا بطاقة ائتمان).
 *
 * المسارات:
 *   GET  /                    لوحة ويب لعرض المزودات
 *   GET  /api/status          حالة المزودات (JSON)
 *   GET  /health              فحص الصحة
 *   GET  /v1/models           قائمة الموديلات بصيغة provider/model
 *   POST /v1/chat/completions
 *
 * الأسرار (Settings → Variables and secrets):
 *   RELAY_TOKEN, GROQ_API_KEY, GEMINI_API_KEY, OPENROUTER_API_KEY, ...
 *   EXTRA_PROVIDERS (JSON اختياري), RELAY_DEFAULT_MODEL (اختياري)
 */

const HTML_PAGE = String.raw`<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LionAI Gateway — مزودات الذكاء الاصطناعي</title>
<style>
  :root{
    --bg:#0b0f14;--card:#131a23;--card2:#1a2431;--line:#243040;
    --txt:#e8eef7;--muted:#8fa3b8;--accent:#f5b301;--ok:#2ecc71;--off:#5b6b7d;
  }
  *{box-sizing:border-box}
  body{margin:0;background:radial-gradient(1200px 600px at 80% -10%,#1b2a3d 0%,var(--bg) 55%);
    color:var(--txt);font-family:system-ui,"Segoe UI",Tahoma,Arial,sans-serif;line-height:1.6}
  .wrap{max-width:1080px;margin:0 auto;padding:28px 18px 60px}
  header{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:22px}
  .logo{font-size:38px;filter:drop-shadow(0 4px 10px rgba(245,179,1,.25))}
  h1{margin:0;font-size:26px}
  .sub{color:var(--muted);font-size:14px;margin-top:2px}
  .badge{display:inline-flex;align-items:center;gap:6px;padding:4px 12px;border-radius:999px;
    font-size:12.5px;font-weight:600;border:1px solid var(--line);background:var(--card2)}
  .badge .dot{width:8px;height:8px;border-radius:50%;background:var(--off)}
  .badge.on .dot{background:var(--ok);box-shadow:0 0 8px var(--ok)}
  .hero{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:24px}
  .stat{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px}
  .stat .k{color:var(--muted);font-size:12.5px}
  .stat .v{font-size:22px;font-weight:700;margin-top:4px;word-break:break-all}
  .endpoint{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin-bottom:26px}
  .endpoint .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  code{background:#0a0e13;border:1px solid var(--line);border-radius:10px;padding:8px 12px;
    display:inline-block;font-size:13.5px;direction:ltr;color:#cfe4ff;max-width:100%;overflow:auto}
  button{cursor:pointer;background:var(--accent);color:#1a1200;border:0;border-radius:10px;
    padding:9px 16px;font-weight:700;font-size:13.5px}
  button:active{transform:translateY(1px)}
  .hint{color:var(--muted);font-size:12.5px;margin-top:10px}
  h2{font-size:17px;margin:0 0 14px;display:flex;align-items:center;gap:8px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}
  .card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;
    position:relative;overflow:hidden;transition:.2s}
  .card::before{content:"";position:absolute;inset-inline-start:0;top:0;bottom:0;width:4px;background:var(--off)}
  .card.active::before{background:var(--ok)}
  .card:hover{border-color:#33465e;transform:translateY(-2px)}
  .card .top{display:flex;justify-content:space-between;align-items:flex-start;gap:8px}
  .card h3{margin:0;font-size:15.5px}
  .card .id{color:var(--muted);font-size:12px;direction:ltr;text-align:right;margin-top:2px}
  .tag{font-size:11.5px;padding:2px 9px;border-radius:999px;border:1px solid var(--line);white-space:nowrap}
  .tag.ok{color:var(--ok);border-color:rgba(46,204,113,.4);background:rgba(46,204,113,.08)}
  .tag.no{color:var(--muted)}
  .tag.free{color:var(--accent);border-color:rgba(245,179,1,.35);background:rgba(245,179,1,.08)}
  .models{margin-top:12px;display:flex;flex-wrap:wrap;gap:6px}
  .chip{font-size:11.5px;background:#0a0e13;border:1px solid var(--line);border-radius:8px;
    padding:3px 8px;direction:ltr;color:#9fb6cf}
  .models-more{font-size:11.5px;color:var(--muted);align-self:center}
  footer{margin-top:34px;color:var(--muted);font-size:12.5px;text-align:center}
  a{color:var(--accent);text-decoration:none}
  .loading{color:var(--muted);text-align:center;padding:30px}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">🦁</div>
    <div>
      <h1>LionAI Gateway</h1>
      <div class="sub">بوابة واحدة بصيغة OpenAI-Compatible تجمع مزودات الذكاء الاصطناعي خلف رابط واحد</div>
    </div>
    <span class="badge" id="statusBadge"><span class="dot"></span><span id="statusText">جارٍ التحميل…</span></span>
  </header>

  <div class="hero">
    <div class="stat"><div class="k">المزودون المفعّلون</div><div class="v" id="activeCount">—</div></div>
    <div class="stat"><div class="k">إجمالي المزودات المدعومة</div><div class="v" id="totalCount">—</div></div>
    <div class="stat"><div class="k">حماية التوكن</div><div class="v" id="authState">—</div></div>
    <div class="stat"><div class="k">الموديل الافتراضي</div><div class="v" id="defaultModel" style="font-size:14px">—</div></div>
  </div>

  <div class="endpoint">
    <h2>🔌 نقطة الاتصال (Base URL)</h2>
    <div class="row">
      <code id="baseUrl">—</code>
      <button onclick="copyBase()">نسخ</button>
    </div>
    <div class="hint">
      في التطبيق: الإعدادات ← مخصص (OpenAI-Compatible) ← الصق الرابط أعلاه مع إضافة <code style="display:inline;padding:1px 6px">/v1</code>،
      والمفتاح = <b>RELAY_TOKEN</b>، ثم اضغط «تحديث» لاختيار الموديل بصيغة <code style="display:inline;padding:1px 6px">provider/model</code>.
    </div>
  </div>

  <h2>🧠 المزودات المتاحة</h2>
  <div class="grid" id="grid"><div class="loading">جارٍ جلب حالة المزودات…</div></div>

  <footer>
    LionAI · <a href="/v1/models">قائمة الموديلات (تتطلب التوكن)</a> · <a href="/health">فحص الصحة</a>
  </footer>
</div>
<script>
async function copyBase(){
  try{ await navigator.clipboard.writeText(location.origin + "/v1"); }
  catch(e){}
}
function el(tag, cls, text){const e=document.createElement(tag); if(cls)e.className=cls; if(text!=null)e.textContent=text; return e;}
async function load(){
  const badge=document.getElementById('statusBadge');
  try{
    const r=await fetch('/api/status',{cache:'no-store'});
    const d=await r.json();
    badge.classList.add('on');
    document.getElementById('statusText').textContent='متصل';
    document.getElementById('activeCount').textContent=d.counts.active;
    document.getElementById('totalCount').textContent=d.counts.total;
    document.getElementById('authState').textContent=d.auth_required?'مفعّلة 🔒':'مفتوحة ⚠️';
    document.getElementById('defaultModel').textContent=d.default_model||'تلقائي';
    document.getElementById('baseUrl').textContent=location.origin+'/v1';
    const grid=document.getElementById('grid'); grid.innerHTML='';
    const sorted=[...d.providers].sort((a,b)=>(b.configured-a.configured)||a.id.localeCompare(b.id));
    for(const p of sorted){
      const c=el('div','card'+(p.configured?' active':''));
      const top=el('div','top');
      const left=el('div');
      left.appendChild(el('h3',null,p.label));
      left.appendChild(el('div','id',p.id+'/…'));
      top.appendChild(left);
      const tag=el('span','tag '+(p.configured?'ok':'no'),p.configured?'مفعّل':'غير مفعّل');
      top.appendChild(tag);
      c.appendChild(top);
      const ms=el('div','models');
      (p.models||[]).slice(0,6).forEach(m=>ms.appendChild(el('span','chip',m)));
      if(p.keyless) ms.appendChild(el('span','tag free','بلا مفتاح'));
      c.appendChild(ms);
      grid.appendChild(c);
    }
  }catch(e){
    document.getElementById('statusText').textContent='تعذّر الاتصال';
    document.getElementById('grid').innerHTML='<div class="loading">تعذّر جلب البيانات من الخادم.</div>';
  }
}
load();
</script>
</body>
</html>`;

const PROVIDERS = {
  groq: ["GROQ_API_KEY", "https://api.groq.com/openai/v1",
    ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "qwen/qwen3-32b"], "Groq (free tier)"],
  cerebras: ["CEREBRAS_API_KEY", "https://api.cerebras.ai/v1",
    ["gpt-oss-120b", "llama-3.3-70b", "qwen-3-32b"], "Cerebras (free tier)"],
  gemini: ["GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai",
    ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"], "Google Gemini (free tier)"],
  hf: ["HF_TOKEN", "https://router.huggingface.co/v1",
    ["deepseek-ai/DeepSeek-V4.1-Flash-1017", "meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen3-235B-A22B-Instruct-2507"], "Hugging Face Inference Providers"],
  mistral: ["MISTRAL_API_KEY", "https://api.mistral.ai/v1",
    ["mistral-large-latest", "mistral-medium-latest", "mistral-small-latest"], "Mistral (free tier)"],
  together: ["TOGETHER_API_KEY", "https://api.together.xyz/v1",
    ["meta-llama/Llama-3.3-70B-Instruct-Turbo-Free", "deepseek-ai/DeepSeek-R1-Distill-Llama-70B-free"], "Together (free models)"],
  openrouter: ["OPENROUTER_API_KEY", "https://openrouter.ai/api/v1",
    ["nvidia/nemotron-3-ultra-550b-a55b:free", "thinkingmachines/inkling:free", "google/gemma-4-31b-it:free"], "OpenRouter (free models)"],
  nvidia: ["NVIDIA_API_KEY", "https://integrate.api.nvidia.com/v1",
    ["openai/gpt-oss-120b", "meta/llama-3.3-70b-instruct"], "NVIDIA NIM (free credits)"],
  sambanova: ["SAMBANOVA_API_KEY", "https://api.sambanova.ai/v1",
    ["DeepSeek-R1", "Meta-Llama-3.3-70B-Instruct", "Llama-4-Maverick-17B-128E-Instruct"], "SambaNova (free tier)"],
  chutes: ["CHUTES_API_KEY", "https://llm.chutes.ai/v1",
    ["deepseek-ai/DeepSeek-V3-0324", "Qwen/Qwen3-235B-A22B"], "Chutes (cheap/free)"],
  nebius: ["NEBIUS_API_KEY", "https://api.studio.nebius.ai/v1",
    ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"], "Nebius AI Studio (free credits)"],
  hyperbolic: ["HYPERBOLIC_API_KEY", "https://api.hyperbolic.xyz/v1",
    ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"], "Hyperbolic (free credits)"],
  novita: ["NOVITA_API_KEY", "https://api.novita.ai/v3/openai",
    ["deepseek/deepseek-v3-0324", "meta-llama/llama-3.3-70b-instruct"], "Novita AI (free credits)"],
  deepinfra: ["DEEPINFRA_API_KEY", "https://api.deepinfra.com/v1/openai",
    ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"], "DeepInfra (cheap)"],
  scaleway: ["SCALEWAY_API_KEY", "https://api.scaleway.ai/v1",
    ["llama-3.3-70b-instruct", "qwen2.5-coder-32b-instruct"], "Scaleway (free tier)"],
  siliconflow: ["SILICONFLOW_API_KEY", "https://api.siliconflow.cn/v1",
    ["deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"], "SiliconFlow (free models)"],
  modelscope: ["MODELSCOPE_API_KEY", "https://api-inference.modelscope.cn/v1",
    ["deepseek-ai/DeepSeek-V3.2", "Qwen/Qwen3-235B-A22B-Instruct-2507"], "ModelScope (free quota)"],
  aimlapi: ["AIMLAPI_KEY", "https://api.aimlapi.com/v1",
    ["gpt-4o-mini", "deepseek-chat"], "AI/ML API (free tier)"],
  requesty: ["REQUESTY_API_KEY", "https://router.requesty.ai/v1",
    ["openai/gpt-4o-mini", "deepseek/deepseek-chat"], "Requesty (free credits)"],
  cohere: ["COHERE_API_KEY", "https://api.cohere.ai/compatibility/v1",
    ["command-a-03-2025", "command-r-plus-08-2024", "command-r-08-2024"], "Cohere (trial/free)"],
  fireworks: ["FIREWORKS_API_KEY", "https://api.fireworks.ai/inference/v1",
    ["accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/deepseek-v3", "accounts/fireworks/models/qwen3-235b-a22b"], "Fireworks (free credits)"],
  zhipu: ["ZHIPU_API_KEY", "https://open.bigmodel.cn/api/paas/v4",
    ["glm-4-flash", "glm-4.5-flash", "glm-4-flashx"], "Z.ai / GLM (free models)"],
  qwen: ["DASHSCOPE_API_KEY", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    ["qwen-turbo", "qwen-plus", "qwen-max", "qwen3-coder-plus"], "Qwen / DashScope (free quota)"],
  moonshot: ["MOONSHOT_API_KEY", "https://api.moonshot.ai/v1",
    ["moonshot-v1-8k", "moonshot-v1-32k", "kimi-k2-0711-preview"], "Moonshot Kimi (free credits)"],
  cloudflare: ["CLOUDFLARE_API_TOKEN", "",
    ["@cf/openai/gpt-oss-120b", "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/qwen/qwen2.5-coder-32b-instruct"], "Cloudflare Workers AI (free)"],
  cfai: ["", "",
    ["@cf/meta/llama-3.1-8b-instruct", "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/openai/gpt-oss-120b", "@cf/qwen/qwen2.5-coder-32b-instruct"], "Cloudflare Workers AI (binding, keyless)"],
  pollinations: ["", "https://text.pollinations.ai/openai",
    ["openai", "mistral"], "Pollinations (keyless)"],
  llm7: ["", "https://api.llm7.io/v1",
    ["default", "fast"], "LLM7 (keyless)"]
};

const PROVIDER_HEADERS = {};
const MODELS_TTL = 600;
let modelsCache = {};

function trim(v) { return (v || "").trim(); }

function providerBase(pid, spec, env) {
  if (pid === "cloudflare") {
    const acc = trim(env.CLOUDFLARE_ACCOUNT_ID);
    if (!acc) return "";
    return "https://api.cloudflare.com/client/v4/accounts/" + acc + "/ai/v1";
  }
  return String(spec[1] || "").replace(/\/+$/, "");
}

function loadExtras(env) {
  const raw = trim(env.EXTRA_PROVIDERS);
  if (!raw) return;
  let data;
  try { data = JSON.parse(raw); } catch (e) { console.log("WARNING: invalid EXTRA_PROVIDERS JSON"); return; }
  const items = Array.isArray(data) ? data : (data && data.providers) || [];
  if (!Array.isArray(items)) return;
  for (const item of items) {
    if (!item || typeof item !== "object") continue;
    const pid = trim(String(item.id || "")).toLowerCase();
    let base = trim(String(item.baseUrl || item.base_url || item.endpoint || "")).replace(/\/+$/, "");
    if (base.endsWith("/chat/completions")) base = base.slice(0, -"/chat/completions".length);
    if (!/^[a-z0-9_.-]{1,32}$/.test(pid) || !base) continue;
    const keyless = !!item.keyless;
    const envKey = keyless ? "" : trim(String(item.envKey || item.env_key || ""));
    let models = item.models || [];
    if (typeof models === "string") models = [models];
    models = models.map((m) => trim(String(m))).filter(Boolean);
    const single = trim(String(item.model || ""));
    if (single && !models.includes(single)) models.unshift(single);
    if (!models.length) continue;
    PROVIDERS[pid] = [envKey, base, models, trim(String(item.label || item.name || pid))];
    if (item.headers && typeof item.headers === "object") {
      PROVIDER_HEADERS[pid] = item.headers;
    }
  }
}

function configured(env) {
  loadExtras(env);
  const out = {};
  for (const pid of Object.keys(PROVIDERS)) {
    const spec = PROVIDERS[pid];
    if (spec[0] !== "" && !trim(env[spec[0]])) continue;
    if (pid === "cloudflare" && !providerBase(pid, spec, env)) continue;
    if (pid === "cfai" && !env.AI) continue;
    out[pid] = spec;
  }
  return out;
}

function authOk(request, env) {
  const token = trim(env.RELAY_TOKEN);
  if (!token) return true;
  const h = request.headers.get("Authorization") || "";
  let got = h.toLowerCase().startsWith("bearer ") ? h.slice(7).trim() : trim(request.headers.get("X-Api-Key"));
  return got === token;
}

function jsonResponse(obj, status, cors) {
  const body = JSON.stringify(obj);
  return new Response(body, {
    status: status || 200,
    headers: Object.assign({ "Content-Type": "application/json; charset=utf-8" }, cors)
  });
}

function errorResponse(status, message, type, cors) {
  return jsonResponse({ error: { message: message, type: type || "invalid_request_error", code: status } }, status, cors);
}

function statusPayload(env) {
  const conf = configured(env);
  const providers = Object.keys(PROVIDERS).sort().map((pid) => {
    const spec = PROVIDERS[pid];
    return {
      id: pid,
      label: spec[3],
      base_url: providerBase(pid, spec, env),
      configured: Object.prototype.hasOwnProperty.call(conf, pid),
      keyless: spec[0] === "",
      env_key: spec[0] || null,
      models: spec[2].slice(0, 8)
    };
  });
  return {
    name: "LionAI Free Gateway",
    status: "ok",
    runtime: "cloudflare-worker",
    auth_required: !!trim(env.RELAY_TOKEN),
    default_model: trim(env.RELAY_DEFAULT_MODEL) || null,
    timeout: Number(env.UPSTREAM_TIMEOUT || 120),
    counts: { total: providers.length, active: Object.keys(conf).length },
    providers: providers
  };
}

async function fetchJson(url, key, extraHeaders, timeoutMs) {
  const headers = { Accept: "application/json" };
  if (key) headers.Authorization = "Bearer " + key;
  if (extraHeaders) Object.assign(headers, extraHeaders);
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs || 8000);
  try {
    const resp = await fetch(url, { headers: headers, signal: ctrl.signal });
    const text = await resp.text();
    let data = {};
    try { data = JSON.parse(text); } catch (e) { data = {}; }
    return { status: resp.status, data: data };
  } finally {
    clearTimeout(timer);
  }
}

async function upstreamModels(pid, spec, env) {
  const key = spec[0] ? trim(env[spec[0]]) : "";
  const base = providerBase(pid, spec, env);
  if (!base) return spec[2].slice();
  try {
    const r = await fetchJson(base + "/models", key, PROVIDER_HEADERS[pid], 8000);
    const items = (r.data && r.data.data) || [];
    const ids = items.map((m) => trim(String((m && m.id) || ""))).filter(Boolean);
    if (ids.length) return ids.slice(0, 200);
  } catch (e) { /* fall back to defaults */ }
  return spec[2].slice();
}

async function modelsFor(pid, spec, env) {
  const now = Date.now() / 1000;
  const hit = modelsCache[pid];
  if (hit && now - hit.t < MODELS_TTL) return hit.ids;
  const ids = await upstreamModels(pid, spec, env);
  modelsCache[pid] = { t: now, ids: ids };
  return ids;
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let i = 0;
  const workers = new Array(Math.min(limit, items.length)).fill(0).map(async () => {
    while (i < items.length) {
      const idx = i++;
      out[idx] = await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
  return out;
}

async function allModels(env) {
  const conf = configured(env);
  const pids = Object.keys(conf);
  const lists = await mapLimit(pids, 4, (pid) => modelsFor(pid, conf[pid], env));
  const out = [];
  pids.forEach((pid, idx) => {
    for (const mid of lists[idx] || []) {
      out.push({ id: pid + "/" + mid, object: "model", owned_by: pid, created: 0 });
    }
  });
  return out;
}

function resolveModel(model, conf, env) {
  model = trim(model);
  if (!model || model === "auto") {
    const def = trim(env.RELAY_DEFAULT_MODEL);
    if (def) model = def;
    else {
      const pid = Object.keys(conf)[0];
      return pid ? { pid: pid, model: conf[pid][2][0], spec: conf[pid] } : null;
    }
  }
  const slash = model.indexOf("/");
  if (slash > 0) {
    const pid = model.slice(0, slash);
    const rest = model.slice(slash + 1);
    if (Object.prototype.hasOwnProperty.call(conf, pid) && rest) {
      return { pid: pid, model: rest, spec: conf[pid] };
    }
  }
  for (const pid of Object.keys(conf)) {
    if (conf[pid][2].includes(model)) return { pid: pid, model: model, spec: conf[pid] };
  }
  return null;
}

const ALLOWED_FIELDS = [
  "messages", "temperature", "top_p", "max_tokens", "max_completion_tokens",
  "stop", "presence_penalty", "frequency_penalty", "seed", "response_format",
  "tools", "tool_choice", "user", "n", "logprobs", "top_logprobs"
];

async function handleCfAi(payload, model, env) {
  if (!env.AI) return errorResponse(400, "Workers AI binding is not available", "invalid_request_error", CORS);
  const input = { messages: payload.messages };
  for (const k of ["max_tokens", "temperature", "top_p", "seed", "tools"]) {
    if (Object.prototype.hasOwnProperty.call(payload, k)) input[k] = payload[k];
  }
  let res;
  try {
    res = await env.AI.run(model, input);
  } catch (e) {
    return errorResponse(502, "Workers AI " + model + " failed: " + (e && e.message ? e.message : "error"), "upstream_error", CORS);
  }
  if (res && Array.isArray(res.choices)) {
    if (!res.model) res.model = model;
    if (!res.object) res.object = "chat.completion";
    return jsonResponse(res, 200, CORS);
  }
  const text = typeof res === "string" ? res : (res && (res.response || res.result || res.text || "")) || "";
  return jsonResponse({
    id: "chatcmpl-cfai-" + Date.now(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: model,
    choices: [{ index: 0, message: { role: "assistant", content: String(text) }, finish_reason: "stop" }],
    usage: (res && res.usage) || { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }
  }, 200, CORS);
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Api-Key",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS"
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (request.method === "GET" && path === "/") {
      return new Response(HTML_PAGE, {
        status: 200,
        headers: Object.assign({ "Content-Type": "text/html; charset=utf-8" }, CORS)
      });
    }

    if (request.method === "GET" && path === "/api/status") {
      return jsonResponse(statusPayload(env), 200, CORS);
    }

    if (request.method === "GET" && path === "/api/info") {
      return jsonResponse({
        name: "LionAI Free Gateway",
        status: "ok",
        runtime: "cloudflare-worker",
        auth_required: !!trim(env.RELAY_TOKEN),
        providers: Object.keys(configured(env)).sort(),
        usage: "POST /v1/chat/completions with model=provider/model"
      }, 200, CORS);
    }

    if (request.method === "GET" && path === "/health") {
      const conf = configured(env);
      const labels = {};
      for (const pid of Object.keys(conf).sort()) labels[pid] = conf[pid][3];
      return jsonResponse({ status: "ok", auth_required: !!trim(env.RELAY_TOKEN), providers: labels }, 200, CORS);
    }

    if (request.method === "GET" && path === "/v1/models") {
      if (!authOk(request, env)) return errorResponse(401, "Invalid relay token", "authentication_error", CORS);
      return jsonResponse({ object: "list", data: await allModels(env) }, 200, CORS);
    }

    if (request.method === "POST" && path === "/v1/chat/completions") {
      return handleChat(request, env);
    }

    return errorResponse(404, "Not found", "not_found_error", CORS);
  }
};

async function handleChat(request, env) {
  if (!authOk(request, env)) return errorResponse(401, "Invalid relay token", "authentication_error", CORS);

  const len = Number(request.headers.get("Content-Length") || "0");
  if (len > 8 * 1024 * 1024) return errorResponse(400, "Invalid body size", "invalid_request_error", CORS);

  let payload;
  try {
    payload = JSON.parse(await request.text());
  } catch (e) {
    return errorResponse(400, "Invalid JSON body", "invalid_request_error", CORS);
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return errorResponse(400, "Body must be a JSON object", "invalid_request_error", CORS);
  }

  const conf = configured(env);
  const resolved = resolveModel(payload.model, conf, env);
  if (!resolved) {
    const avail = Object.keys(conf).map((p) => p + "/" + conf[p][2][0]).join(", ") || "none";
    return errorResponse(400, "Unknown model. Use provider/model. Available: " + avail, "invalid_request_error", CORS);
  }

  const { pid, model, spec } = resolved;
  if (pid === "cfai") return await handleCfAi(payload, model, env);
  const key = spec[0] ? trim(env[spec[0]]) : "";
  const base = providerBase(pid, spec, env);
  if (!base) return errorResponse(400, "Provider " + pid + " is not configured", "invalid_request_error", CORS);
  const upstreamUrl = base + "/chat/completions";

  const upstream = {};
  for (const k of ALLOWED_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(payload, k)) upstream[k] = payload[k];
  }
  upstream.model = model;
  upstream.stream = false;

  const headers = {
    "Content-Type": "application/json",
    Accept: "application/json"
  };
  if (key) headers.Authorization = "Bearer " + key;
  if (PROVIDER_HEADERS[pid]) Object.assign(headers, PROVIDER_HEADERS[pid]);
  if (pid === "openrouter") {
    headers["HTTP-Referer"] = "https://github.com/starmaen/LionAI";
    headers["X-Title"] = "Lion AI Gateway";
  }

  const timeoutMs = Number(env.UPSTREAM_TIMEOUT || 120) * 1000;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const resp = await fetch(upstreamUrl, {
      method: "POST",
      headers: headers,
      body: JSON.stringify(upstream),
      signal: ctrl.signal
    });
    const text = await resp.text();
    let data;
    try { data = JSON.parse(text); } catch (e) {
      data = { error: { message: text.slice(0, 2000), type: "upstream_error" } };
    }
    return jsonResponse(data, resp.status, CORS);
  } catch (e) {
    return errorResponse(502, "Upstream " + pid + " failed: " + (e && e.name ? e.name : "error") + ": " + (e && e.message ? e.message : e), "upstream_error", CORS);
  } finally {
    clearTimeout(timer);
  }
}

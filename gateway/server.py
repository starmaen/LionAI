#!/usr/bin/env python3
"""
LionAI Free Gateway
-------------------
بوابة OpenAI-Compatible تعمل على PaaS مجاني (Render/Fly/Railway/HuggingFace)
وتجمع عدة مزودات مجانية/شبه مجانية خلف عنوان واحد.

- التطبيق يتصل بالبوابة فقط، والبوابة تتصل بالمزودات من شبكة الاستضافة المفتوحة.
- المفاتيح تبقى على الخادم (متغيرات بيئة) ولا تُخزَّن في التطبيق.
- التطبيق يحتاج فقط RELAY_TOKEN كمفتاح.

المسارات:
    GET  /                 معلومات
    GET  /health           الحالة والمزودات المفعّلة
    GET  /v1/models        قائمة الموديلات بصيغة provider/model
    POST /v1/chat/completions

صيغة الموديل: "<provider>/<model>" مثل: groq/openai/gpt-oss-120b
"""

from __future__ import annotations

import json
import os
import re
import ssl
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "10000"))
RELAY_TOKEN = os.environ.get("RELAY_TOKEN", "").strip()
UPSTREAM_TIMEOUT = float(os.environ.get("UPSTREAM_TIMEOUT", "120"))
DEFAULT_MODEL = os.environ.get("RELAY_DEFAULT_MODEL", "").strip()
MODELS_TTL = int(os.environ.get("MODELS_TTL", "600"))
MAX_BODY = 8 * 1024 * 1024

# provider_id: (env_key, base_url, default_models, label)
# base_url يجب أن ينتهي بـ /v1 (أو ما يعادله) وتُضاف إليه /chat/completions و /models
PROVIDERS = {
    "groq": (
        "GROQ_API_KEY",
        "https://api.groq.com/openai/v1",
        ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "qwen/qwen3-32b"],
        "Groq (free tier)",
    ),
    "cerebras": (
        "CEREBRAS_API_KEY",
        "https://api.cerebras.ai/v1",
        ["gpt-oss-120b", "llama-3.3-70b", "qwen-3-32b"],
        "Cerebras (free tier)",
    ),
    "gemini": (
        "GEMINI_API_KEY",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"],
        "Google Gemini (free tier)",
    ),
    "hf": (
        "HF_TOKEN",
        "https://router.huggingface.co/v1",
        ["deepseek-ai/DeepSeek-V4.1-Flash-1017", "meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen3-235B-A22B-Instruct-2507"],
        "Hugging Face Inference Providers",
    ),
    "github": (
        "GITHUB_MODELS_TOKEN",
        "https://models.inference.ai.azure.com",
        ["gpt-4o", "gpt-4o-mini", "o1", "Meta-Llama-3.1-405B-Instruct"],
        "GitHub Models (free with GitHub PAT)",
    ),
    "mistral": (
        "MISTRAL_API_KEY",
        "https://api.mistral.ai/v1",
        ["mistral-large-latest", "mistral-medium-latest", "mistral-small-latest"],
        "Mistral (free tier)",
    ),
    "together": (
        "TOGETHER_API_KEY",
        "https://api.together.xyz/v1",
        ["meta-llama/Llama-3.3-70B-Instruct-Turbo-Free", "deepseek-ai/DeepSeek-R1-Distill-Llama-70B-free"],
        "Together (free models)",
    ),
    "openrouter": (
        "OPENROUTER_API_KEY",
        "https://openrouter.ai/api/v1",
        ["deepseek/deepseek-chat-v3-0324:free", "meta-llama/llama-3.3-70b-instruct:free"],
        "OpenRouter (free models)",
    ),
    "nvidia": (
        "NVIDIA_API_KEY",
        "https://integrate.api.nvidia.com/v1",
        ["openai/gpt-oss-120b", "meta/llama-3.3-70b-instruct"],
        "NVIDIA NIM (free credits)",
    ),
    "sambanova": (
        "SAMBANOVA_API_KEY",
        "https://api.sambanova.ai/v1",
        ["DeepSeek-R1", "Meta-Llama-3.3-70B-Instruct", "Llama-4-Maverick-17B-128E-Instruct"],
        "SambaNova (free tier)",
    ),
    "chutes": (
        "CHUTES_API_KEY",
        "https://llm.chutes.ai/v1",
        ["deepseek-ai/DeepSeek-V3-0324", "Qwen/Qwen3-235B-A22B"],
        "Chutes (cheap/free)",
    ),
    "nebius": (
        "NEBIUS_API_KEY",
        "https://api.studio.nebius.ai/v1",
        ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"],
        "Nebius AI Studio (free credits)",
    ),
    "hyperbolic": (
        "HYPERBOLIC_API_KEY",
        "https://api.hyperbolic.xyz/v1",
        ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"],
        "Hyperbolic (free credits)",
    ),
    "novita": (
        "NOVITA_API_KEY",
        "https://api.novita.ai/v3/openai",
        ["deepseek/deepseek-v3-0324", "meta-llama/llama-3.3-70b-instruct"],
        "Novita AI (free credits)",
    ),
    "deepinfra": (
        "DEEPINFRA_API_KEY",
        "https://api.deepinfra.com/v1/openai",
        ["deepseek-ai/DeepSeek-V3-0324", "meta-llama/Llama-3.3-70B-Instruct"],
        "DeepInfra (cheap)",
    ),
    "scaleway": (
        "SCALEWAY_API_KEY",
        "https://api.scaleway.ai/v1",
        ["llama-3.3-70b-instruct", "qwen2.5-coder-32b-instruct"],
        "Scaleway (free tier)",
    ),
    "siliconflow": (
        "SILICONFLOW_API_KEY",
        "https://api.siliconflow.cn/v1",
        ["deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"],
        "SiliconFlow (free models)",
    ),
    "modelscope": (
        "MODELSCOPE_API_KEY",
        "https://api-inference.modelscope.cn/v1",
        ["deepseek-ai/DeepSeek-V3.2", "Qwen/Qwen3-235B-A22B-Instruct-2507"],
        "ModelScope (free quota)",
    ),
    "aimlapi": (
        "AIMLAPI_KEY",
        "https://api.aimlapi.com/v1",
        ["gpt-4o-mini", "deepseek-chat"],
        "AI/ML API (free tier)",
    ),
    "requesty": (
        "REQUESTY_API_KEY",
        "https://router.requesty.ai/v1",
        ["openai/gpt-4o-mini", "deepseek/deepseek-chat"],
        "Requesty (free credits)",
    ),
    # مزودات بلا مفتاح
    "pollinations": (
        "",
        "https://text.pollinations.ai/openai",
        ["openai", "mistral"],
        "Pollinations (keyless)",
    ),
    "llm7": (
        "",
        "https://api.llm7.io/v1",
        ["default", "fast"],
        "LLM7 (keyless)",
    ),
}

_models_cache: dict[str, tuple[float, list[str]]] = {}
_cache_lock = threading.Lock()
_ssl_ctx = ssl.create_default_context()

# رؤوس إضافية اختيارية للمزودات المضافة عبر EXTRA_PROVIDERS/providers.json
PROVIDER_HEADERS: dict[str, dict[str, str]] = {}


def _normalize_base(base: str) -> str:
    base = base.strip().rstrip("/")
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    return base


def _load_extra_providers() -> None:
    """يضيف مزودات/منصات دون تعديل الكود.

    المصادر:
      - ملف providers.json بجانب server.py (اختياري)
      - متغير البيئة EXTRA_PROVIDERS (JSON)

    الصيغة: قائمة عناصر، أو كائن فيه مفتاح providers:
    {
      "id": "myplatform",
      "label": "My Platform",
      "baseUrl": "https://api.example.com/v1",
      "models": ["model-a", "model-b"],
      "envKey": "MYPLATFORM_KEY",   # اتركه فارغاً أو keyless=true
      "keyless": false,
      "headers": {"X-Custom": "value"}
    }
    """
    sources = []
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "providers.json")
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                sources.append(json.load(fh))
        except Exception as exc:
            print(f"WARNING: cannot read providers.json: {exc}", flush=True)
    raw = os.environ.get("EXTRA_PROVIDERS", "").strip()
    if raw:
        try:
            sources.append(json.loads(raw))
        except Exception as exc:
            print(f"WARNING: invalid EXTRA_PROVIDERS JSON: {exc}", flush=True)

    for data in sources:
        items = data.get("providers") if isinstance(data, dict) else data
        if not isinstance(items, list):
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            pid = str(item.get("id", "")).strip().lower()
            base = _normalize_base(str(item.get("baseUrl") or item.get("base_url") or item.get("endpoint") or ""))
            if not re.fullmatch(r"[a-z0-9_.-]{1,32}", pid) or not base:
                print(f"WARNING: skipped invalid provider: {item}", flush=True)
                continue
            keyless = bool(item.get("keyless", False))
            env_key = "" if keyless else str(item.get("envKey") or item.get("env_key") or "").strip()
            models = item.get("models") or []
            if isinstance(models, str):
                models = [models]
            models = [str(m).strip() for m in models if str(m).strip()]
            single = str(item.get("model") or "").strip()
            if single and single not in models:
                models.insert(0, single)
            if not models:
                print(f"WARNING: provider {pid} has no models", flush=True)
                continue
            label = str(item.get("label") or item.get("name") or pid).strip()
            PROVIDERS[pid] = (env_key, base, models, label)
            headers = item.get("headers")
            if isinstance(headers, dict) and headers:
                PROVIDER_HEADERS[pid] = {str(k): str(v) for k, v in headers.items()}
            print(f"Extra provider loaded: {pid} -> {base}", flush=True)


_load_extra_providers()



def _env_key(spec) -> str:
    return os.environ.get(spec[0], "").strip() if spec[0] else ""


def configured() -> dict[str, tuple]:
    out = {}
    for pid, spec in PROVIDERS.items():
        if spec[0] == "" or _env_key(spec):
            out[pid] = spec
    return out


def _http_json(url: str, key: str, payload=None, timeout=None, extra: dict | None = None):
    headers = {"Accept": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    if extra:
        headers.update(extra)
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    with urllib.request.urlopen(req, timeout=timeout or UPSTREAM_TIMEOUT, context=_ssl_ctx) as resp:
        raw = resp.read()
        return resp.status, json.loads(raw.decode("utf-8", "replace") or "{}")


def _upstream_models(pid: str, spec) -> list[str]:
    key = _env_key(spec)
    base = spec[1].rstrip("/")
    try:
        status, data = _http_json(base + "/models", key, timeout=8, extra=PROVIDER_HEADERS.get(pid))
        items = data.get("data") if isinstance(data, dict) else None
        ids = [str(m.get("id", "")).strip() for m in (items or []) if isinstance(m, dict)]
        ids = [i for i in ids if i]
        if ids:
            return ids[:200]
    except Exception:
        pass
    return list(spec[2])


def models_for(pid: str, spec) -> list[str]:
    now = time.time()
    with _cache_lock:
        hit = _models_cache.get(pid)
        if hit and now - hit[0] < MODELS_TTL:
            return hit[1]
    ids = _upstream_models(pid, spec)
    with _cache_lock:
        _models_cache[pid] = (now, ids)
    return ids


def all_models() -> list[dict]:
    conf = configured()
    out = []
    if not conf:
        return out
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=min(8, len(conf))) as pool:
        futures = {pid: pool.submit(models_for, pid, spec) for pid, spec in conf.items()}
        for pid in conf:
            for mid in futures[pid].result():
                out.append(
                    {
                        "id": f"{pid}/{mid}",
                        "object": "model",
                        "owned_by": pid,
                        "created": 0,
                    }
                )
    return out


def resolve_model(model: str) -> tuple[str, str, tuple] | None:
    model = (model or "").strip()
    conf = configured()
    if not model or model == "auto":
        if DEFAULT_MODEL:
            model = DEFAULT_MODEL
        else:
            for pid, spec in conf.items():
                return pid, spec[2][0], spec
            return None
    if "/" in model:
        pid, rest = model.split("/", 1)
        if pid in conf and rest:
            return pid, rest, conf[pid]
    for pid, spec in conf.items():
        if model in spec[2]:
            return pid, model, spec
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "LionAIGateway/1.0"

    def log_message(self, fmt, *args):  # quieter logs
        print("[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args), flush=True)

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _send(self, code: int, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _error(self, code: int, message: str, etype: str = "invalid_request_error"):
        self._send(code, {"error": {"message": message, "type": etype, "code": code}})

    def _authorized(self) -> bool:
        if not RELAY_TOKEN:
            return True
        got = self.headers.get("Authorization", "")
        if got.lower().startswith("bearer "):
            got = got[7:].strip()
        else:
            got = self.headers.get("X-Api-Key", "").strip()
        return got == RELAY_TOKEN

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path == "/":
            self._send(
                200,
                {
                    "name": "LionAI Free Gateway",
                    "status": "ok",
                    "auth_required": bool(RELAY_TOKEN),
                    "providers": sorted(configured().keys()),
                    "usage": "POST /v1/chat/completions with model=provider/model",
                },
            )
            return
        if path == "/health":
            self._send(
                200,
                {
                    "status": "ok",
                    "auth_required": bool(RELAY_TOKEN),
                    "providers": {pid: PROVIDERS[pid][3] for pid in sorted(configured())},
                },
            )
            return
        if path == "/v1/models":
            if not self._authorized():
                self._error(401, "Invalid relay token", "authentication_error")
                return
            self._send(200, {"object": "list", "data": all_models()})
            return
        self._error(404, "Not found")

    def do_POST(self):
        path = self.path.split("?", 1)[0].rstrip("/")
        if path != "/v1/chat/completions":
            self._error(404, "Not found")
            return
        if not self._authorized():
            self._error(401, "Invalid relay token", "authentication_error")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self._error(400, "Invalid body size")
            return
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8", "replace"))
        except Exception:
            self._error(400, "Invalid JSON body")
            return
        if not isinstance(payload, dict):
            self._error(400, "Body must be a JSON object")
            return

        resolved = resolve_model(str(payload.get("model", "")))
        if resolved is None:
            avail = ", ".join(f"{p}/{s[2][0]}" for p, s in configured().items()) or "none"
            self._error(400, f"Unknown model. Use provider/model. Available: {avail}")
            return
        pid, model, spec = resolved
        key = _env_key(spec)
        base = spec[1].rstrip("/")
        url = base + "/chat/completions"

        allowed = {
            "messages", "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "stop", "presence_penalty", "frequency_penalty", "seed", "response_format",
            "tools", "tool_choice", "user", "n", "logprobs", "top_logprobs",
        }
        upstream = {k: v for k, v in payload.items() if k in allowed}
        upstream["model"] = model
        upstream["stream"] = False

        headers_extra = dict(PROVIDER_HEADERS.get(pid, {}))
        if pid == "openrouter":
            headers_extra.update(
                {
                    "HTTP-Referer": "https://github.com/starmaen/LionAI",
                    "X-Title": "Lion AI Gateway",
                }
            )

        try:
            req = urllib.request.Request(url, data=json.dumps(upstream).encode("utf-8"), method="POST")
            req.add_header("Content-Type", "application/json")
            req.add_header("Accept", "application/json")
            if key:
                req.add_header("Authorization", f"Bearer {key}")
            for k, v in headers_extra.items():
                req.add_header(k, v)
            with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT, context=_ssl_ctx) as resp:
                raw = resp.read()
                code = resp.status
            try:
                data = json.loads(raw.decode("utf-8", "replace"))
            except Exception:
                data = {"error": {"message": raw.decode("utf-8", "replace")[:2000], "type": "upstream_error"}}
            self._send(code, data)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                data = json.loads(raw)
            except Exception:
                data = {"error": {"message": raw[:2000] or str(e), "type": "upstream_error"}}
            self._send(e.code, data)
        except Exception as e:
            self._error(502, f"Upstream {pid} failed: {type(e).__name__}: {e}", "upstream_error")


def main():
    if not RELAY_TOKEN:
        print("WARNING: RELAY_TOKEN is empty — the gateway is public. Set RELAY_TOKEN!", flush=True)
    print(f"LionAI gateway listening on 0.0.0.0:{PORT}", flush=True)
    print("Enabled providers: " + (", ".join(sorted(configured())) or "none"), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()

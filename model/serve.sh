#!/usr/bin/env bash
# خادم محلي متوافق مع OpenAI عبر llama.cpp (يعمل على Termux أيضاً بعد بناء llama.cpp).
# أضفه في التطبيق كمزود مخصص: http://127.0.0.1:${PORT}/v1  والموديل: lion-1
PORT="${PORT:-8089}"   # ليس 44045 كي لا يتعارض مع خادمك الحالي
exec llama-server -m "${1:-lion-1.Q4_K_M.gguf}" --alias lion-1 --host 127.0.0.1 --port "$PORT" -c 8192 --jinja

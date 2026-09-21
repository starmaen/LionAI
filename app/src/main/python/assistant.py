import requests
import json

def ask(message, api_key="", provider="groq", history_json="[]"):
    if not api_key:
        return "⚠️ يرجى إدخال مفتاح API من الإعدادات (⚙️)"
    try:
        history = json.loads(history_json) if history_json else []
    except Exception:
        history = []
    messages = history + [{"role": "user", "content": message}]
    try:
        if provider == "groq":
            url = "https://api.groq.com/openai/v1/chat/completions"
            headers = {"Authorization": "Bearer " + api_key, "Content-Type": "application/json"}
            data = {
                "model": "groq/compound-beta",
                "messages": messages,
                "max_tokens": 1500,
                "temperature": 0.3
            }
            r = requests.post(url, headers=headers, json=data, timeout=90)
            js = r.json()
            if "error" in js:
                data["model"] = "llama-3.3-70b-versatile"
                r = requests.post(url, headers=headers, json=data, timeout=60)
                js = r.json()
            return js.get("choices", [{}])[0].get("message", {}).get("content", "لا يوجد رد")
        elif provider == "openai":
            url = "https://api.openai.com/v1/chat/completions"
            headers = {"Authorization": "Bearer " + api_key, "Content-Type": "application/json"}
            data = {"model": "gpt-4o-mini", "messages": messages, "max_tokens": 1500, "temperature": 0.3}
            r = requests.post(url, headers=headers, json=data, timeout=60)
            return r.json().get("choices", [{}])[0].get("message", {}).get("content", "لا يوجد رد")
        elif provider == "gemini":
            url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + api_key
            data = {"contents": [{"parts": [{"text": message}]}]}
            r = requests.post(url, json=data, timeout=60)
            return r.json().get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "لا يوجد رد")
        elif provider == "claude":
            url = "https://api.anthropic.com/v1/messages"
            headers = {"x-api-key": api_key, "anthropic-version": "2023-06-01", "Content-Type": "application/json"}
            data = {"model": "claude-3-5-sonnet-20241022", "max_tokens": 1500, "messages": messages}
            r = requests.post(url, headers=headers, json=data, timeout=60)
            return r.json().get("content", [{}])[0].get("text", "لا يوجد رد")
        else:
            return "مزود غير معروف"
    except Exception as e:
        return "خطأ: " + str(e)

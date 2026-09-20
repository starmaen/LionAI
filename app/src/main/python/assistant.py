import requests

def ask(message, api_key="", provider="groq", history=None):
    if not api_key:
        return "⚠️ يرجى إدخال مفتاح API من الإعدادات (⚙️)"
    if history is None:
        history = []
    messages = list(history) + [{"role": "user", "content": message}]
    try:
        if provider == "groq":
            url = "https://api.groq.com/openai/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            data = {"model": "qwen/qwen3.8-27b", "messages": messages, "max_tokens": 1500}
            r = requests.post(url, headers=headers, json=data, timeout=60)
            return r.json().get("choices", [{}])[0].get("message", {}).get("content", "لا يوجد رد")
        elif provider == "openai":
            url = "https://api.openai.com/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            data = {"model": "gpt-4o-mini", "messages": messages, "max_tokens": 1500}
            r = requests.post(url, headers=headers, json=data, timeout=60)
            return r.json().get("choices", [{}])[0].get("message", {}).get("content", "لا يوجد رد")
        elif provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}"
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
            return f"مزود غير معروف: {provider}"
    except Exception as e:
        return f"خطأ: {str(e)}"

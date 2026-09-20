import requests
import json

def ask(message, api_key="", provider="groq"):
    if not api_key:
        return "⚠️ يرجى إدخال مفتاح API في الإعدادات"
    
    try:
        if provider == "groq":
            url = "https://api.groq.com/openai/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            data = {
                "model": "qwen/qwen3.8-27b",
                "messages": [{"role": "user", "content": message}],
                "max_tokens": 1500
            }
        elif provider == "openai":
            url = "https://api.openai.com/v1/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
            data = {
                "model": "gpt-4o-mini",
                "messages": [{"role": "user", "content": message}],
                "max_tokens": 1500
            }
        elif provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}"
            headers = {"Content-Type": "application/json"}
            data = {"contents": [{"parts": [{"text": message}]}]}
        elif provider == "claude":
            url = "https://api.anthropic.com/v1/messages"
            headers = {
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json"
            }
            data = {
                "model": "claude-3-5-sonnet-20241022",
                "max_tokens": 1500,
                "messages": [{"role": "user", "content": message}]
            }
        else:
            return f"مزود غير معروف: {provider}"
        
        response = requests.post(url, headers=headers, json=data, timeout=60)
        result = response.json()
        
        if provider == "gemini":
            return result.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "لا يوجد رد")
        elif provider == "claude":
            return result.get("content", [{}])[0].get("text", "لا يوجد رد")
        else:
            return result.get("choices", [{}])[0].get("message", {}).get("content", "لا يوجد رد")
    except Exception as e:
        return f"خطأ: {str(e)}"

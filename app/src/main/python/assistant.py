import os
try:
    from groq import Groq
except ImportError:
    Groq = None

def ask(message, api_key="", model="qwen/qwen3.8-27b"):
    if not api_key:
        return "⚠️ يرجى إدخال مفتاح API أولاً"
    try:
        client = Groq(api_key=api_key)
        response = client.chat.completions.create(
            messages=[{"role": "user", "content": message}],
            model=model,
            max_tokens=1500
        )
        return response.choices[0].message.content
    except Exception as e:
        return f"خطأ: {str(e)}"

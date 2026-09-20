def ask(message, api_key=""):
    if not api_key:
        return "⚠️ يرجى إدخال مفتاح API أولاً"
    try:
        from groq import Groq
        client = Groq(api_key=api_key)
        response = client.chat.completions.create(
            messages=[{"role": "user", "content": message}],
            model="qwen/qwen3.8-27b",
            max_tokens=1500
        )
        return response.choices[0].message.content
    except Exception as e:
        return f"خطأ: {str(e)}"

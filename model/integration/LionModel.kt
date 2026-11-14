package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lion-1 («الأسد»): نموذج افتراضي = شخصية + توجيه مهام + تبديل تلقائي بين الخوادم.
 * لا يحتاج مكتبات إضافية. استدعِ chat() من خيط خلفي (Dispatchers.IO).
 */
object LionModel {
    const val NAME = "lion-1"

    private const val SYSTEM = """أنت «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria.
- أجب بلغة المستخدم، وبدقة وإيجاز، وابدأ بالجواب مباشرة.
- إن لم تكن متأكداً قل ذلك ولا تخترع معلومات.
- لا تذكر اسم أي مزود أو نموذج خلفي. إن سُئلت عن هويتك فأنت Lion-1 من Star Syria."""

    enum class Task(val temp: Double, val prompt: String) {
        CODE(0.2, "المهمة: برمجة. أعطِ كوداً كاملاً يعمل داخل كتل كود، ثم شرحاً قصيراً. اذكر الملف المقصود وأي تبعيات."),
        TEACH(0.4, "المهمة: تعليم. اشرح خطوة بخطوة من المبسّط إلى المتقدم بمثال عملي، ثم اقترح تمريناً صغيراً."),
        WRITE(0.8, "المهمة: كتابة. اكتب نصاً سليماً ومنظماً بأسلوب مناسب للغرض والجمهور."),
        REASON(0.3, "المهمة: تحليل واستدلال. فكّر بخطوات واضحة وقارن الخيارات ثم قدّم خلاصة."),
        TRANSLATE(0.2, "المهمة: ترجمة. ترجم بدقة وبأسلوب طبيعي دون إضافات."),
        SUMMARY(0.3, "المهمة: تلخيص. لخّص النقاط الجوهرية فقط بإيجاز."),
        GENERAL(0.6, "المهمة: محادثة عامة. كن ودوداً ومباشراً.")
    }

    /** نقطة اتصال: الخادم المحلي أولاً ثم الخارجي. */
    data class Endpoint(val url: String, val model: String, val key: String? = null)

    val DEFAULT_ENDPOINTS = listOf(
        Endpoint("http://127.0.0.1:44045/v1/chat/completions", model = "auto"),
        Endpoint("https://lionai-gateway.starsyria2500.workers.dev/v1/chat/completions", model = "lion-1")
    )

    fun detect(text: String): Task {
        val t = text.lowercase()
        fun has(vararg k: String) = k.any { t.contains(it) }
        return when {
            has("```", "كود", "برمج", "خطأ", "error", "function", "kotlin", "python", "gradle", "bug") -> Task.CODE
            has("اشرح", "علمني", "تعلم", "explain", "teach") -> Task.TEACH
            has("ترجم", "translate") -> Task.TRANSLATE
            has("لخص", "تلخيص", "summar") -> Task.SUMMARY
            has("اكتب", "صغ", "رسالة", "مقال", "write", "email") -> Task.WRITE
            has("حلل", "قارن", "لماذا", "احسب", "analy", "why") -> Task.REASON
            else -> Task.GENERAL
        }
    }

    /** history: أزواج (role, content) حيث role = user أو assistant. */
    fun chat(
        history: List<Pair<String, String>>,
        endpoints: List<Endpoint> = DEFAULT_ENDPOINTS,
        forced: Task? = null
    ): String {
        val last = history.lastOrNull { it.first == "user" }?.second ?: ""
        val task = forced ?: detect(last)
        var lastError: Exception? = null
        for (ep in endpoints) {
            try {
                return call(ep, task, history)
            } catch (e: Exception) {
                lastError = e // جرّب الخادم التالي
            }
        }
        throw lastError ?: IllegalStateException("لا توجد خوادم")
    }

    private fun call(ep: Endpoint, task: Task, history: List<Pair<String, String>>): String {
        val msgs = JSONArray().put(
            JSONObject().put("role", "system").put("content", "$SYSTEM\n\n${task.prompt}")
        )
        history.takeLast(20).forEach { (r, c) ->
            msgs.put(JSONObject().put("role", r).put("content", c))
        }
        val body = JSONObject()
            .put("model", ep.model)
            .put("messages", msgs)
            .put("temperature", task.temp)

        val conn = (URL(ep.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            ep.key?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().readText()
        if (code !in 200..299) error("HTTP $code: ${text.take(200)}")

        return JSONObject(text)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}

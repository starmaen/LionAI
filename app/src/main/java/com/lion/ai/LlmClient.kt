package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatTurn(val role: String, val content: String)

class LlmException(val status: Int, message: String) : Exception(message)

object LlmClient {
    /** عناوين وسيطة اختيارية (مثل وسيط Cloudflare Worker) تتجاوز العنوان الافتراضي لكل مزود. */
    @Volatile
    var endpointOverrides: Map<String, String> = emptyMap()

    fun reloadOverrides(prefs: android.content.SharedPreferences) {
        endpointOverrides = PROVIDERS.associate { it.id to (prefs.getString("endpoint_" + it.id, "") ?: "").trim() }
    }

    private fun endpoint(provider: String, default: String): String =
        endpointOverrides[provider]?.takeIf { it.startsWith("http") } ?: default

    private const val USER_AGENT = "LionAI/1.3 (Android)"
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000
    private const val MAX_TOKENS = 2048
    private const val GEMINI_MAX_TOKENS = 8192
    private const val CLAUDE_MAX_TOKENS = 8192
    private const val OPENAI_MAX_TOKENS = 8192
    private const val TEMPERATURE = 0.3

    private const val SYSTEM_PROMPT =
        "أنت Lion AI، مساعد ذكي عربي متعدد الاستخدامات. أجب دائماً بالعربية الفصحى المبسطة ولا تجب بالإنجليزية إلا إذا كتب المستخدم بها أو طلبها، " +
            "وكن دقيقاً ومباشراً. عند كتابة الكود استخدم كتل Markdown مع تحديد اللغة، " +
            "واشرح الخطوات باختصار. لا تخترع معلومات؛ إن لم تكن متأكداً فقل ذلك."

    private const val TITLE_PROMPT =
        "أنت تكتب عناوين قصيرة للمحادثات. أجب بالعنوان فقط: من 2 إلى 5 كلمات بلغة المستخدم، " +
            "بدون علامات ترقيم أو اقتباس أو إيموجي أو شرح."

    private const val INTENT_PROMPT = """أنت محلّل أوامر لتطبيق هاتف. حوّل طلب المستخدم إلى JSON فقط، بدون شرح وبدون Markdown.
الوقت الحالي: {NOW}
الصيغة:
{"intent":"reminder|sms|call|none","recipient":"الاسم أو الرقم كما ذكره المستخدم أو فارغ","message":"النص النهائي","delay_minutes":رقم أو null,"at":"yyyy-MM-dd HH:mm" أو null,"repeat_minutes":رقم أو null}
القواعد:
- reminder: تذكير المستخدم بشيء. message = جملة تذكير واضحة وكاملة تُعرض عند حلول الموعد بصيغة المخاطب، تبدأ بـ «حان موعد ...» أو «عليك ... الآن»، وتحافظ على كلمات المهمة كما قالها المستخدم تماماً دون استبدال أي كلمة أو تغيير معناها. أمثلة: «ذكرني بعد دقيقة أن أشرب الدواء» ← «حان موعد شرب الدواء الآن». «ذكرني الساعة 5 بزيارة صديقي» ← «حان موعد زيارة صديقك الآن». «ذكرني بعد ساعة أن أتصل بأمي» ← «حان موعد الاتصال بأمك الآن».
- sms: إرسال رسالة لشخص. message = نص الرسالة كما سيقرؤه المستلم مباشرة بصيغة المخاطب (حوّل الغائب إلى مخاطب، مثل: "اخبر احمد ان ياتي هنا الساعة 5" يصبح "تعال إلى هنا الساعة 5"). لا تضف معلومات لم يذكرها المستخدم.
- call: اتصال هاتفي بشخص. message فارغ.
- الوقت النسبي: ربع ساعة=15، نصف ساعة=30، ساعة=60، دقيقتان=2، ساعتان=120 ... ضعه في delay_minutes.
- الوقت المحدد (الساعة 7 صباحاً، غداً الساعة 9 مساءً ...) ضعه في at بتوقيت الجهاز وبنظام 24 ساعة. إذا لم يُذكر صباحاً أو مساءً فاختر أقرب وقت قادم.
- التكرار للتذكير فقط: "كل يوم/يومياً"=1440، "كل ساعة"=60، "كل أسبوع"=10080، "كل 30 دقيقة"=30 ضعه في repeat_minutes، وإن ذُكر وقت محدد (كل يوم الساعة 8 صباحاً) ضعه في at أيضاً.
- إن لم يُذكر وقت فاجعل delay_minutes و at يساويان null (التنفيذ فوري للرسالة والاتصال).
- إن لم يكن الطلب تذكيراً أو رسالة أو اتصالاً فعلياً (مثل سؤال عن كيفية الإرسال) فاجعل intent=none.
- لا تترجم أبداً: اكتب message بنفس لغة المستخدم تماماً (العربية تبقى عربية).
- الرد JSON واحد فقط."""

    /** هل الموديل الحالي يقرأ الصور؟ (OpenRouter يعتمد على الموديل، وخطأ المزود يُعرض إن لم يدعمها) */
    fun supportsVision(provider: String, model: String): Boolean = when (provider) {
        "gemini", "openai", "claude", "deepseek", "openrouter" -> true
        "groq" -> (model.ifBlank { providerInfo("groq").defaultModel }).contains("llama-4")
        else -> false
    }

    fun planImageEdit(
        userText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String
    ): String? {
        val reply = ask(userText, apiKey, provider, emptyList(), model, accountId, null, IMAGE_EDIT_PROMPT)
        if (isErrorReply(reply)) return null
        return stripThinking(reply)
    }

    private const val IMAGE_EDIT_PROMPT = """أنت مخطط تعديل صور. حوّل طلب المستخدم إلى JSON فقط بدون شرح وبدون Markdown:
{"ops":[{"op":"...","value":رقم,"text":"..."}]}
العمليات المتاحة بالضبط:
- rotate: value بالدرجات (90 أو -90 أو 180)
- flip_h (قلب أفقي) ، flip_v (قلب رأسي)
- brightness: value من -100 إلى 100 (تفتيح موجب، تغميق سالب)
- contrast: value من -100 إلى 100
- saturation: value من -100 إلى 100 (-100 يعني أبيض وأسود)
- grayscale (أبيض وأسود) ، sepia (بني قديم) ، invert (عكس الألوان)
- blur: value من 1 إلى 25 (تمويه)
- sharpen (زيادة الحدة)
- crop: text بنسبة العرض:الارتفاع مثل "1:1" أو "4:3" أو "16:9" أو "9:16"
- resize: value أكبر ضلع بالبكسل (مثل 1080)
- preset: text معرّف المرشح الجاهز: vivid (حيوي) ، bw (أبيض وأسود) ، sepia ، warm (دافئ) ، cool (بارد) ، drama ، fade (هادئ) ، noir ، dream (حالم)
- auto: تحسين ذكي تلقائي للإضاءة والتباين والألوان (استعمله عند طلب «حسّن الصورة» أو «حسّن الجودة»)
- text: text النص المراد كتابته، value موضعه (1 أعلى، 2 وسط، 3 أسفل)، وأضف "color":"white|black|yellow|red" اختيارياً
رتّب العمليات بالترتيب المنطقي. إن لم يمكن تنفيذ الطلب بهذه العمليات (مثل إضافة أو حذف عنصر في الصورة) فأعد {"ops":[],"note":"سبب قصير بالعربية"}."""

    fun isErrorReply(reply: String): Boolean =
        reply.startsWith("⚠️") || reply.startsWith("🔑") || reply.startsWith("⏳") ||
            reply.startsWith("لم يصل رد")

    private fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    fun generateTitle(
        userText: String,
        assistantText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String
    ): String? {
        val arabic = userText.any { it in '\u0600'..'\u06FF' }
        val langNote = if (arabic) "اكتب العنوان بالعربية.\n" else ""
        val prompt = "اكتب عنواناً لهذه المحادثة.\n" + langNote +
            "\nالمستخدم: ${userText.take(400)}\nالمساعد: ${assistantText.take(400)}"
        val reply = ask(prompt, apiKey, provider, emptyList(), model, accountId, null, TITLE_PROMPT)
        if (isErrorReply(reply)) return null
        val line = stripThinking(reply).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val clean = line.trim('"', '\'', '«', '»', '*', '#', '`', '.', '،', ':', ' ').take(40)
        return clean.ifBlank { null }
    }

    fun extractIntent(
        userText: String,
        apiKey: String,
        provider: String,
        model: String,
        accountId: String,
        nowMillis: Long
    ): String? {
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date(nowMillis)) +
            " (" + TimeZone.getDefault().id + ")"
        val reply = ask(userText, apiKey, provider, emptyList(), model, accountId, null, INTENT_PROMPT.replace("{NOW}", now))
        if (isErrorReply(reply)) return null
        return stripThinking(reply)
    }

    fun defaultModel(provider: String): String = providerInfo(provider).defaultModel

    fun ask(
        message: String,
        apiKey: String,
        provider: String,
        history: List<ChatTurn>,
        modelOverride: String = "",
        accountId: String = "",
        image: ImageAttachment? = null,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        // المفاتيح الملصوقة تحمل غالباً مسافة أو سطراً جديداً يُفسد الطلب في كل المزودات
        val apiKey = apiKey.filterNot { it.isWhitespace() }
        if (apiKey.isBlank()) {
            return "⚠️ يرجى إدخال مفتاح API للمزود من الإعدادات (⚙️)"
        }
        if (provider == "cloudflare" && accountId.isBlank()) {
            return "⚠️ أدخل Cloudflare Account ID من الإعدادات"
        }

        val model = modelOverride.trim().ifBlank { defaultModel(provider) }
        val turns = normalize(history)

        return try {
            when (provider) {
                "gemini" -> callGemini(message, apiKey, model, turns, image, systemPrompt)
                "opencode" -> {
                    if (model.startsWith("gpt-") || model.startsWith("claude-") || model.startsWith("gemini-")) {
                        "⚠️ الموديل «$model» يعمل عبر واجهة مختلفة في OpenCode. اختر موديلاً من نوع chat/completions مثل big-pickle."
                    } else {
                        val hasArabic = systemPrompt == SYSTEM_PROMPT && message.any { it in '\u0600'..'\u06FF' }
                        val finalMessage = if (hasArabic) {
                            message + "\n\n(أجب بالعربية)"
                        } else {
                            message
                        }
                        callOpenAiCompatible(
                            endpoint("opencode", "https://opencode.ai/zen/v1/chat/completions"), apiKey, model, turns, finalMessage,
                            maxTokens = OPENAI_MAX_TOKENS, image = null, systemPrompt = systemPrompt
                        )
                    }
                }
                "groq" -> callOpenAiCompatible(
                    endpoint("groq", "https://api.groq.com/openai/v1/chat/completions"), apiKey, model, turns, message,
                    maxTokens = OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "openrouter" -> callOpenAiCompatible(
                    endpoint("openrouter", "https://openrouter.ai/api/v1/chat/completions"), apiKey, model, turns, message,
                    mapOf("HTTP-Referer" to "https://github.com/starmaen/LionAI", "X-Title" to "Lion AI"),
                    image = image, systemPrompt = systemPrompt
                )
                "deepseek" -> callOpenAiCompatible(
                    endpoint("deepseek", "https://api.deepseek.com/chat/completions"), apiKey, model, turns, message,
                    omitTemperature = true, maxTokens = OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "openai" -> callOpenAiCompatible(
                    endpoint("openai", "https://api.openai.com/v1/chat/completions"), apiKey, model, turns, message,
                    tokenParam = "max_completion_tokens", omitTemperature = true,
                    maxTokens = OPENAI_MAX_TOKENS, image = image, systemPrompt = systemPrompt
                )
                "cloudflare" -> callOpenAiCompatible(
                    "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions",
                    apiKey, model, turns, message, image = image, systemPrompt = systemPrompt
                )
                "cohere" -> callCohere(message, apiKey, model, turns, image, systemPrompt)
                "claude" -> callClaude(message, apiKey, model, turns, image, systemPrompt)
                else -> "⚠️ مزود غير معروف: $provider"
            }
        } catch (e: LlmException) {
            when (e.status) {
                401, 403 -> "🔑 المفتاح غير صالح أو لا يملك صلاحية لهذا الموديل (${e.status}).\n${e.message}" +
                    regionHint(provider, e.message.orEmpty())
                404 -> "⚠️ الموديل «$model» غير متاح لهذا المفتاح، أو أن المزود رفض الطلب. جرّب موديلاً آخر من الإعدادات." +
                    regionHint(provider, e.message.orEmpty())
                429 -> "⏳ تجاوزت حد الاستخدام المجاني مؤقتاً. انتظر قليلاً ثم أعد المحاولة.\n${e.message}"
                else -> "⚠️ خطأ من المزود (${e.status}): ${e.message}"
            }
        } catch (e: Exception) {
            "⚠️ تعذر الاتصال بالمزود: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun regionHint(provider: String, message: String): String {
        if (provider == "groq") {
            return "\n\n💡 Groq يحجب طلبات الدول الخاضعة للعقوبات (يرد 403 أو 404 حتى مع مفتاح صحيح). " +
                "جرّب تشغيل VPN بدولة أخرى، أو أدخل «عنوان وسيط» في الإعدادات، أو استخدم OpenCode / OpenRouter."
        }
        val blocked = message.contains("Access denied", ignoreCase = true) ||
            message.contains("network settings", ignoreCase = true) ||
            message.contains("not supported", ignoreCase = true) ||
            message.contains("1010")
        return if (blocked) {
            "\n\n💡 يبدو أن المزود يرفض الاتصال من شبكتك أو منطقتك. جرّب VPN أو «عنوان وسيط» من الإعدادات."
        } else {
            ""
        }
    }

    private fun callOpenAiCompatible(
        url: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tokenParam: String = "max_tokens",
        omitTemperature: Boolean = false,
        maxTokens: Int = MAX_TOKENS,
        image: ImageAttachment? = null,
        systemPrompt: String = SYSTEM_PROMPT
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val userContent: Any = if (image == null) {
            message
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", message))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", imageDataUri(image)))
                )
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put(tokenParam, maxTokens)
        if (!omitTemperature) body.put("temperature", TEMPERATURE)

        val headers = mutableMapOf("Authorization" to "Bearer $apiKey")
        headers.putAll(extraHeaders)

        val response = postJson(url, headers, body)
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            .orEmpty()
        return content.ifBlank { "لم يصل رد من المزود." }
    }

    private fun callGemini(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            )
        }
        val userParts = JSONArray().put(JSONObject().put("text", message))
        if (image != null) {
            userParts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", image.mime).put("data", image.base64)
                )
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", userParts))

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", GEMINI_MAX_TOKENS)
            )

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            model + ":generateContent"
        val response = postJson(url, mapOf("x-goog-api-key" to apiKey), body)

        val parts = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optBoolean("thought", false)) continue
                text.append(part.optString("text", ""))
            }
        }
        if (text.isNotBlank()) return text.toString()

        val blocked = response.optJSONObject("promptFeedback")
            ?.optString("blockReason", "")
            .orEmpty()
        return if (blocked.isNotBlank()) {
            "⚠️ حجب Gemini الطلب ($blocked). جرّب صياغة أخرى."
        } else {
            "لم يصل رد من Gemini."
        }
    }

    private fun callCohere(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val cohereContent: Any = if (image == null) {
            message
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", message))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", imageDataUri(image)))
                )
        }
        messages.put(JSONObject().put("role", "user").put("content", cohereContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)

        val response = postJson(
            "https://api.cohere.com/v2/chat",
            mapOf("Authorization" to "Bearer $apiKey"),
            body
        )

        val parts = response.optJSONObject("message")?.optJSONArray("content")
        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text", ""))
            }
        }
        return text.toString().ifBlank { "لم يصل رد من Cohere." }
    }

    private fun callClaude(
        message: String,
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        image: ImageAttachment?,
        systemPrompt: String
    ): String {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        val claudeContent = JSONArray()
        if (image != null) {
            claudeContent.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mime)
                            .put("data", image.base64)
                    )
            )
        }
        claudeContent.put(JSONObject().put("type", "text").put("text", message))
        messages.put(JSONObject().put("role", "user").put("content", claudeContent))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", CLAUDE_MAX_TOKENS)
            .put("system", systemPrompt)
            .put("messages", messages)

        val response = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            body
        )

        val parts = response.optJSONArray("content")
        val text = StringBuilder()
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text", ""))
            }
        }
        return text.toString().ifBlank { "لم يصل رد من Claude." }
    }

    private fun imageDataUri(image: ImageAttachment): String =
        "data:${image.mime};base64,${image.base64}"

    private fun normalize(history: List<ChatTurn>): List<ChatTurn> {
        val result = mutableListOf<ChatTurn>()
        history.forEach { turn ->
            if (turn.content.isBlank()) return@forEach
            val role = if (turn.role == "assistant") "assistant" else "user"
            val last = result.lastOrNull()
            if (last != null && last.role == role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n" + turn.content)
            } else {
                result.add(ChatTurn(role, turn.content))
            }
        }
        while (result.isNotEmpty() && result.first().role != "user") {
            result.removeAt(0)
        }
        return result
    }

    private fun idsOf(array: JSONArray?, key: String): List<String> {
        val out = mutableListOf<String>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val value = array.optJSONObject(i)?.optString(key, "").orEmpty()
                if (value.isNotBlank()) out.add(value)
            }
        }
        return out
    }

    private fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw LlmException(401, "أدخل مفتاح المزود أولاً ثم اضغط تحديث")
    }

    /** يجلب قائمة الموديلات الحالية من المزود. للمزودات المجانية يُرجع المجاني منها فقط حيث أمكن. */
    fun listModels(provider: String, rawKey: String, accountId: String): List<String> {
        val apiKey = rawKey.filterNot { it.isWhitespace() }
        val bearer = mapOf("Authorization" to "Bearer $apiKey")
        val ids: List<String> = when (provider) {
            "opencode" -> {
                val headers = if (apiKey.isNotBlank()) bearer else emptyMap()
                val json = getJson("https://opencode.ai/zen/v1/models", headers)
                idsOf(json.optJSONArray("data"), "id")
                    .filter { it == "big-pickle" || it.contains("free") }
            }

            "openrouter" -> {
                val json = getJson("https://openrouter.ai/api/v1/models", emptyMap())
                val data = json.optJSONArray("data")
                val out = mutableListOf<String>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        val pricing = item.optJSONObject("pricing")
                        val free = id.endsWith(":free") ||
                            (pricing != null &&
                                pricing.optString("prompt") == "0" &&
                                pricing.optString("completion") == "0")
                        if (id.isNotBlank() && free) out.add(id)
                    }
                }
                out
            }

            "groq" -> {
                requireKey(apiKey)
                val json = getJson("https://api.groq.com/openai/v1/models", bearer)
                idsOf(json.optJSONArray("data"), "id").filterNot {
                    val n = it.lowercase()
                    n.contains("whisper") || n.contains("tts") || n.contains("orpheus") ||
                        n.contains("guard") || n.contains("safeguard")
                }
            }

            "gemini" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                    mapOf("x-goog-api-key" to apiKey)
                )
                val data = json.optJSONArray("models")
                val out = mutableListOf<String>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val name = item.optString("name", "").removePrefix("models/")
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        var canChat = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.optString(j) == "generateContent") canChat = true
                            }
                        }
                        val n = name.lowercase()
                        val excluded = n.contains("tts") || n.contains("image") || n.contains("live") ||
                            n.contains("embedding") || n.contains("robotics") ||
                            n.contains("computer-use") || n.contains("audio")
                        if (canChat && n.startsWith("gemini") && !excluded) out.add(name)
                    }
                }
                out
            }

            "cohere" -> {
                requireKey(apiKey)
                val json = getJson("https://api.cohere.com/v1/models?endpoint=chat&page_size=100", bearer)
                idsOf(json.optJSONArray("models"), "name")
            }

            "cloudflare" -> {
                requireKey(apiKey)
                if (accountId.isBlank()) throw LlmException(400, "أدخل Cloudflare Account ID أولاً")
                val json = getJson(
                    "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search" +
                        "?task=Text%20Generation&per_page=100",
                    bearer
                )
                idsOf(json.optJSONArray("result"), "name")
            }

            "deepseek" -> {
                requireKey(apiKey)
                idsOf(getJson("https://api.deepseek.com/models", bearer).optJSONArray("data"), "id")
            }

            "openai" -> {
                requireKey(apiKey)
                idsOf(getJson("https://api.openai.com/v1/models", bearer).optJSONArray("data"), "id")
                    .filter { it.startsWith("gpt") || it.startsWith("o") }
            }

            "claude" -> {
                requireKey(apiKey)
                val json = getJson(
                    "https://api.anthropic.com/v1/models?limit=100",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                )
                idsOf(json.optJSONArray("data"), "id")
            }

            else -> emptyList()
        }
        return ids.distinct().sorted().take(100)
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (status !in 200..299) throw LlmException(status, errorMessage(json, raw))
            return json ?: throw LlmException(status, "رد غير صالح من المزود")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()

            if (status !in 200..299) {
                throw LlmException(status, errorMessage(json, raw))
            }
            return json ?: throw LlmException(status, "رد غير صالح من المزود")
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(json: JSONObject?, raw: String): String {
        if (json != null) {
            json.optJSONObject("error")
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optString("message", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("error")
                ?.optString("message", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }
        }
        return raw.take(300).ifBlank { "خطأ غير معروف" }
    }
}

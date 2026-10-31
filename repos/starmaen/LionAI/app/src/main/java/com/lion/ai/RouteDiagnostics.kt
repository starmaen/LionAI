package com.lion.ai

import android.content.SharedPreferences

/**
 * فحص المسارات الثلاثة لمزود واحد، ليعرف المستخدم بالضبط أي خيار يعمل:
 * 1) مباشر بمفتاحه  2) عبر خادم الويب  3) عبر الخادم المحلي داخل التطبيق.
 * ثم يبيّن هل العقل المركزي يستطيع استعمال هذا المزود في التلقائي.
 */
object RouteDiagnostics {
    private const val PROBE = "أجب بكلمة واحدة فقط: تم"

    fun report(prefs: SharedPreferences, provider: String, model: String): String {
        val info = providerInfo(provider)
        val label = RoutePolicy.shortName(provider)
        val actualModel = model.trim()
            .ifBlank { RoutePolicy.modelFor(prefs, provider) }
            .ifBlank { info.defaultModel }
        val account = (prefs.getString("cf_account_id", "") ?: "").trim()
        val key = (prefs.getString("api_key_$provider", "") ?: "").trim()
        val gateway = WebGateway.load(prefs)
        val sb = StringBuilder("🔬 فحص المسارات الثلاثة — $label ($actualModel)\n")
        sb.append(if (RoutePolicy.isManual(prefs)) "الوضع: ✋ يدوي" else "الوضع: 🧠 تلقائي (العقل المركزي)")
        sb.append(" • خادم الويب: ").append(WebGateway.modeLabel(gateway.mode)).append("\n\n")

        // 1) مباشر
        val directOk: Boolean
        if (provider == "cfai") {
            directOk = false
            sb.append("1) 🔌 مباشر: — هذا المزود يعمل عبر خادم الويب فقط.\n")
        } else if (key.isBlank() && !info.keyless) {
            directOk = false
            sb.append("1) 🔌 مباشر: ⚠️ لا مفتاح محلي لهذا المزود.\n")
        } else {
            val started = System.currentTimeMillis()
            val reply = runCatching {
                LlmClient.ask(
                    PROBE, key, provider, emptyList(), actualModel, account,
                    readTimeoutMs = 20_000, forceDirect = true
                )
            }.getOrElse { "⚠️ " + (it.message ?: "خطأ") }
            directOk = !LlmClient.isErrorReply(reply)
            sb.append("1) 🔌 مباشر: ").append(if (directOk) "✅ " else "❌ ")
                .append(reply.replace("\n", " ").take(110))
                .append(" (").append(System.currentTimeMillis() - started).append("ms)\n")
        }

        // 2) خادم الويب
        val webOk: Boolean
        if (!gateway.configured || gateway.token.isBlank()) {
            webOk = false
            sb.append("2) 🌐 خادم الويب: ⚠️ أدخل رابط الخادم و RELAY_TOKEN أولاً.\n")
        } else if (!WebGateway.supports(provider)) {
            webOk = false
            sb.append("2) 🌐 خادم الويب: — هذا المزود غير مدعوم عبر الخادم.\n")
        } else {
            val result = WebGateway.testProvider(prefs, provider, actualModel)
            webOk = result.startsWith("✅")
            sb.append("2) 🌐 خادم الويب: ").append(result.replace("\n", " ").take(170)).append('\n')
        }

        // 3) الخادم المحلي
        var localOk = false
        if (LocalAIProxy.getUrl().isBlank()) {
            sb.append("3) 🖥️ الخادم المحلي: ❌ متوقف — أعد فتح التطبيق.\n")
        } else {
            val health = LocalAIProxy.healthCheck()
            val backend = LocalAIProxy.backendProvider(prefs)
            if (backend == provider) {
                val chat = LocalAIProxy.selfTest(prefs)
                localOk = chat.contains("✅")
                sb.append("3) 🖥️ الخادم المحلي: ").append(chat.replace("\n", " ").take(170)).append('\n')
            } else {
                localOk = health.startsWith("✅")
                sb.append("3) 🖥️ الخادم المحلي: ").append(if (localOk) "✅ يعمل" else "❌ لا يستجيب")
                    .append(" — لكنه يوجّه حالياً إلى ").append(RoutePolicy.shortName(backend))
                    .append(" لا إلى ").append(label).append(". اختر هذا المزود في بطاقة الخادم لتجربته عبره.\n")
            }
        }

        val working = listOfNotNull(
            if (directOk) "مباشر" else null,
            if (webOk) "خادم الويب" else null,
            if (localOk && LocalAIProxy.backendProvider(prefs) == provider) "الخادم المحلي" else null
        )
        sb.append("\nالخلاصة: ")
        if (working.isEmpty()) {
            sb.append("❌ لا مسار يعمل لهذا المزود الآن — راجع الأسباب أعلاه (مفتاح أو رابط أو حصة).\n")
        } else {
            sb.append("✅ يعمل عبر: ").append(working.joinToString("، ")).append(".\n")
        }
        if (!directOk && webOk) sb.append("👉 فعّل «خادم أولاً» أو «خادم فقط» ليستعمل التطبيق هذا المزود عبر الخادم.\n")
        if (directOk && !webOk && gateway.mode != WebGateway.MODE_OFF) {
            sb.append("👉 الخادم لا يردّ لهذا المزود؛ «خادم أولاً» سيرجع تلقائياً للمباشر.\n")
        }
        sb.append("🧠 العقل المركزي: ")
            .append(if (SmartRouter.available(prefs, provider)) "هذا المزود مؤهل للتوجيه التلقائي ✅" else "غير مؤهل (لا مفتاح ولا خادم) ⛔")
        return sb.toString().take(2400)
    }
}

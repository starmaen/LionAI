package com.lion.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/**
 * أداة تشخيصية شاملة لدورة الأدوات (End-to-End Tool Round-Trip Self-Test).
 *
 * تختبر المزود المختار (سواء مزود API مباشر/مجاني أو مزود Web عبر جلسة المتصفح) على 4 مراحل متتالية
 * دون أي تعديل أو حذف في مساحة العمل (قراءة فقط 100%):
 * 1) طلب استدعاء أداة قراءة آمنة (`file_tree`) من المزود.
 * 2) تحليل مخرجات المزود عبر [WebToolBridge.normalizeReply] + [AiTools.parseCalls] + [AiTools.validateArgs].
 * 3) تنفيذ الأداة محلياً بوضع قراءة فقط وإدراج رمز إثبات عشوائي وحيد المرة (`LION-PROBE-XXXXXX`).
 * 4) إرسال نتيجة الأداة للمزود والتحقق من استيعابه للنتيجة وإعادته رمز الإثبات نصاً صريحاً دون تسرّب علامات أداة خام.
 */
object ToolSelfTest {

    data class StageResult(
        val id: String,
        val title: String,
        val ok: Boolean,
        val detail: String,
        val durationMs: Long = 0L
    )

    data class Report(
        val provider: String,
        val providerName: String,
        val model: String,
        val isWeb: Boolean,
        val stages: List<StageResult>,
        val totalMs: Long
    ) {
        val ok: Boolean get() = stages.size == 4 && stages.all { it.ok }

        fun summaryLine(): String {
            val passed = stages.count { it.ok }
            return if (ok) {
                "✅ نجح اختبار الأدوات لـ $providerName ($passed/4 مراحل · ${totalMs / 1000.0}ث)"
            } else {
                val failed = stages.firstOrNull { !it.ok }
                "❌ تعذّر اختبار الأدوات لـ $providerName عند «${failed?.title ?: "غير معروف"}»: ${failed?.detail ?: ""}"
            }
        }

        fun detailedText(): String = buildString {
            append(summaryLine()).append('\n')
            if (model.isNotBlank()) append("الموديل: ").append(model).append(" | القناة: ")
                .append(if (isWeb) "جلسة ويب" else "API").append('\n')
            stages.forEachIndexed { idx, s ->
                append(idx + 1).append(". ")
                    .append(if (s.ok) "✅ " else "❌ ")
                    .append(s.title)
                    .append(" — ")
                    .append(s.detail)
                if (s.durationMs > 0L) append(" (").append(s.durationMs).append("ms)")
                append('\n')
            }
        }.trimEnd()
    }

    /** يجب أن يتضمن علامة الوكيل حتى يفعّل [WebToolBridge.plan] بروتوكول الأدوات لمزودات الويب. */
    internal const val PROBE_SYSTEM_PROMPT = """
أنت وكيل برمجة وتطوير داخل Lion AI في وضع تشخيص الأدوات الآمن (قراءة فقط).
لتنفيذ أداة، أخرج كتلة واحدة فقط بالصيغة الحرفية التالية دون أي شرح إضافي قبلها أو بعدها:
[[TOOL]]{"name":"file_tree","args":{"max":5}}[[/TOOL]]
وعندما تصلك «نتائج الأدوات» في الرسالة التالية، لا تستدعِ أي أداة أخرى بل أجب بسطر عربي واحد يتضمن رمز التحقق المذكور في النتيجة حرفياً.
"""

    internal const val TURN1_USER_PROMPT =
        "هذا فحص آلي لدورة الأدوات (قراءة فقط). استدعِ الآن أداة file_tree بالوسائط {\"max\":5} فقط بصيغة [[TOOL]]{\"name\":\"file_tree\",\"args\":{\"max\":5}}[[/TOOL]] دون أي كلام آخر."

    internal fun generateProbeToken(): String =
        "LION-PROBE-" + UUID.randomUUID().toString().replace("-", "").uppercase().take(6)

    /**
     * المحرّك النقي للاختبار الرباعي — معزول عن أندرويد ليكون قابلاً للاختبار المباشر في JUnit.
     */
    internal suspend fun runProbe(
        provider: String,
        providerName: String,
        model: String,
        isWeb: Boolean,
        probeToken: String = generateProbeToken(),
        asker: suspend (turn: Int, systemPrompt: String, message: String, history: List<ChatTurn>) -> String,
        executor: suspend (ToolCall) -> ToolResult
    ): Report {
        val startedAt = System.currentTimeMillis()
        val stages = mutableListOf<StageResult>()

        // ── المرحلة 1: طلب استدعاء الأداة من المزود ──
        val t1Start = System.currentTimeMillis()
        val rawTurn1 = runCatching {
            asker(1, PROBE_SYSTEM_PROMPT.trim(), TURN1_USER_PROMPT, emptyList())
        }.getOrElse { e -> "⚠️ ${e.message ?: e.javaClass.simpleName}" }
        val t1Ms = System.currentTimeMillis() - t1Start

        if (rawTurn1.isBlank() || LlmClient.isErrorReply(rawTurn1)) {
            stages.add(
                StageResult(
                    id = "request",
                    title = "طلب الأداة من المزود",
                    ok = false,
                    detail = rawTurn1.ifBlank { "ردّ فارغ من المزود" }.take(200),
                    durationMs = t1Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        stages.add(
            StageResult(
                id = "request",
                title = "طلب الأداة من المزود",
                ok = true,
                detail = "استجاب المزود (${rawTurn1.length} حرفاً)",
                durationMs = t1Ms
            )
        )

        // ── المرحلة 2: تحليل كتلة الأداة والتحقق من التوقيع ──
        val t2Start = System.currentTimeMillis()
        val normalizedTurn1 = if (isWeb) WebToolBridge.normalizeReply(rawTurn1) else rawTurn1
        val calls = AiTools.parseCalls(normalizedTurn1)
        val targetCall = calls.firstOrNull { it.name in setOf("file_tree", "file_list", "list_dir") }
            ?: calls.firstOrNull()
        val t2Ms = System.currentTimeMillis() - t2Start

        if (targetCall == null) {
            val snippet = ToolMarkup.strip(normalizedTurn1).replace('\n', ' ').trim().take(140)
            stages.add(
                StageResult(
                    id = "parse",
                    title = "تحليل كتلة [[TOOL]]",
                    ok = false,
                    detail = "لم يُخرج المزود كتلة أداة صالحة" +
                        (if (snippet.isNotBlank()) " — ردّ بـ: «$snippet»" else ""),
                    durationMs = t2Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        if (AiTools.isMutation(targetCall)) {
            stages.add(
                StageResult(
                    id = "parse",
                    title = "تحليل كتلة [[TOOL]]",
                    ok = false,
                    detail = "رفض أمني: طلب المزود أداة تعديل (${targetCall.name}) بدلاً من أداة القراءة المطلوبة",
                    durationMs = t2Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        val argError = AiTools.validateArgs(ToolCall(targetCall.name, JSONObject(targetCall.args.toString())))
        if (argError != null) {
            stages.add(
                StageResult(
                    id = "parse",
                    title = "تحليل كتلة [[TOOL]]",
                    ok = false,
                    detail = argError.take(200),
                    durationMs = t2Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        stages.add(
            StageResult(
                id = "parse",
                title = "تحليل كتلة [[TOOL]]",
                ok = true,
                detail = "حُلّلت الأداة «${targetCall.name}» بنجاح (${targetCall.args})",
                durationMs = t2Ms
            )
        )

        // ── المرحلة 3: التنفيذ المحلي الآمن (قراءة فقط) ──
        val t3Start = System.currentTimeMillis()
        val safeArgs = JSONObject(targetCall.args.toString()).apply {
            if (!has("max")) put("max", 5)
        }
        val safeCall = ToolCall(targetCall.name, safeArgs)
        val execResult = runCatching { executor(safeCall) }
            .getOrElse { e -> ToolResult(safeCall.name, "❌ ${e.message ?: e.javaClass.simpleName}", false) }
        val t3Ms = System.currentTimeMillis() - t3Start

        if (!execResult.ok) {
            stages.add(
                StageResult(
                    id = "execute",
                    title = "التنفيذ المحلي الآمن",
                    ok = false,
                    detail = execResult.output.take(200),
                    durationMs = t3Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        stages.add(
            StageResult(
                id = "execute",
                title = "التنفيذ المحلي الآمن",
                ok = true,
                detail = "نُفّذت «${safeCall.name}» بوضع قراءة فقط (${execResult.output.length} حرفاً)",
                durationMs = t3Ms
            )
        )

        // ── المرحلة 4: إكمال الجولة والتحقق من استيعاب النتيجة ──
        // يجب أن تبدأ الرسالة بـ «نتائج الأدوات» ليتعرّف عليها WebToolBridge كرسالة استكمال لنفس الجلسة.
        val observationMessage = buildString {
            append("نتائج الأدوات (بيانات فحص فقط):\n\n")
            append("✅ ").append(safeCall.name).append('\n')
            append(execResult.output.take(1_500)).append('\n')
            append("رمز التحقق الفريد لهذه الجولة: ").append(probeToken).append("\n\n")
            append("أكمل الفحص الآن: لا تستدعِ أي أداة أخرى، وأجب بسطر عربي واحد يذكر عدد الملفات الظاهر ويعيد رمز التحقق «")
                .append(probeToken).append("» حرفياً.")
        }
        val history = listOf(
            ChatTurn("user", TURN1_USER_PROMPT),
            ChatTurn("assistant", normalizedTurn1)
        )
        val t4Start = System.currentTimeMillis()
        val rawTurn2 = runCatching {
            asker(2, PROBE_SYSTEM_PROMPT.trim(), observationMessage, history)
        }.getOrElse { e -> "⚠️ ${e.message ?: e.javaClass.simpleName}" }
        val t4Ms = System.currentTimeMillis() - t4Start

        if (rawTurn2.isBlank() || LlmClient.isErrorReply(rawTurn2)) {
            stages.add(
                StageResult(
                    id = "verify",
                    title = "استيعاب النتيجة وإكمال الجولة",
                    ok = false,
                    detail = rawTurn2.ifBlank { "ردّ فارغ في الجولة الثانية" }.take(200),
                    durationMs = t4Ms
                )
            )
            return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
        }
        val normalizedTurn2 = if (isWeb) WebToolBridge.normalizeReply(rawTurn2) else rawTurn2
        val extraCalls = AiTools.parseCalls(normalizedTurn2)
        val cleanTurn2 = ToolMarkup.strip(normalizedTurn2).trim()
        val hasToken = cleanTurn2.contains(probeToken, ignoreCase = true)
        val hasLeakedMarkup = ToolMarkup.hasMarkup(cleanTurn2)
        when {
            extraCalls.isNotEmpty() && !hasToken -> stages.add(
                StageResult(
                    id = "verify",
                    title = "استيعاب النتيجة وإكمال الجولة",
                    ok = false,
                    detail = "استمر المزود في طلب أداة إضافية (${extraCalls.first().name}) بدلاً من إنهاء الفحص وإعادة رمز التحقق",
                    durationMs = t4Ms
                )
            )
            !hasToken -> stages.add(
                StageResult(
                    id = "verify",
                    title = "استيعاب النتيجة وإكمال الجولة",
                    ok = false,
                    detail = "لم يُعِد المزود رمز الإثبات ($probeToken) مما يدل على عدم قراءة نتيجة الأداة فعلياً — الرد: «${cleanTurn2.take(120)}»",
                    durationMs = t4Ms
                )
            )
            hasLeakedMarkup -> stages.add(
                StageResult(
                    id = "verify",
                    title = "استيعاب النتيجة وإكمال الجولة",
                    ok = false,
                    detail = "بقيت علامات أداة خام عالقة في النص النهائي",
                    durationMs = t4Ms
                )
            )
            else -> stages.add(
                StageResult(
                    id = "verify",
                    title = "استيعاب النتيجة وإكمال الجولة",
                    ok = true,
                    detail = "تأكّد رمز الإثبات ($probeToken) في الرد النهائي: «${cleanTurn2.replace('\n', ' ').take(110)}»",
                    durationMs = t4Ms
                )
            )
        }

        return Report(provider, providerName, model, isWeb, stages, System.currentTimeMillis() - startedAt)
    }

    /**
     * يشغّل الاختبار الحقيقي على أندرويد لأي مزود (API أو Web) ويحفظ التقرير المختصر في الإعدادات.
     */
    suspend fun run(
        context: Context,
        prefs: SharedPreferences,
        provider: String,
        apiKeyOverride: String? = null,
        modelOverride: String? = null,
        accountIdOverride: String? = null,
        onStage: ((String) -> Unit)? = null
    ): Report {
        LlmClient.reloadOverrides(prefs)
        val info = providerInfo(provider)
        val isWeb = WebChatRuntime.handles(provider)
        if (isWeb) {
            prefs.edit().putBoolean("web_chat_enabled_$provider", true).apply()
        }
        val key = (apiKeyOverride ?: prefs.getString("api_key_$provider", "")).orEmpty().trim()
        val account = (accountIdOverride ?: prefs.getString("cf_account_id", "")).orEmpty().trim()
        var model = (modelOverride ?: prefs.getString("model_$provider", "")).orEmpty().trim()
        val isServer = info.custom || provider == "local_proxy" || provider.startsWith(UserPlatforms.PREFIX)
        if (!isWeb && model.isBlank() && isServer) {
            model = runCatching { LlmClient.listModels(provider, key, account) }
                .getOrDefault(emptyList()).firstOrNull().orEmpty()
        }
        if (!isWeb && model.isBlank()) model = info.defaultModel

        val probeChatId = "tool_probe_${provider}_${System.currentTimeMillis() % 100_000}"
        if (isWeb) WebToolBridge.resetPage(provider)

        val toolCtx = ToolContext(
            context = context,
            prefs = prefs,
            allowWrite = false,
            chatId = probeChatId,
            mode = ChatMode.CODE,
            provider = provider,
            model = model,
            apiKey = key,
            accountId = account,
            writeAuthorized = false
        )
        val timeoutMs = if (isWeb) WebChatRuntime.timeoutMs(prefs, provider).toInt() else 60_000

        val report = try {
            runProbe(
                provider = provider,
                providerName = info.shortName,
                model = if (isWeb) "web-session" else model,
                isWeb = isWeb,
                asker = { turn, sys, msg, hist ->
                    onStage?.invoke(
                        if (turn == 1) "⏳ (1/4) أطلب استدعاء الأداة من ${info.shortName}…"
                        else "⏳ (4/4) أتحقق من استيعاب ${info.shortName} لنتيجة الأداة…"
                    )
                    var reply = LlmClient.ask(
                        message = msg,
                        apiKey = key,
                        provider = provider,
                        history = hist,
                        modelOverride = model,
                        accountId = account,
                        systemPrompt = sys,
                        readTimeoutMs = timeoutMs,
                        chatModeHint = ChatMode.CODE,
                        via = if (isWeb) Channel.WEB else Channel.DIRECT,
                        chatId = probeChatId
                    )
                    if (!isWeb && LlmClient.isErrorReply(reply) && WebGateway.shouldRoute(provider)) {
                        val viaGw = LlmClient.ask(
                            message = msg,
                            apiKey = key,
                            provider = provider,
                            history = hist,
                            modelOverride = model,
                            accountId = account,
                            systemPrompt = sys,
                            readTimeoutMs = timeoutMs,
                            chatModeHint = ChatMode.CODE,
                            via = Channel.GATEWAY,
                            chatId = probeChatId
                        )
                        if (!LlmClient.isErrorReply(viaGw)) reply = viaGw
                    }
                    reply
                },
                executor = { call ->
                    onStage?.invoke("⏳ (3/4) أنفّذ «${call.name}» محلياً بوضع قراءة فقط…")
                    AiTools.execute(toolCtx, call)
                }
            )
        } finally {
            if (isWeb) WebToolBridge.resetPage(provider)
        }

        prefs.edit()
            .putString("tool_selftest_$provider", report.summaryLine())
            .putLong("tool_selftest_at_$provider", System.currentTimeMillis())
            .apply()
        return report
    }
}

package com.lion.ai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONTokener
import org.json.JSONObject

/** Activity-owned browser sessions. No JavascriptInterface and no remote cookie/token forwarding. */
internal class AndroidWebSessionHost(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val prefs: SharedPreferences,
    private val display: (Boolean, String) -> Unit
) : WebSessionTransport.Host {
    private val main = Handler(Looper.getMainLooper())
    private val pool = linkedMapOf<String, WebView>()
    private val chatIds = mutableMapOf<String, String>()
    private val loads = mutableMapOf<WebView, WebPageLoad>()
    private var displayedProvider: String? = null
    private val script = activity.assets.open("web-session.js").bufferedReader().use { it.readText() }
    private var pending: Pending? = null
    private var destroyed = false
    private var interactive = false

    private var inputLease: WebView? = null
    private var previousFocus: View? = null

    /** Allow programmatic editor focus without creating an IME connection behind chat. */
    private class SessionWebView(activity: ComponentActivity) : WebView(activity) {
        var manualInput = false
        /** آخر لمسة مستخدم حقيقية (لمسات التطبيق الاصطناعية بلا مصدر شاشة لمس). */
        @Volatile var userTouchAt = 0L
        private fun imeAllowed() = manualInput && SystemClock.uptimeMillis() - userTouchAt < 20_000L
        override fun onCheckIsTextEditor(): Boolean = imeAllowed() && super.onCheckIsTextEditor()
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            if (imeAllowed()) super.onCreateInputConnection(outAttrs) else null
    }

    fun setInteractive(value: Boolean) {
        interactive = value
        container.descendantFocusability = if (value || inputLease != null)
            ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        pool.values.forEach { view ->
            (view as? SessionWebView)?.manualInput = value
            val focusAllowed = value || view === inputLease
            view.isFocusable = focusAllowed
            view.isFocusableInTouchMode = focusAllowed
            view.isClickable = focusAllowed
            view.settings.javaScriptCanOpenWindowsAutomatically = value
            if (!focusAllowed && view.hasFocus()) view.clearFocus()
        }
    }

    private fun acquireEditorFocus(view: WebView) {
        if (interactive || inputLease === view) return
        previousFocus = activity.currentFocus
        inputLease = view
        setInteractive(false)
        view.requestFocus()
    }

    private fun releaseEditorFocus() {
        val leased = inputLease ?: return
        val restore = previousFocus
        val wasFocused = leased.hasFocus()
        inputLease = null
        previousFocus = null
        setInteractive(interactive)
        if (!interactive && wasFocused && restore?.isAttachedToWindow == true) restore.requestFocus()
    }
    /** إخفاء صريح: بعض المواقع تعيد التركيز لحقلها بعد الإرسال فتظهر لوحة المفاتيح فوق الرد. */
    private fun hideKeyboardIfBackground() {
        if (interactive) return
        runCatching {
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(container.windowToken ?: activity.window.decorView.windowToken, 0)
        }
    }

    private data class Pending(
        val request: WebSessionTransport.Request,
        val adapter: WebSiteAdapter,
        val view: WebView,
        val done: (WebSessionTransport.Reply) -> Unit,
        val deadline: Long,
        val chatId: String = ActiveChat.id,
        var stage: String = "probe",
        var submitted: Boolean = false,
        var lastStatus: String = "loading",
        /** Track observed streaming text for an explicit incomplete-response error at timeout. */
        var lastText: String = "",
        /** Compact page diagnostics from the injected script, appended to timeout errors. */
        var lastDiag: String = "",
        var tapped: Boolean = false,
        var enterTried: Boolean = false,
        var lastTextAt: Long = 0L,
        var hiddenTicks: Int = 0
    )
    override fun isUiThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Dispatch a real touch so the page receives a trusted click (JS .click() is often ignored). */
    private fun tapCss(view: WebView, cssX: Double, cssY: Double) {
        if (cssX <= 0.0 || cssY <= 0.0 || view.width <= 0 || view.height <= 0) return
        val scale = view.scale.toDouble().takeIf { it > 0.0 } ?: 1.0
        val x = (cssX * scale).toFloat().coerceIn(1f, (view.width - 1).toFloat())
        val y = (cssY * scale).toFloat().coerceIn(1f, (view.height - 1).toFloat())
        view.requestFocus()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun viewFor(adapter: WebSiteAdapter): WebView {
        displayedProvider = adapter.id
        pool.remove(adapter.id)?.let { pool[adapter.id] = it; show(it); return it }
        while (pool.size >= 3) {
            val entry = pool.entries.first()
            pool.remove(entry.key)
            (entry.value.parent as? FrameLayout)?.removeView(entry.value)
            entry.value.destroy()
            loads.remove(entry.value)
            chatIds.remove(entry.key)
        }
        val view = SessionWebView(activity).apply { manualInput = interactive }
        view.isFocusable = interactive
        view.isFocusableInTouchMode = interactive
        view.isClickable = interactive
        view.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && interactive &&
                ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                (v as? SessionWebView)?.userTouchAt = SystemClock.uptimeMillis()
            }
            !(interactive || v === inputLease)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = mobileUserAgent(WebSettings.getDefaultUserAgent(activity))
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
            }
        } catch (_: Throwable) { }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        val load = WebPageLoad()
        loads[view] = load
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                load.start(url)
                reportLoad(adapter, "جارٍ تحميل ${load.origin}")
            }
            override fun onPageCommitVisible(v: WebView, url: String) {
                load.commit(url, adapter.accepts(url))
                reportCommitted(adapter, load)
            }
            override fun onPageFinished(v: WebView, url: String) {
                load.commit(url, adapter.accepts(url))
                CookieManager.getInstance().flush()
                reportCommitted(adapter, load)
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageFailed(adapter, v, "${describeWebError(error.errorCode)} (رمز ${error.errorCode})")
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageFailed(adapter, v, "رفض تحميل الصفحة: HTTP ${response.statusCode}. افتح الجلسة للتحقق أو أعد تحميلها.")
            }
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Never bypass certificate validation.
                pageFailed(adapter, v, "فشل التحقق من شهادة HTTPS؛ لم يُرسل نص المحادثة.")
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                // Login redirects remain interactive; execution checks the exact top-level origin again.
                return request.url.scheme != "https"
            }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                pending?.takeIf { it.view === v }?.let { fail(it, "توقفت عملية المتصفح. افتح الجلسة مجدداً.") }
                pool.remove(adapter.id)
                loads.remove(v)
                (v.parent as? FrameLayout)?.removeView(v)
                v.destroy()
                return true
            }
        }
        pool[adapter.id] = view
        show(view)
        load.start(adapter.url)
        view.loadUrl(adapter.url)
        chatIds[adapter.id] = ActiveChat.id
        return view
    }
    private fun show(view: WebView) {
        if (view.parent === container) return
        container.removeAllViews()
        (view.parent as? FrameLayout)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.onResume()
        view.resumeTimers()
    }
    private fun reportLoad(adapter: WebSiteAdapter, text: String) {
        prefs.edit().putString("web_session_load_${adapter.id}", text)
            .putString("web_session_status_${adapter.id}", text).apply()
        if (displayedProvider == adapter.id) display(false, text)
    }
    private fun reportCommitted(adapter: WebSiteAdapter, load: WebPageLoad) {
        val message = when (load.phase) {
            WebPageLoad.Phase.READY -> "حُمّلت صفحة ${providerInfo(adapter.id).shortName}؛ لم يُختبر الإرسال بعد"
            WebPageLoad.Phase.REDIRECT -> "تحويل إلى ${load.origin}؛ أكمل الدخول من الجلسة ثم أعد المحاولة"
            WebPageLoad.Phase.FAILED -> load.error
            else -> return
        }
        reportLoad(adapter, message)
    }
    private fun pageFailed(adapter: WebSiteAdapter, view: WebView, message: String) {
        loads[view]?.fail(message)
        reportLoad(adapter, "⚠️ $message")
        prefs.edit().putBoolean("web_session_verified_${adapter.id}", false).apply()
        pending?.takeIf { it.view === view }?.let { fail(it, message) }
    }
    /** Reload only the page; never replay a message and never erase login cookies. */
    fun reloadCurrent() {
        if (pending != null) {
            display(false, "ألغِ الطلب أو انتظر انتهاءه قبل إعادة تحميل الجلسة")
            return
        }
        val id = displayedProvider ?: return
        val adapter = WebSiteAdapters.get(id, prefs) ?: return
        val existing = pool[id]
        if (existing == null) { viewFor(adapter); return }
        val url = existing.url?.takeIf { it.startsWith("https://") } ?: adapter.url
        loads[existing]?.start(url)
        existing.loadUrl(url)
    }
    override fun open(provider: String) {
        main.post {
            if (destroyed || pending != null) return@post
            val adapter = WebSiteAdapters.get(provider, prefs) ?: return@post
            viewFor(adapter)
            display(true, "${providerInfo(provider).shortName} • سجّل الدخول هنا عند الحاجة")
        }
    }
    override fun inspect(provider: String) {
        main.post {
            if (destroyed || pending != null) return@post
            val adapter = WebSiteAdapters.get(provider, prefs) ?: return@post
            // امسح أي تقرير قديم حتى لا يُنسخ بالخطأ قبل اكتمال التقرير الجديد.
            prefs.edit().remove("web_session_dom_$provider").apply()
            val view = viewFor(adapter)
            display(false, "${providerInfo(provider).shortName} • جارٍ تحميل الصفحة قبل التشخيص")
            runInspection(provider, adapter, view, System.currentTimeMillis() + 30_000L)
        }
    }

    /** Diagnose only after the page has really loaded; a fresh WebView is still about:blank. */
    private fun runInspection(provider: String, adapter: WebSiteAdapter, view: WebView, deadline: Long) {
        if (destroyed || pending != null) return
        val phase = loads[view]?.phase
        val settled = phase == WebPageLoad.Phase.READY || phase == WebPageLoad.Phase.REDIRECT ||
            phase == WebPageLoad.Phase.FAILED
        if (!settled && System.currentTimeMillis() < deadline) {
            main.postDelayed({ runInspection(provider, adapter, view, deadline) }, 700)
            return
        }
        run {
            val js = script.replace("__LION_CONFIG__", adapter.json().toString())
                .replace("__LION_TOKEN__", JSONObject.quote("diagnostic"))
                .replace("__LION_ACTION__", JSONObject.quote("diagnose"))
                .replace("__LION_PROMPT__", JSONObject.quote(""))
            view.evaluateJavascript(js) { raw ->
                val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                val diagnostic = runCatching { JSONObject(text ?: "{}") }.getOrElse { JSONObject() }
                diagnostic.put("reportVersion", 2).put("generatedAt", System.currentTimeMillis())
                diagnostic.put("navigation", JSONObject().put("requestedOrigin", WebPageLoad.originOf(view.url.orEmpty()))
                    .put("phase", loads[view]?.phase?.name ?: "UNKNOWN")
                    .put("loadError", loads[view]?.error.orEmpty()))
                diagnostic.put("android", JSONObject().put("width", view.width).put("height", view.height)
                    .put("shown", view.isShown).put("windowFocus", view.hasWindowFocus())
                    .put("focusable", view.isFocusable).put("manual", interactive))
                prefs.edit().putString("web_session_dom_$provider", diagnostic.toString()).apply()
                display(false, "${providerInfo(provider).shortName} • تقرير البنية جاهز للنسخ من إعدادات Lion Chat")
            }
        }
    }
    override fun start(request: WebSessionTransport.Request, done: (WebSessionTransport.Reply) -> Unit) {
        main.post {
            if (destroyed || pending != null) {
                done(WebSessionTransport.Reply("⚠️ جلسة الويب غير متاحة أو مشغولة.", false))
                return@post
            }
            val adapter = WebSiteAdapters.get(request.provider, prefs)
            if (adapter == null) {
                done(WebSessionTransport.Reply("⚠️ لا يوجد موصّل لهذا الموقع.", false))
                return@post
            }
            val view = viewFor(adapter)
            if (request.fresh || chatIds[adapter.id] != ActiveChat.id || loads[view]?.phase == WebPageLoad.Phase.FAILED) {
                // Re-enter the site's initial chat route for each LionAI conversation.
                loads[view]?.start(adapter.url)
                view.loadUrl(adapter.url)
                chatIds[adapter.id] = ActiveChat.id
            }
            val p = Pending(request, adapter, view, done, System.currentTimeMillis() + request.timeoutMs)
            pending = p
            status(p, "جارٍ تجهيز جلسة الموقع؛ لا حاجة لمفتاح API")
            display(false, "${providerInfo(request.provider).shortName} • اتصال بصفحة الويب")
            tick(p)
        }
    }
    private fun status(p: Pending, text: String) {
        prefs.edit().putString("web_session_status_${p.adapter.id}", text)
            .putLong("web_session_checked_${p.adapter.id}", System.currentTimeMillis())
            .putString("web_session_event_chat", p.chatId)
            .putString("web_session_event", "${providerInfo(p.adapter.id).shortName} • $text").apply()
    }
    private fun fail(p: Pending, message: String) {
        finish(p, WebSessionTransport.Reply("⚠️ $message" +
            (if (p.submitted) " قد أُرسلت الرسالة؛ لن تُعاد تلقائياً." else ""), false, p.submitted))
    }
    private fun finish(p: Pending, reply: WebSessionTransport.Reply) {
        if (pending !== p) return
        pending = null
        releaseEditorFocus()
        hideKeyboardIfBackground()
        status(p, if (reply.success) "✅ استُقبل رد جديد من صفحة الموقع" else reply.text)
        if (reply.success) {
            prefs.edit().putBoolean("web_session_verified_${p.adapter.id}", true).apply()
            display(false, "${providerInfo(p.adapter.id).shortName} • وصل الرد من الصفحة")
        } else {
            prefs.edit().putBoolean("web_session_verified_${p.adapter.id}", false).apply()
            display(false, reply.text)
        }
        CookieManager.getInstance().flush()
        p.done(reply)
    }
    private fun tick(p: Pending) {
        if (pending !== p || destroyed) return
        if (!WebChatRuntime.enabled(prefs, p.adapter.id) || !providerEnabled(prefs, p.adapter.id)) {
            fail(p, "تم تعطيل المزود.")
            return
        }
        if (System.currentTimeMillis() >= p.deadline) {
            // Send already happened and a new answer was captured — return it rather
            // than dropping a reply that is sitting on the page.
            if (p.submitted && p.lastText.isNotBlank() &&
                p.lastStatus in setOf("streaming", "waiting", "sent", "complete")
            ) {
                finish(p, WebSessionTransport.Reply(p.lastText.trim(), true, true))
                return
            }
            val message = when (p.lastStatus) {
                "needs_input", "origin", "loading" -> "لم يظهر حقل الدردشة. أكمل تسجيل الدخول/التحقق يدوياً أو حدّث محدد حقل الإدخال."
                "needs_send" -> "لم يُعثر على زر إرسال جاهز. حدّث محدد زر الإرسال."
                "awaiting_ack", "clicked" -> "نُقر الإرسال لكن الموقع لم يؤكد استقبال الرسالة. لم أعِد الإرسال." +
                    (if (p.lastDiag.isNotBlank()) " [" + p.lastDiag + "]" else "")
                "streaming", "waiting" -> "لم يتم تأكيد اكتمال جواب جديد. افتح الجلسة وتأكد أن الموقع أجاب ولا يطلب تحققاً." +
                    (if (p.lastDiag.isNotBlank()) " [" + p.lastDiag + "]" else "")
                else -> "انتهت مهلة الموقع (${p.lastStatus})."
            }
            fail(p, message)
            return
        }
        val load = loads[p.view]
        when (load?.phase) {
            WebPageLoad.Phase.FAILED -> { fail(p, load.error); return }
            WebPageLoad.Phase.REDIRECT -> {
                fail(p, "الصفحة الحالية ${load.origin} ليست صفحة الدردشة؛ أكمل تسجيل الدخول يدويًا من الجلسة.")
                return
            }
            WebPageLoad.Phase.READY -> Unit
            else -> {
                p.lastStatus = "loading"
                if (load?.loadExpired(System.currentTimeMillis()) == true) {
                    fail(p, "لم يكتمل تحميل صفحة الموقع خلال 30 ثانية. افتح الجلسة واضغط تحديث؛ لم يُرسل النص.")
                } else main.postDelayed({ tick(p) }, 700)
                return
            }
        }
        if (!p.adapter.accepts(p.view.url.orEmpty())) {
            fail(p, "تغير عنوان الصفحة إلى ${WebPageLoad.originOf(p.view.url.orEmpty())}. لم أكتب الرسالة خارج الموقع المحدد.")
            return
        }
        val js = script.replace("__LION_CONFIG__", p.adapter.json().toString())
            .replace("__LION_TOKEN__", JSONObject.quote(p.request.token))
            .replace("__LION_ACTION__", JSONObject.quote(p.stage))
            .replace("__LION_PROMPT__", JSONObject.quote(p.request.prompt))
        if (p.stage == "prepare" || p.stage == "submit" || p.stage == "locate" || p.stage == "enter") {
            acquireEditorFocus(p.view)
        }
        p.view.evaluateJavascript(js) { raw ->
            if (pending !== p || destroyed) return@evaluateJavascript
            val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val result = runCatching { JSONObject(decoded ?: "{}") }.getOrNull()
            if (result?.optString("token") != p.request.token) {
                p.lastStatus = "loading"
            } else {
                val state = result.optString("status")
                p.lastStatus = state
                result.optString("diag").takeIf { it.isNotBlank() }?.let { p.lastDiag = it }
                when (state) {
                    "origin" -> {
                        val actual = result.optString("actualOrigin", "unknown")
                        prefs.edit().putString("web_session_actual_origin_${p.adapter.id}", actual).apply()
                        fail(p, "الوثيقة الحالية $actual لا تطابق موقع الدردشة. افتح الجلسة وأعد تحميلها؛ لم تُرسل الرسالة.")
                        return@evaluateJavascript
                    }
                    "ready" -> p.stage = "prepare"
                    "prepared" -> {
                        p.stage = "locate"
                        status(p, "وصل النص إلى حقل الموقع؛ جارٍ الإرسال")
                    }
                    "located" -> {
                        p.submitted = true
                        if (!p.tapped) {
                            p.tapped = true
                            tapCss(p.view, result.optDouble("x"), result.optDouble("y"))
                        }
                        p.stage = "poll"
                        releaseEditorFocus()
                        status(p, "نُقر زر الإرسال من التطبيق؛ ننتظر تأكيد الموقع")
                    }
                    "entered" -> {
                        p.submitted = true
                        p.enterTried = true
                        p.stage = "poll"
                        releaseEditorFocus()
                        status(p, "أُرسل Enter إلى حقل الموقع؛ ننتظر تأكيد الاستقبال")
                    }
                    "needs_send" -> {
                        p.submitted = false
                        when {
                            p.stage == "locate" && !p.enterTried -> p.stage = "enter"
                            p.stage == "enter" && !p.tapped -> p.stage = "submit"
                            else -> Unit
                        }
                    }
                    "clicked" -> {
                        p.submitted = true
                        p.stage = "poll"
                        releaseEditorFocus()
                        status(p, "نُقر زر الإرسال؛ ننتظر تأكيد الموقع، لا إعادة إرسال")
                    }
                    "awaiting_ack" -> status(p, "لم يؤكد الموقع استقبال الرسالة بعد")
                    "send_unconfirmed" -> {
                        fail(p, "تمت محاولة النقر لكن الموقع لم يؤكد الإرسال خلال 45 ثانية. لم أعِد المحاولة ولم أستخدم API.")
                        return@evaluateJavascript
                    }
                    "site_busy" -> {
                        p.submitted = false
                        fail(p, "الموقع يولّد ردًا لطلب سابق. أوقفه أو انتظر انتهاؤه داخل الجلسة قبل إرسال طلب جديد.")
                        return@evaluateJavascript
                    }
                    "sent" -> {
                        p.submitted = true
                        p.stage = "poll"
                        releaseEditorFocus()
                        status(p, "أُرسلت الرسالة إلى الصفحة؛ بانتظار جواب جديد")
                    }
                    "streaming" -> {
                        val streamed = result.optString("text")
                        if (streamed.isNotBlank()) {
                            if (streamed != p.lastText) {
                                p.lastText = streamed
                                p.lastTextAt = System.currentTimeMillis()
                            }
                            // Live-return: the in-app chat shows this while the page finishes.
                            status(p, streamed.take(3500))
                        }
                    }
                    "complete" -> {
                        val text = result.optString("text").trim()
                        if (text.isNotBlank()) finish(p, WebSessionTransport.Reply(text, true, true))
                        else fail(p, "عاد جواب فارغ.")
                        return@evaluateJavascript
                    }
                    "input_rejected" -> {
                        p.submitted = false
                        fail(p, "لم يقبل محرر الموقع النص. افتح الجلسة ثم تشخيص الصفحة وأرسل التقرير؛ لم تُرسل الرسالة.")
                        return@evaluateJavascript
                    }
                    "input_disabled" -> {
                        p.submitted = false
                        fail(p, "حقل الموقع معطّل أو للقراءة فقط. أكمل تسجيل الدخول أو انتظر انتهاء الطلب السابق.")
                        return@evaluateJavascript
                    }
                    "draft", "draft_changed" -> {
                        p.submitted = false
                        fail(p, "يوجد نص في حقل الموقع. لم أستبدله. امسحه أو أرسله يدويًا ثم أعد المحاولة.")
                        return@evaluateJavascript
                    }
                    "page_hidden" -> {
                        // Just re-attached or backgrounded: wake the renderer and wait for visibility, don't type blind.
                        p.hiddenTicks++
                        p.view.onResume()
                        p.view.resumeTimers()
                        p.view.requestLayout()
                        status(p, "الصفحة ما زالت مخفية؛ أنتظر ظهورها قبل الكتابة")
                        if (p.hiddenTicks > 40) {
                            fail(p, "بقيت صفحة الموقع مخفية فلم أكتب فيها. افتح الجلسة من الإعدادات ثم أعد المحاولة.")
                            return@evaluateJavascript
                        }
                    }
                    "site_error" -> {
                        fail(p, "أعاد الموقع رسالة خطأ بدل الجواب: ${result.optString("text")}")
                        return@evaluateJavascript
                    }
                    "selector_error" -> {
                        fail(p, "محدد CSS غير صالح: ${result.optString("error")}")
                        return@evaluateJavascript
                    }
                    "lost", "not_sent" -> {
                        fail(p, "تغيّرت الصفحة وفُقد سياق الطلب. لم أكرر الإرسال.")
                        return@evaluateJavascript
                    }
                }
            }
            val delayMs = when (p.stage) {
                "poll" -> 220L
                "prepare", "locate", "enter", "submit" -> 280L
                else -> 450L
            }
            main.postDelayed({ tick(p) }, delayMs)
        }
    }
    override fun cancel(token: String) {
        main.post {
            pending?.takeIf { it.request.token == token }?.let { fail(it, "أُلغي الطلب.") }
        }
    }
    fun cancelCurrent() { pending?.let { fail(it, "أُلغي الانتظار. يمكنك إيقاف التوليد من الصفحة.") } }
    fun destroy() {
        destroyed = true
        pending?.let { fail(it, "أُغلقت واجهة التطبيق.") }
        main.removeCallbacksAndMessages(null)
        container.removeAllViews()
        pool.values.forEach { it.stopLoading(); it.destroy() }
        pool.clear()
        loads.clear()
        WebSessionTransport.detach(this)
    }
}

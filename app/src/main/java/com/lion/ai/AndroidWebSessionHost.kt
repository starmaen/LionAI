package com.lion.ai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
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
    private val script = activity.assets.open("web-session.js").bufferedReader().use { it.readText() }
    private var pending: Pending? = null
    private var destroyed = false
    private data class Pending(
        val request: WebSessionTransport.Request,
        val adapter: WebSiteAdapter,
        val view: WebView,
        val done: (WebSessionTransport.Reply) -> Unit,
        val deadline: Long,
        var stage: String = "probe",
        var submitted: Boolean = false,
        var lastStatus: String = "loading"
    )
    override fun isUiThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    @SuppressLint("SetJavaScriptEnabled")
    private fun viewFor(adapter: WebSiteAdapter): WebView {
        pool.remove(adapter.id)?.let { pool[adapter.id] = it; show(it); return it }
        while (pool.size >= 3) {
            val entry = pool.entries.first()
            pool.remove(entry.key)
            (entry.value.parent as? FrameLayout)?.removeView(entry.value)
            entry.value.destroy()
            chatIds.remove(entry.key)
        }
        val view = WebView(activity)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = mobileUserAgent(WebSettings.getDefaultUserAgent(activity))
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                // Login redirects remain interactive; execution checks the exact top-level origin again.
                return request.url.scheme != "https"
            }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                pending?.takeIf { it.view === v }?.let { fail(it, "توقفت عملية المتصفح. افتح الجلسة مجدداً.") }
                pool.remove(adapter.id)
                (v.parent as? FrameLayout)?.removeView(v)
                v.destroy()
                return true
            }
        }
        pool[adapter.id] = view
        show(view)
        view.loadUrl(adapter.url)
        chatIds[adapter.id] = ActiveChat.id
        return view
    }
    private fun show(view: WebView) {
        container.removeAllViews()
        (view.parent as? FrameLayout)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
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
            val view = viewFor(adapter)
            if (!adapter.accepts(view.url.orEmpty())) {
                display(true, "انتظر تحميل الجلسة ثم اضغط التشخيص مجدداً")
                return@post
            }
            val js = script.replace("__LION_CONFIG__", adapter.json().toString())
                .replace("__LION_TOKEN__", JSONObject.quote("diagnostic"))
                .replace("__LION_ACTION__", JSONObject.quote("diagnose"))
                .replace("__LION_PROMPT__", JSONObject.quote(""))
            view.evaluateJavascript(js) { raw ->
                val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                prefs.edit().putString("web_session_dom_$provider", text ?: "تعذّر فحص بنية الصفحة").apply()
                display(true, "${providerInfo(provider).shortName} • تقرير البنية جاهز للنسخ من إعدادات Lion Chat")
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
            if (chatIds[adapter.id] != ActiveChat.id) {
                // Re-enter the site's initial chat route for each LionAI conversation.
                view.loadUrl(adapter.url)
                chatIds[adapter.id] = ActiveChat.id
            }
            val p = Pending(request, adapter, view, done, System.currentTimeMillis() + request.timeoutMs)
            pending = p
            status(p, "جارٍ تجهيز جلسة الموقع؛ لا حاجة لمفتاح API")
            display(true, "${providerInfo(request.provider).shortName} • اتصال بصفحة الويب")
            tick(p)
        }
    }
    private fun status(p: Pending, text: String) {
        prefs.edit().putString("web_session_status_${p.adapter.id}", text)
            .putLong("web_session_checked_${p.adapter.id}", System.currentTimeMillis()).apply()
    }
    private fun fail(p: Pending, message: String) {
        finish(p, WebSessionTransport.Reply("⚠️ $message" +
            (if (p.submitted) " قد أُرسلت الرسالة؛ لن تُعاد تلقائياً." else ""), false, p.submitted))
    }
    private fun finish(p: Pending, reply: WebSessionTransport.Reply) {
        if (pending !== p) return
        pending = null
        status(p, if (reply.success) "✅ استُقبل رد جديد من صفحة الموقع" else reply.text)
        if (reply.success) {
            prefs.edit().putBoolean("web_session_verified_${p.adapter.id}", true).apply()
            display(false, "${providerInfo(p.adapter.id).shortName} • وصل الرد من الصفحة")
        } else {
            prefs.edit().putBoolean("web_session_verified_${p.adapter.id}", false).apply()
            display(true, reply.text)
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
            val message = when (p.lastStatus) {
                "needs_input", "origin", "loading" -> "لم يظهر حقل الدردشة. أكمل تسجيل الدخول/التحقق يدوياً أو حدّث محدد حقل الإدخال."
                "needs_send" -> "لم يُعثر على زر إرسال جاهز. حدّث محدد زر الإرسال."
                "streaming", "waiting" -> "لم يتم تأكيد اكتمال جواب جديد. افحص محدد الجواب وأزرار الإيقاف/النسخ."
                else -> "انتهت مهلة الموقع (${p.lastStatus})."
            }
            fail(p, message)
            return
        }
        if (!p.adapter.accepts(p.view.url.orEmpty())) {
            p.lastStatus = "origin"
            main.postDelayed({ tick(p) }, 700)
            return
        }
        val js = script.replace("__LION_CONFIG__", p.adapter.json().toString())
            .replace("__LION_TOKEN__", JSONObject.quote(p.request.token))
            .replace("__LION_ACTION__", JSONObject.quote(p.stage))
            .replace("__LION_PROMPT__", JSONObject.quote(p.request.prompt))
        // Mark possible delivery before evaluating: a navigation can lose the callback after click.
        if (p.stage == "submit") p.submitted = true
        p.view.evaluateJavascript(js) { raw ->
            if (pending !== p || destroyed) return@evaluateJavascript
            val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val result = runCatching { JSONObject(decoded ?: "{}") }.getOrNull()
            if (result?.optString("token") != p.request.token) {
                p.lastStatus = "loading"
            } else {
                val state = result.optString("status")
                p.lastStatus = state
                when (state) {
                    "ready" -> p.stage = "prepare"
                    "prepared" -> p.stage = "submit"
                    "needs_send" -> p.submitted = false
                    "sent" -> {
                        p.submitted = true
                        p.stage = "poll"
                        status(p, "أُرسلت الرسالة إلى الصفحة؛ بانتظار جواب جديد")
                    }
                    "complete" -> {
                        val text = result.optString("text").trim()
                        if (text.isNotBlank()) finish(p, WebSessionTransport.Reply(text, true, true))
                        else fail(p, "عاد جواب فارغ.")
                        return@evaluateJavascript
                    }
                    "draft", "draft_changed" -> {
                        fail(p, "يوجد نص في حقل الموقع. لم أستبدله. امسحه أو أرسله يدويًا ثم أعد المحاولة.")
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
            main.postDelayed({ tick(p) }, 700)
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
        WebSessionTransport.detach(this)
    }
}

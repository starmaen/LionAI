package com.lion.ai

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * المتصفح المدمج لدردشات الويب (Gemini وQwen وDeepSeek وCopilot وArena وغيرها).
 */
private val WEB_BG = Color(0xFF121212)
private val WEB_BAR = Color(0xFF171717)
private val WEB_ACCENT = Color(0xFFFFB800)
private val WEB_MUTED = Color(0xFF9A9A9A)

internal fun mobileUserAgent(base: String): String =
    base.replace("; wv", "").replace("Version/4.0 ", "")

/** وكيل سطح المكتب بإصدار يطابق محرك WebView الفعلي (لا رقم ثابت قديم). */
internal fun desktopMajor(base: String): Int =
    Regex("Chrome/(\\d+)").find(base)?.groupValues?.get(1)?.toIntOrNull() ?: 138

internal fun desktopUserAgent(base: String = ""): String {
    val major = desktopMajor(base)
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
}

/** يطابق ترويسات Client Hints مع وكيل Windows كي لا تتناقض مع «Android/mobile». */
internal fun applyDesktopClientHints(settings: WebSettings, major: Int) {
    try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, v: String) = androidx.webkit.UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(v).setFullVersion("$v.0.0.0").build()
            val meta = androidx.webkit.UserAgentMetadata.Builder()
                .setBrandVersionList(listOf(brand("Microsoft Edge", "$major"), brand("Chromium", "$major"), brand("Not.A/Brand", "99")))
                .setFullVersion("$major.0.0.0")
                .setPlatform("Windows").setPlatformVersion("10.0.0")
                .setArchitecture("x86").setBitness(64).setModel("").setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, meta)
        }
    } catch (_: Throwable) { }
}

internal fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .trim()
}

internal fun isWebUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

internal fun isInternalScheme(url: String): Boolean =
    url.startsWith("blob:", ignoreCase = true) ||
        url.startsWith("data:", ignoreCase = true) ||
        url.startsWith("about:", ignoreCase = true)

private val FALLBACK_PARAM = Regex("S\\.browser_fallback_url=([^;]+)")

internal fun intentFallbackUrl(url: String): String? {
    if (!url.startsWith("intent:", ignoreCase = true)) return null
    val raw = FALLBACK_PARAM.find(url)?.groupValues?.getOrNull(1) ?: return null
    val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
    return decoded.takeIf { it.startsWith("https://", ignoreCase = true) }
}

/** صفحات تثبيت التطبيق: Copilot وغيره يحوّل المتصفح المحمول إليها بدل فتح الدردشة. */
internal fun isAppStoreUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    if (u.startsWith("market:") || u.startsWith("itms-apps:") || u.startsWith("itms-appss:")) return true
    if (!isWebUrl(u)) return false
    val host = hostOf(u).substringBefore(':')
    return host == "play.google.com" || host == "apps.apple.com" || host == "appgallery.huawei.com"
}

internal fun describeWebError(code: Int): String = when (code) {
    WebViewClient.ERROR_HOST_LOOKUP -> "تعذّر العثور على عنوان الموقع."
    WebViewClient.ERROR_CONNECT -> "تعذّر الاتصال بالخادم."
    WebViewClient.ERROR_TIMEOUT -> "انتهت مهلة الاتصال."
    WebViewClient.ERROR_BAD_URL -> "عنوان الصفحة غير صالح."
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "فشل التحقق من شهادة الأمان."
    WebViewClient.ERROR_TOO_MANY_REQUESTS -> "طلبات كثيرة جداً من الموقع."
    WebViewClient.ERROR_FILE_NOT_FOUND -> "الصفحة أو الملف غير موجود."
    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "نوع الرابط غير مدعوم."
    else -> "تعذّر تحميل الصفحة."
}

internal class LionWebController(
    private val activity: ComponentActivity,
    val startUrl: String
) {
    private val baseUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    private var restoreBundle: Bundle? = null
    private var view: WebView? = null
    private var rendererGone = false

    var launchFileChooser: ((Intent) -> Unit)? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    var progress by mutableIntStateOf(0)
        private set
    var pageTitle by mutableStateOf("")
        private set
    var pageUrl by mutableStateOf(startUrl)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val isLoading: Boolean get() = progress in 1..99

    fun setRestoreState(bundle: Bundle?) {
        restoreBundle = bundle
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun attach(webView: WebView) {
        view = webView
        rendererGone = false
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            userAgentString = if (startUrl.contains("copilot")) desktopUserAgent(baseUserAgent) else mobileUserAgent(baseUserAgent)
            if (startUrl.contains("copilot")) applyDesktopClientHints(this, desktopMajor(baseUserAgent))
        }
        hideRequestedWithHeader(webView.settings)
        webView.webViewClient = lionClient
        webView.webChromeClient = lionChrome
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(url) }

        val bundle = restoreBundle
        restoreBundle = null
        if (bundle != null && webView.restoreState(bundle) != null) {
            pageUrl = webView.url ?: startUrl
        } else {
            errorMessage = null
            webView.loadUrl(startUrl)
        }
    }

    private fun hideRequestedWithHeader(settings: WebSettings) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        } catch (_: Throwable) {}
    }

    fun detach() { view = null }
    fun saveState(outState: Bundle) { runCatching { view?.saveState(outState) } }
    fun destroy() { runCatching { view?.destroy() }; view = null }

    fun navigateBack(): Boolean {
        val v = view ?: return false
        return if (v.canGoBack()) { v.goBack(); true } else false
    }

    fun reload() {
        errorMessage = null
        if (rendererGone) { activity.recreate(); return }
        view?.reload()
    }

    fun stopLoading() { view?.stopLoading() }
    fun openInBrowser() { openExternal(view?.url ?: startUrl) }

    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private fun openExternal(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
            true
        } catch (_: ActivityNotFoundException) { false }
        catch (_: Exception) { false }
    }

    private val lionClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString().orEmpty()
            // لا نترك الموقع يحوّلنا إلى متجر التطبيقات بدل صفحة الدردشة.
            if (isAppStoreUrl(url)) return true
            if (isWebUrl(url) || isInternalScheme(url)) return false
            intentFallbackUrl(url)?.let {
                view.loadUrl(it)
                return true
            }
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            errorMessage = null
            pageUrl = url
            injectCompatibilityFixes(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            injectCompatibilityFixes(view)
            pageUrl = view.url ?: url
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            if (error.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return
            errorMessage = describeWebError(error.errorCode)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            rendererGone = true
            errorMessage = "توقفت الصفحة بسبب نقص الذاكرة. اضغط إعادة المحاولة."
            return true
        }
    }

    private val lionChrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
        }

        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            val intent = runCatching { params.createIntent() }.getOrNull()
                ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            val launcher = launchFileChooser
            return if (launcher != null) {
                runCatching { launcher(intent) }.isSuccess
            } else {
                filePathCallback = null
                false
            }
        }
    }

    private fun injectCompatibilityFixes(webView: WebView) {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                } catch (e) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }
}

@Composable
internal fun LionWebScreen(controller: LionWebController, onClose: () -> Unit) {
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Column(modifier = Modifier.fillMaxSize().background(WEB_BG).windowInsetsPadding(safeInsets)) {
        LionWebBar(controller = controller, onClose = onClose)
        Box(modifier = Modifier.fillMaxWidth().weight(1f).imePadding()) {
            AndroidView(factory = { ctx -> WebView(ctx).also { controller.attach(it) } }, modifier = Modifier.fillMaxSize())
            if (controller.isLoading) {
                LinearProgressIndicator(
                    progress = { controller.progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = WEB_ACCENT,
                    trackColor = Color.Transparent
                )
            }
            val err = controller.errorMessage
            if (err != null) {
                Surface(modifier = Modifier.fillMaxSize(), color = WEB_BG) {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "⚠️", fontSize = 48.sp)
                        Text(text = err, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = WEB_ACCENT, modifier = Modifier.clickable { controller.reload() }) {
                            Text(text = "إعادة المحاولة", color = Color.Black, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LionWebBar(controller: LionWebController, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(56.dp), color = WEB_BAR) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF262626), modifier = Modifier.clickable { onClose() }) {
                Text(text = "✕", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = controller.pageTitle.ifBlank { hostOf(controller.pageUrl) }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = hostOf(controller.pageUrl), color = WEB_MUTED, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF262626), modifier = Modifier.clickable { controller.reload() }) {
                Text(text = "↻", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF262626), modifier = Modifier.clickable { controller.openInBrowser() }.padding(start = 6.dp)) {
                Text(text = "↗", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

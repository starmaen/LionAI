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
 *
 * أسباب الصفحات الفارغة أو المقتطعة في النسخة السابقة وإصلاحها:
 *  1. `useWideViewPort=false` كان يتجاهل وسم viewport فتُرسم المواقع المتجاوبة بعرض خاطئ ومقتطعة:
 *     فعّلناه مع overview ليعمل التصميم المتجاوب كما في كروم.
 *  2. `setSupportMultipleWindows(true)` مع إعادة استعمال نفس الـ WebView كنافذة جديدة كان يستبدل الصفحة
 *     الحالية بنافذة فارغة عند أي `window.open` (تسجيل الدخول وغيره): الآن تُفتح الروابط في نفس العرض.
 *  3. ترويسة `X-Requested-With` كانت تفضح التطبيق فتحجب Google وغيرها الدخول داخل WebView: تُحذف هنا.
 *  4. أزرار التنقل وتبديل الجوال/سطح المكتب أُزيلت؛ بقي إغلاق وتحديث فقط، والرجوع يتم بزر النظام.
 */

private val WEB_BG = Color(0xFF121212)
private val WEB_BAR = Color(0xFF171717)
private val WEB_ACCENT = Color(0xFFFFB800)
private val WEB_MUTED = Color(0xFF9A9A9A)

/** وكيل الجوال: نحذف وسم `; wv` حتى تعاملنا المواقع كمتصفح عادي لا كـ WebView. */
internal fun mobileUserAgent(base: String): String =
    base.replace("; wv", "").replace("Version/4.0 ", "")

/** اسم المضيف للعرض في الشريط العلوي (بدون استخدام Uri حتى يبقى قابلاً للاختبار). */
internal fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .trim()
}

/** الروابط التي يفتحها الـ WebView داخلياً؛ ما عداها يُسلَّم لتطبيق خارجي. */
internal fun isWebUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** رسائل الخطأ بعربية واضحة بدل شاشة سوداء فارغة. */
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

/**
 * المتحكّم في الـ WebView: يحفظ الحالة التي تعرضها الواجهة. يُنشأ مرة واحدة في النشاط
 * ويبقى محتفظاً بالصفحة عند تغيّر الاتجاه.
 */
internal class LionWebController(
    private val activity: ComponentActivity,
    val startUrl: String
) {

    private val baseUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    private var restoreBundle: Bundle? = null
    private var view: WebView? = null
    private var rendererGone = false

    /** يُضبط من النشاط لتشغيل منتقي الملفات. */
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
            // نافذة واحدة: window.open يفتح داخل نفس العرض بدل أن يستبدله بنافذة فارغة.
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // يحترم وسم viewport فيعمل التصميم المتجاوب للمواقع كما في المتصفح العادي.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            userAgentString = mobileUserAgent(baseUserAgent)
        }
        hideRequestedWithHeader(webView.settings)
        webView.webViewClient = lionClient
        webView.webChromeClient = lionChrome

        // التنزيلات (صور، ملفات) تُسلَّم للتطبيق المناسب بدل أن تختفي بصمت.
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

    /** يحذف ترويسة X-Requested-With التي تكشف التطبيق للمواقع (تحجب Google الدخول بسببها). */
    private fun hideRequestedWithHeader(settings: WebSettings) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        } catch (_: Throwable) {
            // إصدار WebView قديم: نكمل بدون هذه الميزة.
        }
    }

    fun detach() {
        view = null
    }

    fun saveState(outState: Bundle) {
        runCatching { view?.saveState(outState) }
    }

    fun destroy() {
        runCatching { view?.destroy() }
        view = null
    }

    /** رجوع داخل الصفحة؛ ترجع true إن استُهلك الضغط. */
    fun navigateBack(): Boolean {
        val v = view ?: return false
        return if (v.canGoBack()) {
            v.goBack()
            true
        } else {
            false
        }
    }

    fun reload() {
        errorMessage = null
        if (rendererGone) {
            activity.recreate()
            return
        }
        view?.reload()
    }

    fun stopLoading() {
        view?.stopLoading()
    }

    fun openInBrowser() {
        openExternal(view?.url ?: startUrl)
    }

    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    /** يفتح الروابط غير القابلة للعرض داخل التطبيق (mailto/tel/intent/market/…). */
    private fun openExternal(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private val lionClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url?.toString().orEmpty()
            return if (isWebUrl(url)) false else openExternal(url)
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

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            errorMessage = describeWebError(error.errorCode)
        }

        // انهيار عملية العرض (نقص ذاكرة) كان يقتل التطبيق كله؛ الآن نعرض رسالة ونعيد التشغيل بنقرة.
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

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
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

    /** إخفاء أثر الأتمتة عن المواقع التي ترفض الـ WebView. لا نلمس أي حشو أو هوامش داخل الصفحة. */
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
internal fun LionWebScreen(
    controller: LionWebController,
    onClose: () -> Unit
) {
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WEB_BG)
            .windowInsetsPadding(safeInsets)
    ) {
        LionWebBar(controller = controller, onClose = onClose)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // المساحة الوحيدة التي تتأثر بلوحة المفاتيح: الصفحة نفسها.
                .imePadding()
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).also { controller.attach(it) }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    controller.detach()
                    runCatching { webView.destroy() }
                }
            )
            if (controller.isLoading) {
                LinearProgressIndicator(
                    progress = { controller.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = WEB_ACCENT,
                    trackColor = Color(0xFF2A2A2A)
                )
            }
            controller.errorMessage?.let { message ->
                WebErrorPanel(
                    message = message,
                    onRetry = { controller.reload() },
                    onOpenBrowser = { controller.openInBrowser() }
                )
            }
        }
    }
}

/** شريط بسيط: إغلاق، عنوان الصفحة، تحديث. لا أسهم تنقل ولا تبديل جوال/سطح مكتب. */
@Composable
private fun LionWebBar(
    controller: LionWebController,
    onClose: () -> Unit
) {
    Surface(color = WEB_BAR) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WebBarButton("✕", onClick = onClose)
            val title = controller.pageTitle.ifBlank { hostOf(controller.pageUrl) }
            Text(
                text = title,
                color = WEB_MUTED,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            if (controller.isLoading) {
                WebBarButton("■") { controller.stopLoading() }
            } else {
                WebBarButton("⟳") { controller.reload() }
            }
        }
    }
}

@Composable
private fun WebBarButton(
    glyph: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = 18.sp,
            color = WEB_ACCENT,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WebErrorPanel(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WEB_BG),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .background(Color(0xFF1B1B1B), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("⚠️", fontSize = 26.sp)
            Text(
                text = message,
                color = Color(0xFFE6E6E6),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "إعادة المحاولة",
                color = WEB_ACCENT,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onRetry() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                text = "فتح في المتصفح",
                color = WEB_MUTED,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onOpenBrowser() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

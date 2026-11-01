package com.lion.ai

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
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

/**
 * المتصفح المدمج في التطبيق لدردشات الويب (Arena وGemini وDeepSeek وChatGPT وغيرها).
 *
 * سبب المشكلة السابقة: الـ WebView كان يملأ الشاشة بالكامل دون أي حساب للحواف،
 * فصار صندوق الكتابة داخل الصفحة مخفياً تحت شريط التنقل ولوحة المفاتيح من الأسفل،
 * وجزء من أعلى الصفحة مخفياً تحت شريط الحالة. الحل هنا:
 *   1. الشاشة كلها داخل `systemBars ∪ displayCutout` فلا يمرّ أي محتوى تحت System UI.
 *   2. مساحة الصفحة وحدها تأخذ `imePadding()` فتتقلّص تلقائياً عند ظهور لوحة المفاتيح
 *      ويبقى صندوق الكتابة ظاهراً فوقها.
 *   3. لم نعد نصفّر `padding-bottom` في صفحة الويب بسكربت (كان يهدم هوامش الأمان).
 */

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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
 * المتحكّم في الـ WebView: يحفظ الحالة التي تعرضها الواجهة ويعرض أوامر التنقل.
 * يُنشأ مرة واحدة في النشاط ويبقى محتفظاً بالصفحة عند تغيّر الاتجاه.
 */
internal class LionWebController(
    private val activity: ComponentActivity,
    val startUrl: String
) {

    private var baseUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    private var restoreBundle: Bundle? = null
    private var view: WebView? = null

    /** يُضبط من النشاط لتشغيل منتقي الملفات. */
    var launchFileChooser: ((Intent) -> Unit)? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    var progress by mutableIntStateOf(0)
        private set
    var pageTitle by mutableStateOf("")
        private set
    var pageUrl by mutableStateOf(startUrl)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var desktopMode by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val isLoading: Boolean get() = progress in 1..99

    fun setRestoreState(bundle: Bundle?) {
        restoreBundle = bundle
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        view = webView
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            // بعض الدردشات تحمّل أجزاءً عبر http؛ نبقي السلوك المتساهل كما كان.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
        }
        applyMode(webView)
        webView.webViewClient = lionClient
        webView.webChromeClient = lionChrome

        // التنزيلات (صور، ملفات) تُسلَّم للتطبيق المناسب بدل أن تختفي بصمت.
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(url) }

        val bundle = restoreBundle
        restoreBundle = null
        if (bundle != null) {
            webView.restoreState(bundle)
            sync()
        } else {
            errorMessage = null
            webView.loadUrl(startUrl)
        }
    }

    fun detach() {
        view = null
    }

    fun saveState(outState: Bundle) {
        view?.saveState(outState)
    }

    fun destroy() {
        view?.destroy()
        view = null
    }

    /** رجوع داخل الصفحة؛ ترجع true إن استُهلك الضغط. */
    fun navigateBack(): Boolean {
        val v = view ?: return false
        return if (v.canGoBack()) {
            v.goBack()
            sync()
            true
        } else {
            false
        }
    }

    fun goForward() {
        val v = view ?: return
        if (v.canGoForward()) {
            v.goForward()
            sync()
        }
    }

    fun reload() {
        errorMessage = null
        view?.reload()
    }

    fun stopLoading() {
        view?.stopLoading()
    }

    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val v = view ?: return
        applyMode(v)
        v.reload()
    }

    fun openInBrowser() {
        openExternal(view?.url ?: startUrl)
    }

    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private fun applyMode(webView: WebView) {
        webView.settings.apply {
            // وضع سطح المكتب يوسّع العرض ثم يصغّره ليناسب شاشة الهاتف؛
            // وضع الجوال يترك الموقع يقرر تصميمه المتجاوب بنفسه.
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
            userAgentString = if (desktopMode) DESKTOP_UA else mobileUserAgent(baseUserAgent)
            textZoom = 100
        }
    }

    private fun sync() {
        val v = view ?: return
        canGoBack = v.canGoBack()
        canGoForward = v.canGoForward()
        pageUrl = v.url ?: pageUrl
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
            sync()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            errorMessage = describeWebError(error.errorCode)
        }
    }

    private val lionChrome = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            sync()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
            sync()
        }

        // الروابط التي تطلب نافذة جديدة (`target="_blank"`) تُفتح في العرض نفسه
        // بدل أن تُتجاهل بصمت كما كان يحدث سابقاً.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            transport.webView = view
            resultMsg.sendToTarget()
            return true
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

    /**
     * إخفاء أثر الـ WebView عن المواقع التي ترفضه. لا نلمس أي حشو أو هوامش داخل
     * الصفحة: ضبط الحواف يتم من جانب الواجهة (Compose) لا من جانب الصفحة.
     */
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
                WebErrorPanel(message = message, onRetry = { controller.reload() })
            }
        }
    }
}

@Composable
private fun LionWebBar(
    controller: LionWebController,
    onClose: () -> Unit
) {
    Surface(color = WEB_BAR) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WebBarButton("✕", "إغلاق", onClick = onClose)
                WebBarButton("⟵", "السابق", enabled = controller.canGoBack) { controller.navigateBack() }
                WebBarButton("⟶", "التالي", enabled = controller.canGoForward) { controller.goForward() }
                if (controller.isLoading) {
                    WebBarButton("■", "إيقاف التحميل") { controller.stopLoading() }
                } else {
                    WebBarButton("⟳", "تحديث") { controller.reload() }
                }
                WebBarButton(
                    glyph = if (controller.desktopMode) "📱" else "🖥",
                    description = if (controller.desktopMode) {
                        "الوضع: سطح المكتب — اضغط للتبديل إلى الجوال"
                    } else {
                        "الوضع: جوال — اضغط للتبديل إلى سطح المكتب"
                    }
                ) { controller.toggleDesktopMode() }
                WebBarButton("↗", "فتح في المتصفح") { controller.openInBrowser() }
            }
            val title = controller.pageTitle.ifBlank { hostOf(controller.pageUrl) }
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    color = WEB_MUTED,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun WebBarButton(
    glyph: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .padding(2.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = 17.sp,
            color = if (enabled) WEB_ACCENT else Color(0xFF4A4A4A),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WebErrorPanel(
    message: String,
    onRetry: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        }
    }
}

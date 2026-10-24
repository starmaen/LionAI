package com.lion.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class ArenaActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RESULT_CODE = 1001

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // الشريط العلوي (Top Control Bar)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }

        val titleView = TextView(this).apply {
            text = "🦁 Arena AI"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val bypassBtn = Button(this).apply {
            text = "🛡️ تخطي النافذة"
            setTextColor(Color.parseColor("#0D0D0D"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#FFB800"))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { marginEnd = dp(6) }
            setOnClickListener { dismissTermsModal() }
        }

        val reloadBtn = Button(this).apply {
            text = "🔄"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                dp(38),
                dp(32)
            ).apply { marginEnd = dp(6) }
            setOnClickListener { webView.reload() }
        }

        val closeBtn = Button(this).apply {
            text = "✖"
            setTextColor(Color.parseColor("#E94560"))
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(32))
            setOnClickListener { finish() }
        }

        topBar.addView(titleView)
        topBar.addView(bypassBtn)
        topBar.addView(reloadBtn)
        topBar.addView(closeBtn)

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
            )
            max = 100
        }

        contentContainer.addView(webView)
        contentContainer.addView(progressBar)

        mainColumn.addView(topBar)
        mainColumn.addView(contentContainer)
        setContentView(mainColumn)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectStealthScript()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                injectStealthScript()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            // دعم رفع الصور والملفات داخل محادثة Arena
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@ArenaActivity.filePathCallback?.onReceiveValue(null)
                this@ArenaActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE)
                    true
                } catch (e: Exception) {
                    this@ArenaActivity.filePathCallback = null
                    false
                }
            }
        }

        val targetUrl = intent.getStringExtra("ARENA_URL")
            ?: "https://arena.ai/text/direct?model_a=max"
        webView.loadUrl(targetUrl)
    }

    private fun injectStealthScript() {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => false });
                    if (navigator.userAgentData) {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: () => ({
                                brands: [
                                    { brand: 'Chromium', version: '130' },
                                    { brand: 'Google Chrome', version: '130' },
                                    { brand: 'Not?A_Brand', version: '99' }
                                ],
                                mobile: true,
                                platform: 'Android'
                            })
                        });
                    }
                    if (!window.__lionFetchPatched) {
                        window.__lionFetchPatched = true;
                        const origFetch = window.fetch;
                        window.fetch = async function(...args) {
                            const url = (args[0] && args[0].url) ? args[0].url : String(args[0] || '');
                            const res = await origFetch.apply(this, args);
                            if (!res.ok && (url.includes('terms') || url.includes('consent') || url.includes('policy'))) {
                                setTimeout(window.__lionDismissModal, 150);
                                return new Response(JSON.stringify({ ok: true, success: true, accepted: true }), {
                                    status: 200,
                                    headers: { 'Content-Type': 'application/json' }
                                });
                            }
                            return res;
                        };
                    }
                    window.__lionDismissModal = function() {
                        document.querySelectorAll('[role="dialog"], [role="alertdialog"], [data-state="open"]').forEach(el => {
                            if (el.innerText && (el.innerText.includes('Terms of Use') || el.innerText.includes('Agree'))) {
                                el.remove();
                            }
                        });
                        document.querySelectorAll('div.fixed.inset-0, div[data-aria-hidden="true"]').forEach(el => {
                            if (getComputedStyle(el).zIndex >= 40 && !el.querySelector('textarea')) {
                                el.remove();
                            }
                        });
                        document.querySelectorAll('*').forEach(el => {
                            if (el.innerText && el.innerText.includes('Failed to accept terms-of-use') && el.tagName === 'LI') {
                                el.remove();
                            }
                        });
                        document.body.style.pointerEvents = 'auto';
                        document.body.style.overflow = 'auto';
                        document.documentElement.style.overflow = 'auto';
                    };
                    document.addEventListener('click', function(e) {
                        const t = e.target;
                        if (t && t.innerText && t.innerText.trim() === 'Agree') {
                            setTimeout(window.__lionDismissModal, 700);
                        }
                    }, true);
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun dismissTermsModal() {
        injectStealthScript()
        webView.evaluateJavascript("if (window.__lionDismissModal) window.__lionDismissModal();", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}

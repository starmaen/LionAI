package com.lion.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import android.widget.FrameLayout
import android.widget.ProgressBar

class ArenaActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RESULT_CODE = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
                6,
                Gravity.TOP
            )
            max = 100
        }

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        setContentView(rootLayout)

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
                injectBypassScript()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                injectBypassScript()
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

    private fun injectBypassScript() {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => false });
                    
                    // دالة فورية لإزالة نافذة الشروط وأي خطأ أحمر
                    window.__lionRemoveModal = function() {
                        const dialogs = document.querySelectorAll('[role="dialog"], [role="alertdialog"], div.fixed.inset-0');
                        dialogs.forEach(el => {
                            if (el.innerText && (el.innerText.includes('Terms of Use') || el.innerText.includes('Agree') || el.innerText.includes('Failed to accept'))) {
                                el.remove();
                            }
                        });
                        // إزالة أي تراكب رمادي يمنع اللمس
                        const overlays = document.querySelectorAll('div[data-state="open"], div.bg-black\\/50');
                        overlays.forEach(el => {
                            if (!el.querySelector('textarea')) {
                                el.remove();
                            }
                        });
                        document.body.style.pointerEvents = 'auto';
                        document.body.style.overflow = 'auto';
                    };

                    // مراقبة مستمرة لظهور النافذة وحذفها فوراً
                    if (!window.__lionObserverStarted) {
                        window.__lionObserverStarted = true;
                        const observer = new MutationObserver((mutations) => {
                            window.__lionRemoveModal();
                        });
                        observer.observe(document.body, { childList: true, subtree: true });
                    }

                    // محاولة حذفها كل جزء من الثانية إن وجدت
                    setInterval(window.__lionRemoveModal, 200);

                    // اعتراض زر Agree لمحاكاة النجاح وإخفاء النافذة قسراً
                    document.addEventListener('click', function(e) {
                        const t = e.target;
                        if (t && (t.innerText === 'Agree' || t.id.includes('agree'))) {
                            setTimeout(window.__lionRemoveModal, 100);
                        }
                    }, true);

                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

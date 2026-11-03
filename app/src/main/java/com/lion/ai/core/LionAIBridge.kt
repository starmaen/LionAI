package com.lion.ai.core
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class LionAIBridge(
    private val context: Context,
    private val providerId: String,
    private val onResponse: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    @JavascriptInterface fun onWebResponse(text: String) {
        Log.d("LionBridge", "[$providerId] ${text.length}")
        if (text.length > 10) onResponse(text)
    }
    @JavascriptInterface fun onWebError(err: String) { onError(err) }
    @JavascriptInterface fun log(msg: String) { Log.d("LionBridge", msg) }
    @JavascriptInterface fun readFile(path: String): String {
        return try {
            if (!WebProvidersPrefs.isFilesEnabled(context)) return "FILES_DISABLED"
            val f = java.io.File(path)
            if (!f.exists()) "NOT_FOUND:$path" else f.readText().take(80000)
        } catch (e: Exception) { "ERR:${e.message}" }
    }
    @JavascriptInterface fun writeFile(path: String, content: String): String {
        return try {
            if (!WebProvidersPrefs.isFilesEnabled(context)) return "FILES_DISABLED"
            val f = java.io.File(path); f.parentFile?.mkdirs(); f.writeText(content); "OK:${f.absolutePath}"
        } catch (e: Exception) { "ERR:${e.message}" }
    }
    @JavascriptInterface fun getDevToolsStatus(): String = "{\"files\":${WebProvidersPrefs.isFilesEnabled(context)},\"github\":${WebProvidersPrefs.isGitHubEnabled(context)},\"email\":${WebProvidersPrefs.isEmailEnabled(context)}}"

    companion object {
        fun setupWebViewWithBrain(webView: WebView, provider: WebProvider, bridge: LionAIBridge): WebView {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.allowFileAccess = true
            webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 LionAI"
            webView.addJavascriptInterface(bridge, "Android")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val js = """
                        (function(){
                            if(window._lionInjected) return; window._lionInjected=true;
                            var last='';
                            setInterval(function(){
                                try{
                                    var sels=["[data-message-author-role='assistant']",".markdown",".prose",".model-response","article"];
                                    var best='';
                                    for(var i=0;i<sels.length;i++){
                                        var els=document.querySelectorAll(sels[i]);
                                        if(els.length>0){ var t=els[els.length-1].innerText||''; if(t.length>best.length&&t.length>20) best=t; }
                                    }
                                    if(best && best!==last && best.length>20){ last=best; Android.onWebResponse(best); }
                                }catch(e){}
                            },2000);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }
            webView.loadUrl(provider.url)
            return webView
        }
        fun sendPrompt(webView: WebView, prompt: String) {
            val safe = prompt.replace("\"","\\\"")
            val js = """
                (function(){
                    var p="$safe";
                    var inputs=document.querySelectorAll('textarea, [contenteditable=true]');
                    var inp=inputs.length>0?inputs[inputs.length-1]:null;
                    if(!inp) return;
                    inp.focus();
                    if(inp.tagName==='TEXTAREA'){ inp.value=p; inp.dispatchEvent(new Event('input',{bubbles:true})); }
                    else { inp.textContent=p; inp.dispatchEvent(new Event('input',{bubbles:true})); }
                    setTimeout(function(){
                        var btns=document.querySelectorAll('button[type=submit], button');
                        var b=btns.length>0?btns[btns.length-1]:null;
                        if(b) b.click();
                    },600);
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }
}

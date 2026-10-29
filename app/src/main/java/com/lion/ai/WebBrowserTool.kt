package com.lion.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
/**
 * مستعرض ويب خفيف يعمل داخل التطبيق عبر HTTP.
 * يجلب صفحة ويب ويستخرج النص المرئي منها (ي supprim الوسوم HTML والنصوص غير المرئية)
 * لتمكين مزودي الذكاء الاصطناعي من القراءة والتصفح والبحث ضمن بيئة التطبيق.
 */
object WebBrowserTool {

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; LionAI) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    /** يجلب محتوى خام (JSON/نص) دون استخراج النص المرئي. */
    fun fetchRaw(url: String, maxChars: Int = 200_000): String {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                "⚠️ تعذّر تحميل الصفحة ($status): $url"
            } else {
                String(connection.inputStream.readBytes(), StandardCharsets.UTF_8).take(maxChars)
            }
        } catch (e: Exception) {
            "⚠️ تعذّر التصفح: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** يجلب صفحة ويب ويستخرج نصها المرئي. */
    fun fetch(url: String, maxChars: Int = 12_000): String {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ar,en;q=0.9")
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                "⚠️ تعذّر تحميل الصفحة ($status): $url"
            } else {
                val html = String(connection.inputStream.readBytes(), StandardCharsets.UTF_8)
                extractText(html).take(maxChars)
            }
        } catch (e: Exception) {
            "⚠️ تعذّر التصفح: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** يبحث في DuckDuckGo HTML ويستخرج نتائج البحث. */
    fun search(query: String, maxResults: Int = 5): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encoded"
        return try {
            val connection = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,*/*")
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                "⚠️ تعذّر البحث ($status)"
            } else {
                val html = connection.inputStream.bufferedReader(java.nio.charset.Charset.defaultCharset()).use { it.readText() }
                extractSearchResults(html, maxResults)
            }
        } catch (e: Exception) {
            "⚠️ تعذّر البحث: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** يستخرج النص المرئي من HTML بحذف الوسوم والنصوص المخفية. */
    private fun extractText(html: String): String {
        var text = html
        // إزالة السكريبت والأنماط
        text = Regex("(?s)<script.*?</script>").replace(text, " ")
        text = Regex("(?s)<style.*?</style>").replace(text, " ")
        // إزالة التعليقات
        text = Regex("(?s)<!--.*?-->").replace(text, " ")
        // تحويل سطور الفاصلة والفقرات إلى أسطر جديدة
        text = Regex("(?i)</(p|div|h[1-6]|li|tr|br|section|article|header|footer|blockquote)>").replace(text, "\n")
        // إزالة جميع الوسوم المتبقية
        text = Regex("<[^>]+>").replace(text, " ")
        // فك ترميز كيانات HTML بسيطة
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&copy;", "©").replace("&reg;", "®")
        // تقليص المسافات والأسطر الفارغة
        text = Regex("[ \t]+").replace(text, " ")
        text = Regex("\n[ \t]*\n+").replace(text, "\n")
        return text.trim()
    }

    /** يستخرج نتائج البحث من HTML DuckDuckGo. */
    private fun extractSearchResults(html: String, maxResults: Int): String {
        val results = mutableListOf<String>()
        val resultPattern = Regex("(?s)<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\">(.*?)</a>")
        val snippetPattern = Regex("(?s)<a class=\"result__snippet\".*?>(.*?)</a>")

        val matches = resultPattern.findAll(html)
        var count = 0
        for (match in matches) {
            if (count >= maxResults) break
            val rawUrl = match.groupValues[1]
            val rawTitle = match.groupValues[2]
            val title = Regex("<[^>]+>").replace(rawTitle, " ").trim()
            val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            // ابحث عن المختصر في نفس الكتلة
            val snippet = snippetPattern.find(html)?.groupValues?.get(1)?.let {
                Regex("<[^>]+>").replace(it, " ").trim()
            }.orEmpty()

            results.add("• $title\n  $url${if (snippet.isNotEmpty()) "\n  $snippet" else ""}")
            count++
        }

        return if (results.isEmpty()) "لم تظهر نتائج بحث مرئية." else results.joinToString("\n\n")
    }
}

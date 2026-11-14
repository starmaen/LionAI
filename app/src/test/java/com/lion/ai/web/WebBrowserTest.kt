package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات للمنطق الصرف في المتصفح المدمج (بلا Android). */
class WebBrowserTest {

    @Test
    fun `روابط الويب تبقى داخل المتصفح المدمج`() {
        assertTrue(isWebUrl("https://chat.deepseek.com"))
        assertTrue(isWebUrl("HTTP://gemini.google.com/app"))
        assertFalse(isWebUrl("mailto:a@b.com"))
        assertFalse(isWebUrl("tel:+963900000000"))
        assertFalse(isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isWebUrl("market://details?id=com.lion.ai"))
    }

    @Test
    fun `اسم المضيف يُستخلص للعرض في الشريط`() {
        assertEquals("chat.deepseek.com", hostOf("https://chat.deepseek.com/a/chat/s/123?x=1"))
        assertEquals("gemini.google.com", hostOf("https://gemini.google.com/app#chat"))
        assertEquals("arena.ai", hostOf("https://arena.ai"))
        assertEquals("", hostOf(""))
    }

    @Test
    fun `وسم WebView يُحذف من وكيل الجوال`() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36; wv"
        val cleaned = mobileUserAgent(raw)
        assertFalse(cleaned.contains("; wv"))
        assertFalse(cleaned.contains("Version/4.0 "))
        assertTrue(cleaned.contains("Android 14"))
    }

    @Test
    fun `رسائل الخطأ بالعربية ومفهومة`() {
        assertEquals("تعذّر العثور على عنوان الموقع.", describeWebError(-2))
        assertEquals("انتهت مهلة الاتصال.", describeWebError(-8))
        assertEquals("فشل التحقق من شهادة الأمان.", describeWebError(-11))
        assertTrue(describeWebError(-9999).isNotBlank())
    }
}

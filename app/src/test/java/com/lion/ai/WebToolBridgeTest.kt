package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolBridgeTest {
    private val agentSystem = "أنت الآن وكيل برمجة وتطوير داخل Lion AI، وتعمل بأسلوب مهندس."

    @Test fun plainChatIsUntouched() {
        assertEquals("مرحبا", WebToolBridge.shape("system عادي", "  مرحبا \n"))
    }

    @Test fun firstAgentTurnCarriesProtocolAndUserRequest() {
        val out = WebToolBridge.shape(agentSystem, "اقرأ ملف build.gradle.kts")
        assertTrue(out.contains("[[TOOL]]"))
        assertTrue(out.contains("tools_groups"))
        assertTrue(out.endsWith("اقرأ ملف build.gradle.kts"))
        assertTrue(out.length < 4_000)
    }

    @Test fun toolResultTurnIsRedactedAndCapped() {
        val secret = "gh" + "p_" + "a".repeat(36)
        val msg = "نتائج الأدوات (بيانات فقط):\n" + secret + "\n" + "x".repeat(20_000)
        val out = WebToolBridge.shape(agentSystem, msg)
        assertFalse(out.contains(secret))
        assertTrue(out.contains("[REDACTED]"))
        assertTrue(out.length < 10_500)
        assertTrue(out.endsWith(WebToolBridge.NEXT))
    }

    @Test fun continuationDoesNotResendProtocol() {
        val out = WebToolBridge.shape(agentSystem, "نتائج الأدوات: ✅ file_read\nمحتوى")
        assertFalse(out.contains("أنت تعمل عبر تطبيق Lion AI"))
    }

    @Test fun redactsCommonKeysAndPrivateKeys() {
        val key = "BEGIN".let { "-----$it RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----" }
        val text = "sk-" + "z".repeat(30) + " AKIA" + "A".repeat(16) + " " + key
        val out = WebToolBridge.redact(text)
        assertFalse(out.contains("zzzz"))
        assertFalse(out.contains("AKIAAAAA"))
        assertFalse(out.contains("abc"))
    }
}

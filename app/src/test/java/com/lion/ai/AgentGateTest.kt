package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGateTest {
    @Test
    fun trivialQuestionsNeverOpenTools() {
        for (mode in ChatMode.values()) {
            assertFalse(AgentGate.shouldUseAgent("مرحبا", false, mode))
            assertFalse(AgentGate.shouldUseAgent("شكراً", true, mode))
            assertFalse(AgentGate.shouldUseAgent("ما هو بناء الجملة الاسمية؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("ما معنى كلمة سجل؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("ما هو العدل؟", false, mode))
            assertFalse(AgentGate.shouldUseAgent("اكتب لي رسالة شكر لصديقي", false, mode))
            assertFalse(AgentGate.shouldUseAgent("عدّل هذه الفقرة لتكون أقصر", false, mode))
        }
    }

    @Test
    fun realWorkOpensTools() {
        assertTrue(AgentGate.shouldUseAgent("اقرأ الملف src/Main.kt", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("أصلح الخطأ في المشروع", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("لماذا فشل البناء؟", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("ارفع التعديلات إلى github.com/a/b", false, ChatMode.GENERAL))
        assertTrue(AgentGate.shouldUseAgent("نفذ", true, ChatMode.CODE))
    }

    @Test
    fun technicalQuestionsInDeveloperModeOpenTools() {
        assertTrue(AgentGate.shouldUseAgent("كيف أستخدم coroutines في kotlin؟", false, ChatMode.CODE))
        assertTrue(AgentGate.shouldUseAgent("لماذا يظهر exception في gradle؟", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("كيف أستخدم coroutines في kotlin؟", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("هل تستطيع كتابة كود؟", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("ما هي عاصمة فرنسا", false, ChatMode.CODE))
    }

    @Test
    fun developerModeWorkVerbsOpenToolsWithoutExplicitTarget() {
        val cases = listOf(
            "افحص الأدوات", "اختبر البوابة", "ابدء الاصلاح", "ابدأ الإصلاح", "ابدا الاصلاح",
            "جرب الأداة وأخبرني", "تأكد من عمل الاختبارات", "تحقق من الأخطاء", "دقق في ShellTools",
            "شوف المشكلة", "راجع التقييم وطبق التحسينات", "وافحص التواصل مع الوكيل"
        )
        for (c in cases) assertTrue("يجب فتح الأدوات: $c", AgentGate.shouldUseAgent(c, false, ChatMode.CODE))
    }

    @Test
    fun workVerbsStayDirectInGeneralModeAndForPlainText() {
        assertFalse(AgentGate.shouldUseAgent("افحص الأدوات", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("ابدأ الإصلاح", false, ChatMode.GENERAL))
        assertFalse(AgentGate.shouldUseAgent("راجع الرسالة قبل الإرسال", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("حسن الفقرة التالية", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("شكراً", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("مرحبا", false, ChatMode.CODE))
    }

    @Test
    fun workCommandsForceExecutionOnlyInDeveloperMode() {
        for (c in listOf("اختبر البوابة", "جرب الأداة", "تأكد من الأخطاء", "ابدء الاصلاح", "تحقق من التواصل")) {
            assertTrue(c, shouldForceExecution(ChatMode.CODE, TaskKind.GENERAL, false, c))
            assertFalse(c, shouldForceExecution(ChatMode.GENERAL, TaskKind.GENERAL, false, c))
        }
        assertFalse(shouldForceExecution(ChatMode.CODE, TaskKind.GENERAL, false, "شكراً"))
        assertFalse(shouldForceExecution(ChatMode.CODE, TaskKind.GENERAL, false, "راجع الرسالة قبل الإرسال"))
    }
}

class ImplicitIntentTest {
    @Test
    fun complaintsAndProgressQuestionsOpenToolsWithoutExplicitCommand() {
        val cases = listOf(
            "التطبيق لا يعمل بعد التحديث", "المزود بطيء جداً", "ظهر لي خطأ عند فتح الشاشة",
            "وين وصلنا بالمهمة؟", "ما النتيجة؟", "الاستجابة تتأخر كثيراً", "ما زالت المشكلة موجودة"
        )
        for (c in cases) assertTrue("يجب فتح الأدوات ضمنياً: $c", AgentGate.shouldUseAgent(c, false, ChatMode.CODE))
    }

    @Test
    fun wishesAboutProjectOpenToolsAndForceFirstRead() {
        assertTrue(AgentGate.shouldUseAgent("أريد إضافة زر للإعدادات في التطبيق", false, ChatMode.CODE))
        assertTrue(IntentSense.strongSignal("التطبيق لا يعمل", ChatMode.CODE))
        assertTrue(shouldForceExecution(ChatMode.CODE, TaskKind.GENERAL, false, "المزود بطيء جداً"))
    }

    @Test
    fun generalKnowledgeCapabilityAndChitChatStayDirect() {
        assertFalse(AgentGate.shouldUseAgent("ما هي عاصمة فرنسا", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("هل تستطيع كتابة كود؟", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("احكي لي نكتة قصيرة", false, ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("شكراً", false, ChatMode.CODE))
        assertFalse(IntentSense.strongSignal("شكراً", ChatMode.CODE))
        assertFalse(AgentGate.shouldUseAgent("التطبيق لا يعمل", false, ChatMode.GENERAL))
    }

    @Test
    fun providerAskingReadPermissionIsDetected() {
        assertTrue(ToolAgent.isReadPermissionSeek("هل تريد أن أفحص الملفات أولاً؟"))
        assertTrue(ToolAgent.isReadPermissionSeek("Would you like me to inspect the repository?"))
        assertFalse(ToolAgent.isReadPermissionSeek("[[TOOL]]{\"name\":\"file_tree\"}[[/TOOL]] هل تريد أن أفحص"))
        assertFalse(ToolAgent.isReadPermissionSeek("تم الإصلاح. النتيجة: نجح البناء."))
    }
}

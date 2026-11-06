package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class UserIntentTest {
    @Test fun capabilityQuestionWithoutQuestionMarkIsAnsweredDirectly() {
        // الحالة الحقيقية من التطبيق: لا علامة ؟ وبعدها قائمة قدرات عامة.
        assertEquals(UserIntent.Kind.CAPABILITY, UserIntent.classify("هل بامكانك انشاء مجلدات وملفات وضغطها وقراءة ملف pdf"))
        assertEquals(UserIntent.Kind.CAPABILITY, UserIntent.classify("هل بإمكانك إنشاء مجلدات وملفات؟"))
        assertEquals(UserIntent.Kind.CAPABILITY, UserIntent.classify("هل تستطيع قراءة ملف؟"))
        assertEquals(UserIntent.Kind.CAPABILITY, UserIntent.classify("can you create a folder"))
        assertFalse(shouldUseAgentForPrompt("هل بامكانك انشاء مجلدات وملفات وضغطها وقراءة ملف pdf", false, ChatMode.GENERAL))
        assertFalse(shouldUseAgentForPrompt("هل بامكانك انشاء مجلدات وملفات وضغطها وقراءة ملف pdf", false, ChatMode.CODE))
    }

    @Test fun politeRequestOnASpecificTargetIsARequest() {
        assertEquals(UserIntent.Kind.REQUEST, UserIntent.classify("هل يمكنك إصلاح الملف Main.kt"))
        assertEquals(UserIntent.Kind.REQUEST, UserIntent.classify("هل يمكنك أن تصلح هذا الخطأ"))
        assertEquals(UserIntent.Kind.REQUEST, UserIntent.classify("ممكن تعدل app/src/Main.kt"))
    }

    @Test fun knowledgeQuestionsAndCommandsAreSeparated() {
        assertEquals(UserIntent.Kind.QUESTION, UserIntent.classify("ما هي عاصمة فرنسا"))
        assertEquals(UserIntent.Kind.QUESTION, UserIntent.classify("اشرح لي الفرق بين TCP وUDP"))
        assertEquals(UserIntent.Kind.QUESTION, UserIntent.classify("how does zip compression work"))
        assertEquals(UserIntent.Kind.REQUEST, UserIntent.classify("أنشئ ملف notes.txt"))
        assertEquals(UserIntent.Kind.REQUEST, UserIntent.classify("اضغط المجلد إلى zip"))
    }

    @Test fun capabilityQuestionNeverPromotesToDeveloperMode() {
        assertFalse(ModeGuard.looksLikeCodingRequest("هل بامكانك انشاء مجلدات وملفات وضغطها وقراءة ملف pdf", TaskKind.GENERAL))
    }
}

class CardGateTest {
    @Test fun questionsNeverOpenTheDecisionCardButTasksStillCan() {
        // أسئلة وقدرات: جواب مباشر، لا بطاقة قرار/إذن ولا دفع لاستدعاء أداة.
        assertTrue(ToolAgent.answerOnly("هل بامكانك انشاء مجلدات وملفات وضغطها وقراءة ملف pdf", false))
        assertTrue(ToolAgent.answerOnly("ما هي عاصمة فرنسا", false))
        assertTrue(ToolAgent.answerOnly("لماذا فشل البناء؟", false))
        // أوامر تنفيذية: البطاقة تعمل كما هي.
        assertFalse(ToolAgent.answerOnly("أنشئ ملف notes.txt", false))
        assertFalse(ToolAgent.answerOnly("هل يمكنك إصلاح الملف Main.kt", false))
        // استئناف مهمة قائمة (مثل «نعم» أو «نفذ» بعد سؤال) لا يُعامل كسؤال أبداً.
        assertFalse(ToolAgent.answerOnly("ما رأيك؟", true))
        assertFalse(ToolAgent.answerOnly("نفذ", false))
    }
}

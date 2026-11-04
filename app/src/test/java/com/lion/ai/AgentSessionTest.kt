package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionTest {

    @Test
    fun recordsToolsAndFilesForResume() {
        val session = AgentSession("chat_test")
        session.start("أصلح ملف Main.kt وارفع التعديل", "Claude")
        session.recordTool("file_read", true, "قرأت الملف", "src/Main.kt")
        session.recordTool("file_write", true, "كتبت الملف", "src/Main.kt")

        assertTrue(session.hasWork())
        assertTrue(session.touchedFiles().contains("src/Main.kt"))
        val context = session.contextBlock()
        assertTrue(context.contains("file_write"))
        assertTrue(context.contains("src/Main.kt"))
        assertTrue(context.contains("Claude"))
    }

    @Test
    fun pendingQuestionIsAnsweredAndKept() {
        val session = AgentSession("chat_q")
        session.start("أضف شاشة إعدادات", "Gemini")
        session.recordTool("file_tree", true, "شجرة الملفات")
        session.askQuestion("هل أنشئ الشاشة في ملف جديد أم أعدّل الموجود؟")
        assertTrue(session.contextBlock().contains("سؤال معلّق"))

        session.start("أنشئها في ملف جديد", "DeepSeek")
        assertNull(session.pendingQuestion)
        val context = session.contextBlock()
        assertTrue(context.contains("إجابة المستخدم"))
        assertTrue(context.contains("DeepSeek"))
    }

    @Test
    fun incompleteSessionSurvivesANewWording() {
        val session = AgentSession("chat_new")
        session.start("اصلح مشكلة البناء", "Claude")
        session.recordTool("github_wait_run", false, "فشل البناء")

        session.start("استخدم الفرع dev ثم أعد المحاولة", "DeepSeek")
        assertTrue(session.hasWork())
        assertTrue(session.contextBlock().contains("المهمة الأصلية"))
        assertTrue(session.contextBlock().contains("DeepSeek"))
    }

    @Test
    fun unfinishedWorkResumesWithoutMagicWord() {
        val session = AgentSession("chat_follow")
        session.beginTurn("أصلح البناء")
        session.recordTool("file_tree", true, "شجرة")
        session.beginTurn("استخدم الفرع dev ثم أعد المحاولة")
        assertTrue(session.continuity.resume)
        assertTrue(session.hasWork())
    }

    @Test
    fun explicitNewTaskDoesNotResumeReceipts() {
        val session = AgentSession("chat_newtask")
        session.beginTurn("أصلح البناء")
        session.recordTool("file_tree", true, "شجرة")
        session.beginTurn("مهمة جديدة")
        assertFalse(session.continuity.resume)
    }

    @Test
    fun readAliasesDoNotBecomeModifiedProjectFiles() {
        val session = AgentSession("chat_reads")
        session.recordTool("read_file", true, "contents", "src/Main.kt")
        session.recordTool("list_dir", true, "files", "src")
        session.recordTool("write_file", true, "written", "src/Main.kt")
        assertEquals(listOf("src/Main.kt"), session.touchedFiles())
    }

    @Test
    fun clearRemovesSession() {
        val session = AgentSessions.of("chat_clear")
        session.start("افحص المشروع", "Groq")
        session.recordTool("file_tree", true, "ملفات")
        assertTrue(AgentSessions.hasProgress("chat_clear"))
        AgentSessions.clear("chat_clear")
        assertFalse(AgentSessions.hasProgress("chat_clear"))
        assertNull(AgentSessions.peek("chat_clear"))
    }
}

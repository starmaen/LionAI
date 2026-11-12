package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات التحسينات الستة:
 * 1) منع تكرار نتائج الأدوات وضغط السياق.
 * 2) حذف الفروع المجمّعة (اختيار الأهداف محلياً بلا شبكة).
 * 3) توحيد مفتاحي name و tool في استدعاء الأدوات.
 * 4) إجابة السؤال داخل نفس الحلقة (جلسة تُفرّغ السؤال ولا تفقد الخطوات).
 * 5) سجل خطوات وملفات أوسع في ملخص الاستئناف.
 * 6) سقف خطوات قابل للتهيئة والتمديد.
 */
class AgentImprovementsTest {

    // ---------- (3) توحيد صيغة الاستدعاء ----------

    @Test
    fun toolAndNameKeysProduceTheSameCall() {
        val withTool = AiTools.parseCalls("""[[TOOL]]{"tool":"file_read","args":{"path":"a.kt"}}[[/TOOL]]""")
        val withName = AiTools.parseCalls("""[[TOOL]]{"name":"file_read","args":{"path":"a.kt"}}[[/TOOL]]""")
        assertEquals(1, withTool.size)
        assertEquals(1, withName.size)
        assertEquals(withTool.first().name, withName.first().name)
        assertEquals(withTool.first().args.toString(), withName.first().args.toString())
    }

    @Test
    fun argumentsAliasAndCaseAndSpacingAreAccepted() {
        val call = AiTools.parseCalls("""[[TOOL]]{"Name":"file_read","Arguments":{"path":"b.kt"}}[[/TOOL]]""")
        assertEquals(1, call.size)
        assertEquals("file_read", call.first().name)
        assertEquals("b.kt", call.first().args.optString("path"))

        val spaced = AiTools.parseCalls("""[[TOOL]]{"tool":"file read","args":{"path":"c.kt"}}[[/TOOL]]""")
        assertEquals(1, spaced.size)
        assertEquals("file_read", spaced.first().name)
    }

    @Test
    fun openAiFunctionShapeIsStillAccepted() {
        val call = AiTools.parseCalls(
            """[[TOOL]]{"function":{"name":"file_read","arguments":{"path":"d.kt"}}}[[/TOOL]]"""
        )
        assertEquals(1, call.size)
        assertEquals("file_read", call.first().name)
        assertEquals("d.kt", call.first().args.optString("path"))
    }

    @Test
    fun unknownToolIsStillRejected() {
        assertTrue(AiTools.parseCalls("""[[TOOL]]{"name":"no_such_tool","args":{}}[[/TOOL]]""").isEmpty())
    }

    // ---------- (1) منع التكرار ----------

    @Test
    fun identicalToolResultDoesNotInflateTheLog() {
        val session = AgentSession("dup_chat")
        session.start("افحص الملفات", "Claude")
        session.recordTool("file_read", true, "نفس المخرج", "src/A.kt")
        session.recordTool("file_read", true, "نفس المخرج", "src/A.kt")
        session.recordTool("file_read", true, "نفس المخرج", "src/A.kt")
        // خطوة واحدة فقط مع عدّاد تكرار، لا ثلاث خطوات متطابقة.
        assertEquals(1, session.stepCount())
        assertTrue(session.contextBlock().contains("تكررت"))
    }

    @Test
    fun differentOutputsAreKeptApart() {
        val session = AgentSession("dup_chat2")
        session.start("افحص الملفات", "Claude")
        session.recordTool("file_read", true, "الأول", "src/A.kt")
        session.recordTool("file_read", true, "الثاني", "src/A.kt")
        assertEquals(2, session.stepCount())
    }

    // ---------- (5) ملخص الاستئناف الأوسع ----------

    @Test
    fun resumeSummaryCarriesExecutionDigestAndFileTools() {
        val session = AgentSession("digest_chat")
        session.start("أصلح البناء وارفع التعديل", "Claude")
        session.recordTool("file_tree", true, "شجرة", "app")
        session.recordTool("file_read", true, "قرأت", "app/src/Main.kt")
        session.recordTool("file_write", true, "كتبت", "app/src/Main.kt")
        session.recordTool("github_wait_run", false, "فشل البناء", "run#7")
        val block = session.contextBlock()
        assertTrue(block.contains("ملخص التنفيذ"))
        assertTrue(block.contains("توزيع الأدوات"))
        assertTrue(block.contains("خطوات فشلت"))
        assertTrue(block.contains("app/src/Main.kt"))
        assertTrue(block.contains("[file_write]"))
        // الملف المعدّل معروف بأداة تعديله
        assertEquals("file_write", session.toolForFile("app/src/Main.kt"))
    }

    @Test
    fun digestAndFileToolsSurviveCheckpoint() {
        val session = AgentSession("checkpoint_chat")
        session.start("عدّل الملف", "Claude")
        session.recordTool("file_write", true, "كتبت", "src/B.kt")
        val restored = AgentSession.fromJson(session.toJson())
        assertTrue(restored.touchedFiles().contains("src/B.kt"))
        assertEquals("file_write", restored.toolForFile("src/B.kt"))
    }

    // ---------- (4) السؤال داخل نفس الحلقة ----------

    @Test
    fun answeringInsideTheLoopClearsTheQuestionAndKeepsSteps() {
        val session = AgentSession("inline_chat")
        session.start("أضف شاشة إعدادات", "Gemini")
        session.recordTool("file_tree", true, "شجرة", "app")
        session.askQuestion("هل أنشئ الشاشة في ملف جديد أم أعدّل الموجود؟")
        assertTrue(session.pendingQuestion != null)
        val before = session.stepCount()

        val consumed = session.answerQuestion("أنشئها في ملف جديد", "Gemini")

        assertTrue(consumed)
        assertNull(session.pendingQuestion)
        // الخطوة السابقة لم تُفقد، وأُضيفت خطوة الإجابة فقط.
        assertEquals(before + 1, session.stepCount())
        assertTrue(session.contextBlock().contains("إجابة المستخدم"))
        // لا سؤال معلّق بعد الإجابة
        assertFalse(session.contextBlock().contains("سؤال معلّق"))
    }

    @Test
    fun answerWithoutPendingQuestionIsNoOp() {
        val session = AgentSession("no_question")
        session.start("افحص فقط", "Claude")
        assertFalse(session.answerQuestion("أي رد"))
        assertTrue(session.pendingQuestion == null)
    }

    // ---------- (6) سقف الخطوات ----------

    @Test
    fun stepBudgetIsConfigurableAndDefaultsToOpenAutoMode() {
        // الافتراضي: تلقائي (0 في الإعدادات) ⇒ السقف المعتاد للوضع البرمجي.
        assertEquals(40, ToolAgent.stepBudget(ChatMode.CODE, true, FakePrefs()))
        assertEquals(24, ToolAgent.stepBudget(ChatMode.GENERAL, false, FakePrefs()))

        // سقف صريح من الإعدادات.
        val prefs = FakePrefs()
        prefs.edit().putInt(AGENT_MAX_STEPS, 120).apply()
        assertEquals(120, ToolAgent.stepBudget(ChatMode.CODE, true, prefs))
        assertEquals(120, ToolAgent.configuredSteps(prefs))
        assertTrue(ToolAgent.autoExtendSteps(prefs))

        // حاجز الأمان: لا يتجاوز الحد المطلق.
        prefs.edit().putInt(AGENT_MAX_STEPS, 99_999).apply()
        assertEquals(AGENT_HARD_MAX_STEPS, ToolAgent.configuredSteps(prefs))
    }

    // ---------- (2) حذف الفروع المجمّعة ----------

    @Test
    fun branchTargetsHonourPrefixPatternAndProtection() {
        val existing = listOf("main", "master", "dev", "feature/a", "feature/b", "tmp-1", "release/1.0")
        val byPrefix = GitHubClient.branchTargets(existing, emptyList(), "feature/", "", emptyList(), "main")
        assertEquals(listOf("feature/a", "feature/b"), byPrefix)

        val byPattern = GitHubClient.branchTargets(existing, emptyList(), "", "^tmp-", emptyList(), "main")
        assertEquals(listOf("tmp-1"), byPattern)

        val explicit = GitHubClient.branchTargets(existing, listOf("dev", "main", "missing"), "", "", emptyList(), "main")
        // main محمي، و«missing» يبقى في الخطة ليُبلَّغ عنه كغير موجود (لا يُسقط بصمت)
        assertEquals(listOf("dev", "missing"), explicit)

        val keepDev = GitHubClient.branchTargets(existing, listOf("dev", "feature/a"), "", "", listOf("dev"), "main")
        assertEquals(listOf("feature/a"), keepDev)

        // الفرع الافتراضي غير main يُحمى أيضاً
        val customDefault = GitHubClient.branchTargets(listOf("trunk", "trunk-old"), emptyList(), "trunk", "", emptyList(), "trunk")
        assertEquals(listOf("trunk-old"), customDefault)
    }

    @Test
    fun protectedBranchNamesAlwaysIncludeDefaultMainAndMaster() {
        val protected = GitHubClient.protectedBranchNames(listOf("dev"), "trunk")
        assertTrue(protected.contains("trunk"))
        assertTrue(protected.contains("main"))
        assertTrue(protected.contains("master"))
        assertTrue(protected.contains("dev"))
    }

    @Test
    fun deleteBranchesToolIsRegisteredAndDocumented() {
        val schema = AiTools.openAiTools(setOf(ToolGroup.GITHUB), FakePrefs())
        val functions = (0 until schema.length())
            .mapNotNull { schema.optJSONObject(it)?.optJSONObject("function") }
        val names = functions.map { it.optString("name") }
        assertTrue(names.contains("github_delete_branches"))
        val definition = functions.first { it.optString("name") == "github_delete_branches" }
        val properties = definition.optJSONObject("parameters")?.optJSONObject("properties") ?: JSONObject()
        assertTrue(properties.has("branches"))
        assertTrue(properties.has("prefix"))
        assertTrue(properties.has("pattern"))
        assertTrue(properties.has("dry_run"))
        // الوصف يوضّح الاستثناء التلقائي للفرع الافتراضي
        assertTrue(definition.optString("description").contains("الافتراضي"))
    }

    @Test
    fun agentAskBridgeDeliversOnlyWhenWaiting() {
        assertFalse(AgentAsk.deliver("chat_x", "نعم"))
        assertFalse(AgentAsk.isWaiting("chat_x"))
        AgentAsk.cancel("chat_x")
    }
}

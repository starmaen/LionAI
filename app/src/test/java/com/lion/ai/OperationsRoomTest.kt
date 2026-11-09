package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class OperationsRoomTest {
    private fun prefs() = FakePrefs().also {
        it.edit().putBoolean("auto_route", true).putString("provider", "gemini")
            .putString("api_key_gemini", "k").apply()
    }

    @Test fun roomIsOffUntilEnabledAndNeverInManualOrNamedSite() {
        val p = prefs()
        assertFalse(OperationsRoom.enabled(p))
        p.edit().putBoolean(OperationsRoom.PREF_ENABLED, true).apply()
        assertTrue(OperationsRoom.enabled(p))
        p.edit().putBoolean("auto_route", false).apply()
        assertFalse(OperationsRoom.enabled(p))
        p.edit().putBoolean("auto_route", true).putString("provider", "mistral_web").apply()
        assertFalse(OperationsRoom.enabled(p))
    }

    @Test fun onlyComplexTasksEnterTheRoom() {
        val chat = ChatMode.GENERAL
        assertFalse(OperationsRoom.isComplex("مرحبا كيف حالك اليوم", TaskKind.FAST, chat, false))
        assertFalse(OperationsRoom.isComplex("ما هي عاصمة فرنسا وما عدد سكانها", TaskKind.FACTUAL, chat, false))
        assertTrue(OperationsRoom.isComplex("ابنِ لي تطبيق أندرويد لإدارة المهام مع قاعدة بيانات وواجهة عربية", TaskKind.DEV, chat, false))
        assertTrue(OperationsRoom.isComplex("build a website with auth and an admin dashboard", TaskKind.DEV, chat, false))
        assertTrue(OperationsRoom.isComplex("1. أنشئ الجدول\n2. أضف الواجهة\n3. اربط الخادم\n4. اختبر كل شيء", TaskKind.DEV, chat, false))
        assertFalse(OperationsRoom.isComplex("ابنِ لي تطبيق أندرويد لإدارة المهام مع قاعدة بيانات", TaskKind.VISION, chat, false))
    }

    @Test fun failuresAreRecognised() {
        assertTrue(OperationsRoom.isFailure(""))
        assertTrue(OperationsRoom.isFailure("⚠️ انتهت المهلة"))
        assertFalse(OperationsRoom.isFailure("هذا حل كامل ومفصل"))
    }

    @Test fun singleGoodProposalIsReturnedWithoutAnotherCall() {
        val r = Route("gemini", "m")
        val res = OperationsRoom.decide(
            prefs(), "سؤال", listOf(OpsCandidate(r, "الحل", true, 10), OpsCandidate(Route("openai", "x"), "⚠️ فشل", false, 5)),
            "c", ChatMode.GENERAL, { "" }, {}
        )
        assertTrue(res.ok); assertEquals("الحل", res.answer); assertEquals("gemini", res.lead?.provider)
    }

    @Test fun noProposalMeansRoomFailsSoNormalRoutingTakesOver() {
        val res = OperationsRoom.decide(
            prefs(), "سؤال", listOf(OpsCandidate(Route("gemini", "m"), "⚠️ فشل", false, 5)),
            "c", ChatMode.GENERAL, { "" }, {}
        )
        assertFalse(res.ok)
    }

    @Test fun decisionPromptHidesParticipantsAndCarriesProposalsAndCritique() {
        val good = listOf(
            OpsCandidate(Route("gemini", "m"), "حل أ", true, 1),
            OpsCandidate(Route("mistral_web", "web-session"), "حل ب", true, 1)
        )
        val t = OperationsRoom.decisionPrompt("ابنِ تطبيقاً", good, "الحل أ ينقصه اختبار")
        assertTrue(t.contains("مقترح 1") && t.contains("مقترح 2") && t.contains("حل أ") && t.contains("حل ب"))
        assertTrue(t.contains("الحل أ ينقصه اختبار"))
        assertFalse(t.contains("gemini") || t.contains("mistral"))
    }

    @Test fun webMembersUseVerifiedPreferredSitesAndHonourExplicitChoice() {
        val p = prefs()
        p.edit().putBoolean(WebChatRuntime.ENABLED, true)
            .putBoolean("web_chat_enabled_mistral_web", true).putBoolean("web_session_verified_mistral_web", true)
            .putBoolean("web_chat_enabled_github_copilot_web", true).apply() // غير موثّق: لا يُستخدم تلقائياً
        assertEquals(listOf("mistral_web"), OperationsRoom.webMembers(p))
        p.edit().putString(OperationsRoom.PREF_CORE, "github_copilot_web,gemini").apply()
        assertEquals(listOf("github_copilot_web"), OperationsRoom.webMembers(p))
        assertTrue(OperationsRoom.apiMembers(p).contains("gemini"))
        assertTrue(OperationsRoom.apiMembers(p).none { WebChatRuntime.handles(it) })
    }

    @Test fun evidencePhaseIsReadOnlyAndOnlyWhenTheTaskNeedsFacts() {
        val forbidden = setOf(ToolGroup.SHELL, ToolGroup.DATABASE, ToolGroup.FIREBASE, ToolGroup.COMMS, ToolGroup.CONNECTORS, ToolGroup.MCP)
        assertTrue(OperationsRoom.EVIDENCE_GROUPS.none { it in forbidden })
        assertTrue(ToolGroup.FILES in OperationsRoom.EVIDENCE_GROUPS && ToolGroup.GITHUB in OperationsRoom.EVIDENCE_GROUPS)
        assertTrue(OperationsRoom.wantsEvidence("راجع مستودع github.com/x/y وقيّم نقاط الضعف", false))
        assertTrue(OperationsRoom.wantsEvidence("ابنِ لي تطبيقاً", true))
        assertFalse(OperationsRoom.wantsEvidence("اقترح اسماً جميلاً لمتجر زهور", false))
    }

    @Test fun promptsCarryEvidenceAndForbidUnverifiedAbsenceClaims() {
        val good = listOf(OpsCandidate(Route("gemini", "m"), "حل أ", true, 1), OpsCandidate(Route("openai", "m"), "حل ب", true, 1))
        val d = OperationsRoom.decisionPrompt("قيّم المشروع", good, "", "app/src/test فيه 43 ملف اختبار")
        assertTrue(d.contains("43 ملف اختبار"))
        assertTrue(d.contains("ولا تجزم بوجود أو غياب"))
        assertTrue(OperationsRoom.critiquePrompt("س", good, "حقيقة موثقة").contains("حقيقة موثقة"))
        assertTrue(OperationsRoom.evidencePrompt("راجع الكود").contains("أدوات القراءة فقط"))
    }
}

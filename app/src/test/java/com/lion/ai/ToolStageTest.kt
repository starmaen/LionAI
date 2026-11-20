package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * اختبارات الجولة 5: تصنيف الأدوات حسب مرحلة دورة العمل (استكشاف ← تعديل ← تحقق ← نشر)،
 * وتخفيف الحراسة (التدقيق الختامي عند الشك فقط).
 */
class ToolStageTest {

    @Test fun toolsAreClassifiedIntoWorkPhases() {
        // الاستكشاف
        assertEquals(ToolPhase.DISCOVERY, AiTools.phaseOf("file_tree"))
        assertEquals(ToolPhase.DISCOVERY, AiTools.phaseOf("github_read_file"))
        assertEquals(ToolPhase.DISCOVERY, AiTools.phaseOf("repo_map"))
        // التعديل (أدوات كتابة محلية)
        assertEquals(ToolPhase.EDIT, AiTools.phaseOf("file_edit"))
        assertEquals(ToolPhase.EDIT, AiTools.phaseOf("file_write"))
        assertEquals(ToolPhase.EDIT, AiTools.phaseOf("file_apply_patch"))
        // التحقق
        assertEquals(ToolPhase.VERIFY, AiTools.phaseOf("code_doctor"))
        assertEquals(ToolPhase.VERIFY, AiTools.phaseOf("build_diagnose"))
        assertEquals(ToolPhase.VERIFY, AiTools.phaseOf("github_wait_run"))
        // النشر
        assertEquals(ToolPhase.PUBLISH, AiTools.phaseOf("github_push_workspace"))
        assertEquals(ToolPhase.PUBLISH, AiTools.phaseOf("github_commit_files"))
        assertEquals(ToolPhase.PUBLISH, AiTools.phaseOf("github_create_pr"))
        // المرافق (اتصالات/ويب/موصلات لا تنقل المرحلة)
        assertEquals(ToolPhase.SUPPORT, AiTools.phaseOf("whatsapp_send"))
        assertEquals(ToolPhase.SUPPORT, AiTools.phaseOf("web_search"))
        assertEquals(ToolPhase.SUPPORT, AiTools.phaseOf("أداة_غير_موجودة"))
    }

    @Test fun stagedSpecPutsCurrentPhaseFirst() {
        val prefs = FakePrefs()
        val groups = setOf(ToolGroup.FILES, ToolGroup.GITHUB, ToolGroup.QUALITY, ToolGroup.META)
        val spec = AiTools.spec(groups, prefs, ToolAgent.LEAN_TOOLS, ToolPhase.PUBLISH)
        // عنوان المرحلة الحالية موجود، وأدوات النشر تسبق أدوات الاستكشاف
        assertTrue(spec.contains("المرحلة الحالية للمهمة: النشر إلى GitHub"))
        val publishIdx = spec.indexOf("github_push_workspace")
        val discoveryIdx = spec.indexOf("file_tree")
        assertTrue("أدوات النشر يجب أن تظهر قبل أدوات الاستكشاف", publishIdx in 0 until discoveryIdx)
        assertTrue(spec.contains("◀"))
        // كل الأدوات المطلوبة تبقى ظاهرة (لا نُخفي مخططاً يحتاجه المزود لاحقاً)
        for (name in ToolAgent.LEAN_TOOLS.filter { it != "tools_groups" || true }) {
            if (name == "code_doctor" || name == "gate_run" || name == "file_edit") {
                assertTrue("الأداة $name يجب أن تبقى في القائمة", spec.contains(name))
            }
        }
    }

    @Test fun stagedSpecFollowsTaskProgress() {
        val prefs = FakePrefs()
        val groups = setOf(ToolGroup.FILES, ToolGroup.GITHUB, ToolGroup.QUALITY, ToolGroup.META)
        val editSpec = AiTools.spec(groups, prefs, ToolAgent.LEAN_TOOLS, ToolPhase.EDIT)
        val editIdx = editSpec.indexOf("file_edit")
        val discoveryIdx = editSpec.indexOf("file_tree")
        assertTrue(editIdx in 0 until discoveryIdx)
        assertTrue(editSpec.contains("المرحلة الحالية للمهمة: التعديل والكتابة"))
    }

    @Test fun plainSpecKeepsGroupLayout() {
        val prefs = FakePrefs()
        val groups = setOf(ToolGroup.FILES, ToolGroup.META)
        val spec = AiTools.spec(groups, prefs)
        // بلا مرحلة: العرض القديم بالمجموعات كما هو (لا عنوان مرحلة)
        assertFalse(spec.contains("المرحلة الحالية للمهمة"))
        assertTrue(spec.contains("الملفات"))
    }

    @Test fun finalAuditFiresOnlyWhenEvidenceIsDoubtful() {
        // تعديل مؤكد وبلا فشل: لا تدقيق زائد — الختام مباشر
        assertFalse(
            ToolAgent.needsFinalAudit(
                mutationIntent = true, readOnly = false, answerOnly = false,
                toolsUsed = true, okMutations = 2, trailingFails = 0
            )
        )
        // لا تعديل مؤكد: يلزم التدقيق (صدق لا نجاح زائف)
        assertTrue(
            ToolAgent.needsFinalAudit(
                mutationIntent = true, readOnly = false, answerOnly = false,
                toolsUsed = true, okMutations = 0, trailingFails = 0
            )
        )
        // فشل في آخر الخطوات: يلزم التدقيق
        assertTrue(
            ToolAgent.needsFinalAudit(
                mutationIntent = true, readOnly = false, answerOnly = false,
                toolsUsed = true, okMutations = 3, trailingFails = 1
            )
        )
        // طلبات القراءة والجواب المباشر لا تدقيق عليها أصلاً
        assertFalse(
            ToolAgent.needsFinalAudit(
                mutationIntent = false, readOnly = false, answerOnly = true,
                toolsUsed = true, okMutations = 0, trailingFails = 0
            )
        )
        assertFalse(
            ToolAgent.needsFinalAudit(
                mutationIntent = true, readOnly = true, answerOnly = false,
                toolsUsed = true, okMutations = 0, trailingFails = 0
            )
        )
    }
}

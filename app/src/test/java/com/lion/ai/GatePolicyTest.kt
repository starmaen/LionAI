package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تقفل إصلاح «البوابة تمنع كل بناء»: مشروع قائم بلا Manifest لا يُفشل البوابة أبداً،
 * والمجلدات الفارغة/الملفات اليتيمة ملاحظات لا أخطاء، والتقرير يشرح كل رمز بالعربية.
 */
class GatePolicyTest {

    private fun snapshot(vararg files: Pair<String, String>) = ProjectSnapshot(files.toMap())

    @Test
    fun existingProjectWithoutManifestAlwaysPasses() {
        val snap = ProjectSnapshot(
            files = mapOf(
                "app/src/main/java/A.kt" to "fun a() = Unit",
                "app/src/main/java/B.kt" to "fun b() = Unit" // يتيم: لا نقطة دخول
            ),
            directories = setOf("app/src/main/java"),
            emptyDirectories = setOf("app/src/main/java", "assets")
        )
        val report = ControlPlane.evaluate(snap, manifest = null)
        assertTrue("مشروع قائم: لا شيء يمنع", report.ok)
        assertTrue(report.existingProject)
        assertTrue(report.issues.any { it.code == "EMPTY_DIR" && it.severity == "warn" })
        assertTrue(report.issues.any { it.code == "NO_MANIFEST" })
    }

    @Test
    fun emptyDirNeverBlocksEvenWithManifest() {
        val manifest = BuildManifest(
            project = "p",
            files = listOf(ManifestFile("src/Main.kt", entry = true))
        )
        val snap = ProjectSnapshot(
            files = mapOf("src/Main.kt" to "fun main() = Unit"),
            emptyDirectories = setOf("docs")
        )
        val report = ControlPlane.evaluate(snap, manifest)
        assertTrue("مجلد فارغ لا يمنع", report.ok)
    }

    @Test
    fun approvedManifestStillRequiresItsDeclaredFiles() {
        val manifest = BuildManifest(
            project = "p",
            approved = true,
            files = listOf(ManifestFile("src/Main.kt", entry = true), ManifestFile("src/Missing.kt"))
        )
        val snap = snapshot("src/Main.kt" to "fun main() = Unit")
        val report = ControlPlane.evaluate(snap, manifest)
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "MISSING_FILE" && it.severity == "error" })
    }

    @Test
    fun reportTextExplainsBlockingVsNotes() {
        val manifest = BuildManifest(
            project = "p",
            approved = true,
            files = listOf(ManifestFile("src/Main.kt"), ManifestFile("src/Gone.kt"))
        )
        val text = ControlPlane.evaluate(snapshot("src/Main.kt" to "fun main() = Unit"), manifest).text()
        assertTrue(text.contains("يمنع الاكتمال"))
        assertTrue(text.contains("لا يمنع البناء") || text.contains("ملاحظات"))
        assertTrue(text.contains("غير موجود على القرص"))
    }

    @Test
    fun policyExplainsEachCodeInArabic() {
        for (code in listOf("EMPTY_DIR", "ORPHAN_FILE", "MISSING_FILE", "NO_MANIFEST", "SYNTAX_BALANCE")) {
            assertTrue(code, ManifestPolicy.explain(code).isNotBlank())
        }
        assertFalse(ManifestPolicy.blocking("EMPTY_DIR", BuildManifest("p")))
        assertTrue(ManifestPolicy.blocking("MISSING_FILE", BuildManifest("p")))
        assertFalse(ManifestPolicy.blocking("MISSING_FILE", null))
    }
}

/**
 * يقفل كسر حلقة «نتيجة محاولة سابقة غير مؤكدة»: القراءة الفعلية تحسم الحالة،
 * فلا تتوقف المهمة إلى الأبد ولا يُعاد التنفيذ الأعمى.
 */
class UncertainMutationTest {
        @Test
    fun successfulReadResolvesUncertainMutationOnSamePath() {
        val c = TaskContinuity()
        c.begin("عدّل الملف src/Main.kt", unfinished = false)
        val key = c.key("file_edit", org.json.JSONObject("""{"path":"src/Main.kt","old":"a","new":"b"}"""))
        assertTrue(c.beginMutation(key))
        c.recordMutation(key, "network timeout", ok = false, definite = false, path = "src/Main.kt")
        assertTrue(c.hasUncertainMutation())

        c.recordRead(c.key("file_read", org.json.JSONObject("""{"path":"src/Main.kt"}""")), "محتوى الملف", "src/Main.kt")
        assertFalse("القراءة الفعلية تحسم الشك", c.hasUncertainMutation())
    }

    @Test
    fun unrelatedReadDoesNotClearUncertainty() {
        val c = TaskContinuity()
        c.begin("عدّل الملف src/Main.kt", unfinished = false)
        val key = c.key("file_edit", org.json.JSONObject("""{"path":"a/A.kt"}"""))
        c.beginMutation(key)
        c.recordMutation(key, "boom", ok = false, definite = false, path = "a/A.kt")

        c.recordRead(c.key("file_read", org.json.JSONObject("""{"path":"b/B.kt"}""")), "x", "b/B.kt")
        assertTrue(c.hasUncertainMutation())
    }

    @Test
    fun explicitResolveClearsAllPending() {
        val c = TaskContinuity()
        c.begin("نفّذ", unfinished = false)
        c.beginMutation(c.key("t", org.json.JSONObject("{}")))
        c.recordMutation(c.key("t", org.json.JSONObject("{}")), "unknown", ok = false, definite = false)
        assertTrue(c.resolveUncertain() >= 1)
        assertFalse(c.hasUncertainMutation())
    }
}

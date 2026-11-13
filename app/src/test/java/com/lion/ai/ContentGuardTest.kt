package com.lion.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContentGuardTest {
    private val big = "fun x() {}\n".repeat(400)

    @Test fun placeholderTextIsAlwaysRejected() {
        assertNotNull(ContentGuard.problem("A.kt", null, "/* تم التعديل: تمديد المهلة */\n// (تحديث attemptTimeoutMs)\n// ... [الكود المحدث] ..."))
        assertNotNull(ContentGuard.problem("A.kt", null, "fun a() {}\n// ... existing code ...\n"))
        assertNotNull(ContentGuard.problem("A.kt", big, ""))
    }

    @Test fun drasticShrinkNeedsExplicitFlag() {
        assertNotNull(ContentGuard.problem("A.kt", big, "fun x() {}\n"))
        assertNull(ContentGuard.problem("A.kt", big, "fun x() {}\n", allowShrink = true))
    }

    @Test fun normalEditsAndNewFilesPass() {
        assertNull(ContentGuard.problem("A.kt", big, big.replace("fun x", "fun y")))
        assertNull(ContentGuard.problem("New.kt", null, "class A\n"))
        assertNull(ContentGuard.problem("notes.txt", big, "short"))
    }
}

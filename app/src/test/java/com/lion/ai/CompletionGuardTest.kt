package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompletionGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun v(
        mutation: Boolean = true, readOnly: Boolean = false, answerOnly: Boolean = false,
        used: Int = 3, okMut: Int = 1, trailing: Int = 0, blank: Boolean = false
    ) = CompletionGuard.verdict(mutation, readOnly, answerOnly, used, okMut, trailing, blank)

    @Test fun completesWithEvidence() = assertTrue(v().complete)
    @Test fun noToolsMeansPlainAnswer() = assertTrue(v(used = 0, okMut = 0).complete)
    @Test fun editRequestWithoutSuccessfulWriteIsIncomplete() = assertFalse(v(okMut = 0).complete)
    @Test fun trailingFailureOnEditIsIncomplete() = assertFalse(v(trailing = 1).complete)
    @Test fun blankReplyAfterFailureIsIncomplete() = assertFalse(v(mutation = false, okMut = 0, trailing = 2, blank = true).complete)
    @Test fun readOnlyReportIsComplete() = assertTrue(v(mutation = false, okMut = 0).complete)

    @Test fun pollingToolsAreExemptFromLoopTally() {
        assertTrue(CompletionGuard.isPolling("github_wait_run"))
        assertFalse(CompletionGuard.isPolling("file_read"))
    }

    @Test fun resultKeyIsStableAndSensitive() {
        assertEquals(CompletionGuard.resultKey("t", "{a}", "x"), CompletionGuard.resultKey("t", "{a}", "x"))
        assertFalse(CompletionGuard.resultKey("t", "{a}", "x") == CompletionGuard.resultKey("t", "{a}", "y"))
    }

    @Test fun missingFileSuggestsSameNameElsewhere() {
        val root = tmp.root
        File(root, "app/src").mkdirs()
        File(root, "app/src/Main.kt").writeText("x")
        val msg = CompletionGuard.missingFileMessage(root, "Main.kt")
        assertTrue(msg.startsWith("الملف غير موجود: Main.kt"))
        assertTrue(msg.contains("app/src/Main.kt") || msg.contains("app\\src\\Main.kt"))
    }

    @Test fun missingFileListsParentWhenNoSimilarName() {
        val root = tmp.root
        File(root, "src").mkdirs()
        File(root, "src/A.kt").writeText("x")
        val msg = CompletionGuard.missingFileMessage(root, "src/B.kt")
        assertTrue(msg.contains("A.kt"))
    }

    @Test fun missingFileFallsBackToSearchHint() {
        val msg = CompletionGuard.missingFileMessage(tmp.root, "nope/Z.kt")
        assertTrue(msg.contains("file_tree"))
    }
}

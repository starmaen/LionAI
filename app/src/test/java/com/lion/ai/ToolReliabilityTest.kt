package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReliabilityTest {
    @Test fun treeFooterCountsExactlyAndFlagsTruncation() {
        val entries = (1..50).map { ("app/t/F$it" + if (it <= 43) "Test.kt" else ".kt") to false } + listOf("app/t" to true)
        val f = AiTools.treeFooter(entries, 10, "Test.kt")
        assertTrue(f.contains("المجموع: 50 ملف، 1 مجلد"))
        assertTrue(f.contains("عُرض 10 فقط من 51"))
        assertTrue(f.contains("«Test.kt»: 43"))
    }

    @Test fun treeFooterWithoutSuffixHasNoCountLineAndNoTruncationNote() {
        val f = AiTools.treeFooter(listOf("a.kt" to false, "d" to true), 2, "")
        assertTrue(f.contains("المجموع: 1 ملف، 1 مجلد"))
        assertFalse(f.contains("المنتهية بـ"))
        assertFalse(f.contains("عُرض"))
    }

    @Test fun webStyleFencedCurlyReplyStillParsesToACall() {
        val raw = "سأقرأ الملف\n```json\n[[TOOL]]{“name”:“file_read”,“args”:{“path”:“a.kt”}}[[/TOOL]]\n```"
        val calls = AiTools.parseCalls(WebToolBridge.normalizeReply(raw))
        assertEquals(1, calls.size)
        assertEquals("file_read", calls[0].name)
        assertEquals("a.kt", calls[0].args.getString("path"))
    }

    @Test fun malformedToolBlockParsesToNothingSoTheAgentAsksForARepair() {
        assertTrue(AiTools.parseCalls("[[TOOL]]{name: file_read, args: [[/TOOL]]").isEmpty())
        assertTrue(AiTools.parseCalls("[[TOOL]]{\"name\":\"no_such_tool\",\"args\":{}}[[/TOOL]]").isEmpty())
    }
}

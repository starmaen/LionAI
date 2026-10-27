package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnifiedDiffTest {
    @Test
    fun appliesSingleHunk() {
        val original = "a\nb\nc\n"
        val patch = """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,2 +1,3 @@
             a
             b
            +x
        """.trimIndent() + "\n"
        val applied = UnifiedDiff.apply(original, patch)
        assertEquals("a\nb\nx\nc\n", applied.text)
        assertEquals(1, applied.added)
        assertEquals(0, applied.removed)
    }

    @Test
    fun appliesMultipleHunks() {
        val original = "1\n2\n3\n4\n5\n6\n"
        val patch = """
            @@ -1,3 +1,3 @@
             1
            -2
            +two
             3
            @@ -5,2 +5,2 @@
             5
            -6
            +six
        """.trimIndent() + "\n"
        val applied = UnifiedDiff.apply(original, patch)
        assertEquals("1\ntwo\n3\n4\n5\nsix\n", applied.text)
        assertEquals(2, applied.hunks)
    }

    @Test
    fun preservesMissingTrailingNewline() {
        val original = "a\nb"
        val patch = """
            @@ -1,2 +1,2 @@
             a
            -b
            +B
        """.trimIndent() + "\n"
        assertEquals("a\nB", UnifiedDiff.apply(original, patch).text)
    }

    @Test
    fun rejectsWrongContext() {
        val original = "a\nb\n"
        val patch = "@@ -1,2 +1,2 @@\n a\n-c\n+d\n"
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedDiff.apply(original, patch)
        }
    }

    @Test
    fun appliesInsertionAtStart() {
        val original = "b\n"
        val patch = "@@ -0,0 +1,1 @@\n+a\n"
        assertEquals("a\nb\n", UnifiedDiff.apply(original, patch).text)
    }
}

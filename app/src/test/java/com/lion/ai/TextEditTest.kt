package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextEditTest {
    private val src = "function visible(n) {\n  if (!n) return false;\n  return true;\n}\nfunction other() {\n  return 1;\n}\n"

    @Test fun exactUniqueReplace() {
        val r = TextEdit.replace(src, "  if (!n) return false;", "  if (!n || n.hidden) return false;")
        assertEquals(1, r.count)
        assertTrue(r.text.contains("n.hidden"))
    }

    @Test fun whitespaceInsensitiveMatchKeepsIndent() {
        val r = TextEdit.replace(src, "if (!n) return false;\nreturn true;", "if (!n) return false;\nreturn !n.hidden;")
        assertTrue(r.text.contains("  return !n.hidden;"))
        assertTrue(r.text.contains("  if (!n) return false;"))
    }

    @Test fun ambiguousReplaceNeedsReplaceAll() {
        try { TextEdit.replace("a\nb\na\n", "a", "c"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("يتكرر")) }
        assertEquals(2, TextEdit.replace("a\nb\na\n", "a", "c", all = true).count)
    }

    @Test fun missingTextExplainsNearestLine() {
        try { TextEdit.replace(src, "function visible(x) {", "z"); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("function visible"))
        }
    }

    @Test fun failedLocalMutationIsNotUncertain() {
        val m = TaskContinuity()
        m.beginMutation("k"); m.recordMutation("k", "فشل hunk", ok = false, definite = true)
        assertTrue(!m.hasUncertainMutation())
        m.beginMutation("n"); m.recordMutation("n", "timeout", ok = false)
        assertTrue(m.hasUncertainMutation())
    }
}

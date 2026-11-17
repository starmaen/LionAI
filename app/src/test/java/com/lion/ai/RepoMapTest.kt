package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoMapTest {
    @Test
    fun kotlinSignaturesKeepDeclarationsWithoutBodies() {
        val src = """
            package a
            internal object Foo {
                fun bar(x: Int): Int {
                    return x
                }
                private suspend fun baz(a: Int = 1) = a
                val notASignature = 5
            }
            data class P(val a: Int)
        """.trimIndent()
        val sigs = RepoMap.signatures("Foo.kt", src)
        assertTrue(sigs.contains("internal object Foo"))
        assertTrue(sigs.any { it.trim() == "fun bar(x: Int): Int" })
        assertTrue(sigs.any { it.trim() == "private suspend fun baz(a: Int = 1)" })
        assertTrue(sigs.contains("data class P(val a: Int)"))
        assertFalse(sigs.any { it.contains("return") || it.contains("notASignature") })
    }

    @Test
    fun pythonAndScriptSignatures() {
        assertEquals(listOf("class A", "  def m(self)"), RepoMap.signatures("a.py", "class A:\n    def m(self):\n        pass"))
        assertEquals(listOf("export async function go(x)"), RepoMap.signatures("a.ts", "export async function go(x) {\n  return 1\n}"))
        assertTrue(RepoMap.signatures("a.txt", "class A").isEmpty())
    }

    @Test
    fun renderWrapsInTagAndReportsOverflow() {
        val entries = (1..50).map { RepoMap.Entry("src/F$it.kt", 10, listOf("class F$it")) }
        val out = RepoMap.render(entries, 400)
        assertTrue(out.startsWith("<project_map files=\"50\">") && out.endsWith("</project_map>"))
        assertTrue(out.contains("لم تتسع"))
    }
}

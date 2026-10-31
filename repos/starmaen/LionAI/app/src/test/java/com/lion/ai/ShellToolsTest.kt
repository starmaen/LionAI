package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolsTest {

    @Test
    fun readOnlyCommandsAreNotWrites() {
        assertFalse(ShellTools.looksLikeWrite("git status"))
        assertFalse(ShellTools.looksLikeWrite("ls -la"))
        assertFalse(ShellTools.looksLikeWrite("cat README.md"))
    }

    @Test
    fun destructiveAndMutatingCommandsAreWrites() {
        assertTrue(ShellTools.looksLikeWrite("rm old.txt"))
        assertTrue(ShellTools.looksLikeWrite("mv a.kt b.kt"))
        assertTrue(ShellTools.looksLikeWrite("mkdir src"))
        assertTrue(ShellTools.looksLikeWrite("echo hi > notes.txt"))
        assertTrue(ShellTools.looksLikeWrite("git commit -m x"))
        assertTrue(ShellTools.looksLikeWrite("npm install"))
    }

    @Test
    fun parsesKotlinBuildErrors() {
        val raw = """
            e: file:///home/run/app/Main.kt: (12, 4): unresolved reference: foo
            Main.kt:20: error: expecting ')'
        """.trimIndent()
        val errors = ShellTools.parseBuildErrors(raw)
        assertTrue(errors.any { it.contains("Main.kt:12") && it.contains("unresolved") })
        assertTrue(errors.any { it.contains("Main.kt:20") && it.contains("expecting") })
    }
}

package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRunnerTest {
    @Test
    fun supportedLanguagesUseTheirExpectedRuntimes() {
        assertEquals("node", CodeRunner.plan("javascript")?.command)
        assertEquals("ts", CodeRunner.plan("TypeScript")?.extension)
        assertTrue(CodeRunner.plan("bash")?.command?.contains("busybox") == true)
        assertTrue(CodeRunner.plan("sql")?.command?.startsWith("sqlite3") == true)
    }

    @Test
    fun arbitraryLanguagesAreNotExecuted() {
        assertNull(CodeRunner.plan("python; rm -rf /"))
        assertNull(CodeRunner.plan("kotlin"))
    }
}

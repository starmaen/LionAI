package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTranslatorTest {

    @Test
    fun canonicalizesArabicAndEnglishNames() {
        assertEquals("Kotlin", CodeTranslator.canonical("كوتلن"))
        assertEquals("Python", CodeTranslator.canonical("PYTHON"))
        assertEquals("TypeScript", CodeTranslator.canonical("تايب سكريبت"))
        assertEquals("C#", CodeTranslator.canonical("csharp"))
    }

    @Test
    fun extractsLargestFencedBlock() {
        val raw = """
            سأترجم الكود:
            ```kotlin
            fun main() { println("hi") }
            ```
            ```python
            def main():
                print("hello")
                print("world")
            ```
        """.trimIndent()
        val code = CodeTranslator.extractCode(raw)
        assertTrue(code.contains("def main"))
    }

    @Test
    fun extractsBareCodeWithoutFences() {
        assertEquals("val x = 1", CodeTranslator.extractCode("```\nval x = 1\n```"))
    }

    @Test
    fun buildsOutputPathWithoutOverwritingSource() {
        assertEquals("src/Main.kt", CodeTranslator.outputPath("src/Main.py", "Kotlin"))
        assertEquals("src/Main.translated.py", CodeTranslator.outputPath("src/Main.py", "Python"))
        assertEquals("translated.kt", CodeTranslator.outputPath("", "Kotlin"))
    }

    @Test
    fun detectsLanguageFromExtension() {
        assertEquals("Kotlin", CodeTranslator.languageOf("src/Main.kt"))
        assertEquals("Python", CodeTranslator.languageOf("tool.py"))
    }
}

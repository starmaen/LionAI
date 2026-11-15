package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class StateGlyphsTest {
    @Test fun brainIsReplacedByThinkingGlyph() {
        val line = "\uD83E\uDDE0 Gemini يخطط للخطوة 3"
        assertEquals("Gemini يخطط للخطوة 3", StateGlyphs.clean(line))
        assertEquals(StateGlyph.THINKING, StateGlyphs.forLine(line))
    }

    @Test fun stackedLeadingSymbolsAreAllRemoved() {
        val line = "\u23F3 \uD83D\uDEE0\uFE0F shell_exec ← ./gradlew test"
        assertEquals("shell_exec ← ./gradlew test", StateGlyphs.clean(line))
        assertEquals(StateGlyph.TERMINAL, StateGlyphs.forLine(line))
    }

    @Test fun coloredPencilBecomesWritingGlyph() {
        val line = "\u270F\uFE0F تغيير الاسم"
        assertEquals("تغيير الاسم", StateGlyphs.clean(line))
        assertEquals(StateGlyph.WRITING, StateGlyphs.forLine(line))
    }

    @Test fun stateMarkersWinOverKeywords() {
        assertEquals(StateGlyph.SUCCESS, StateGlyphs.forLine("\u2705 write_file"))
        assertEquals(StateGlyph.ERROR, StateGlyphs.forLine("\u274C file read ← a.kt — فشل"))
        assertEquals(StateGlyph.WARNING, StateGlyphs.forLine("\u26A0\uFE0F المزود وعد بالعمل دون تنفيذ"))
    }

    @Test fun providerSwitchAndWaitingAreRecognised() {
        assertEquals(StateGlyph.SWITCH, StateGlyphs.forLine("\u23F8 توقف المزود — نتابع مع مزود بديل"))
        assertEquals(StateGlyph.WAITING, StateGlyphs.forLine("\u23F3 Gemini مشغول مؤقتاً — أعيد المحاولة"))
        assertEquals(StateGlyph.ASK, StateGlyphs.forLine("\uD83D\uDCAC بانتظار ردك"))
    }

    @Test fun leadingArrowEmojiIsRemoved() {
        assertEquals("توقف المزود", StateGlyphs.clean("\u21AA\uFE0F توقف المزود"))
    }

    @Test fun pictographsRemovedAnywhereButArrowsAndPunctuationKept() {
        assertEquals("تم رفع الملف", StateGlyphs.removePictographs("تم \u2705 رفع \uD83D\uDE80 الملف"))
        assertEquals("file edit ← path — ok", StateGlyphs.removePictographs("file edit ← path — ok"))
    }

    @Test fun toolNamesMapToTechnicalStates() {
        assertEquals(StateGlyph.FILE, StateGlyphs.forTool("file_read"))
        assertEquals(StateGlyph.WRITING, StateGlyphs.forTool("file_write"))
        assertEquals(StateGlyph.WRITING, StateGlyphs.forTool("file_edit"))
        assertEquals(StateGlyph.UPLOAD, StateGlyphs.forTool("github_push_workspace"))
        assertEquals(StateGlyph.BUILD, StateGlyphs.forTool("github_wait_run"))
        assertEquals(StateGlyph.TERMINAL, StateGlyphs.forTool("shell_exec"))
        assertEquals(StateGlyph.SEARCH, StateGlyphs.forTool("github_search_code"))
        assertEquals(StateGlyph.SWITCH, StateGlyphs.forTool("provider"))
        assertEquals(StateGlyph.GENERIC, StateGlyphs.forTool("tools_schema"))
    }

    @Test fun workVisualsCarryGlyphsAndNoEmojiInTitles() {
        val visuals = buildWorkVisuals(listOf("\u2705 write_file", "\uD83E\uDDE0 Gemini يخطط للخطوة 2"), emptyList())
        val running = visuals.last()
        assertEquals(WorkVisualState.RUNNING, running.state)
        assertEquals(StateGlyph.THINKING, running.glyph)
        assertEquals("Gemini يخطط للخطوة 2", running.title)
    }
}

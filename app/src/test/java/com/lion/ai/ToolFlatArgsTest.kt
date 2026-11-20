package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFlatArgsTest {
    @Test fun flatArgsAreRecovered() {
        val calls = AiTools.parseCalls("[[TOOL]]{\"tool\":\"github_read_file\",\"repo\":\"a/b\",\"path\":\"X.kt\"}[[/TOOL]]")
        assertEquals(1, calls.size)
        assertEquals("X.kt", calls[0].args.getString("path"))
    }

    @Test fun nestedArgsStillWork() {
        val calls = AiTools.parseCalls("[[TOOL]]{\"tool\":\"github_read_file\",\"args\":{\"repo\":\"a/b\",\"path\":\"X.kt\"}}[[/TOOL]]")
        assertEquals("X.kt", calls[0].args.getString("path"))
    }

    @Test fun truncatedStringArgsAreNeverSilentlyEmpty() {
        val o = ToolCallCodec.parseArgString("{\"path\":\"X.kt\",\"start_line\":5")
        assertTrue(o.length() > 0)
    }

    @Test fun hintMentionsArgsWrapperWhenEmpty() {
        assertTrue(ToolCallCodec.flatHint("file_read", emptyList()).contains("args"))
    }
}

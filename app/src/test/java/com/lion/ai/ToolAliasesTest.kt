package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAliasesTest {
    private val known = setOf(
        "file_read", "file_write", "file_edit", "file_list", "file_tree", "file_search",
        "execute_command", "todo_write", "file_delete", "github_read_file", "read_file"
    )

    @Test fun claudeCodeStyleNamesResolveToOurTools() {
        assertEquals("file_read", ToolAliases.resolve("read", known))
        assertEquals("file_write", ToolAliases.resolve("Write", known))
        assertEquals("file_edit", ToolAliases.resolve("Edit", known))
        assertEquals("execute_command", ToolAliases.resolve("Bash", known))
        assertEquals("file_search", ToolAliases.resolve("grep", known))
        assertEquals("todo_write", ToolAliases.resolve("TodoWrite", known))
        assertEquals("file_read", ToolAliases.resolve("functions.read", known))
    }

    @Test fun exactNamesAreUntouchedAndUnknownStaysUnknown() {
        assertEquals("file_read", ToolAliases.resolve("file_read", known))
        assertEquals("", ToolAliases.resolve("aea000000000", known))
        assertEquals("", ToolAliases.resolve("", known))
    }

    @Test fun aliasOnlyResolvesWhenTargetIsRegistered() {
        assertEquals("", ToolAliases.resolve("bash", setOf("file_read")))
    }

    @Test fun nativeCallIsCanonicalizedWithArgsPreserved() {
        val c = ToolAliases.canonical(ToolCall("read", JSONObject().put("file_path", "a.kt"), ""), known)
        assertEquals("file_read", c.name)
        assertEquals("a.kt", c.args.getString("file_path"))
    }

    @Test fun textProtocolAcceptsAliasNames() {
        val calls = ToolCallCodec.parse("[[TOOL]]{\"name\":\"Read\",\"args\":{\"file_path\":\"a.kt\"}}[[/TOOL]]", known)
        assertEquals(1, calls.size)
        assertEquals("file_read", calls[0].name)
    }

    @Test fun unknownMessageKeepsFirstLineAndSuggests() {
        val msg = ToolAliases.unknownMessage("file_reed", known)
        assertTrue(msg.lineSequence().first().startsWith("❌ أداة غير معروفة: file_reed"))
        assertTrue(msg.contains("هل تقصد") && msg.contains("tools_schema"))
        val garbage = ToolAliases.unknownMessage("aea000000000", known)
        assertTrue(garbage.lineSequence().first().startsWith("❌ أداة غير معروفة"))
    }

    @Test fun claudeCodeArgNamesAreNormalizedForFileRead() {
        val args = JSONObject().put("file_path", "src/A.kt").put("offset", 10)
        AiTools.normalizeArgs(args, "file_read")
        assertEquals("src/A.kt", args.getString("path"))
        assertEquals(10, args.getInt("start_line"))
    }
}

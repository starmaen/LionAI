package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeConfigTest {
    @Test fun `url becomes remote mcp and command becomes local array`() {
        var cfg = OpenCodeConfig.addMcp(JSONObject(), "docs", "https://example.com/mcp")
        cfg = OpenCodeConfig.addMcp(cfg, "fs", "npx -y server-filesystem /workspace")
        val list = OpenCodeConfig.mcpList(cfg).associateBy { it.name }
        assertEquals("remote", list.getValue("docs").type)
        assertEquals("https://example.com/mcp", list.getValue("docs").target)
        assertEquals("local", list.getValue("fs").type)
        assertEquals("npx -y server-filesystem /workspace", list.getValue("fs").target)
        assertTrue(list.getValue("fs").enabled)
        assertEquals(4, cfg.getJSONObject("mcp").getJSONObject("fs").getJSONArray("command").length())
    }

    @Test fun `existing config sections are preserved and input is not mutated`() {
        val base = JSONObject().put("theme", "dark").put("mcp", JSONObject().put("old", JSONObject().put("type", "remote").put("url", "https://a.b")))
        val next = OpenCodeConfig.addMcp(base, "new", "https://c.d")
        assertEquals("dark", next.getString("theme"))
        assertEquals(2, next.getJSONObject("mcp").length())
        assertEquals(1, base.getJSONObject("mcp").length())
    }

    @Test fun `removing the last entry drops the section`() {
        val cfg = OpenCodeConfig.addMcp(JSONObject(), "one", "https://x.y")
        val empty = OpenCodeConfig.removeMcp(cfg, "one")
        assertFalse(empty.has("mcp"))
    }

    @Test fun `invalid names and blank targets are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addMcp(JSONObject(), "Bad Name", "https://x.y") }
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addMcp(JSONObject(), "ok", "  ") }
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addAgent(JSONObject(), "ok", "", "prompt") }
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addAgent(JSONObject(), "ok", "desc", " ") }
    }

    @Test fun `agent is stored as subagent with prompt`() {
        val cfg = OpenCodeConfig.addAgent(JSONObject(), "reviewer", "يراجع الكود", "راجع التعديلات بدقة")
        val agent = cfg.getJSONObject("agent").getJSONObject("reviewer")
        assertEquals("subagent", agent.getString("mode"))
        assertEquals("راجع التعديلات بدقة", agent.getString("prompt"))
        assertEquals("reviewer", OpenCodeConfig.agentList(cfg).single().name)
        assertEquals("https://opencode.ai/config.json", cfg.getString("\$schema"))
    }

    @Test fun `remote server gets headers and local server gets environment`() {
        var cfg = OpenCodeConfig.addMcp(JSONObject(), "docs", "https://example.com/mcp", "Authorization: Bearer abc:123\nX-Key=zzz")
        cfg = OpenCodeConfig.addMcp(cfg, "mem", "npx -y server-memory", "MEMORY_FILE_PATH=/workspace/memory.jsonl")
        val docs = cfg.getJSONObject("mcp").getJSONObject("docs")
        assertEquals("Bearer abc:123", docs.getJSONObject("headers").getString("Authorization"))
        assertEquals("zzz", docs.getJSONObject("headers").getString("X-Key"))
        val mem = cfg.getJSONObject("mcp").getJSONObject("mem")
        assertEquals("/workspace/memory.jsonl", mem.getJSONObject("environment").getString("MEMORY_FILE_PATH"))
        assertFalse(mem.has("headers"))
    }

    @Test fun `malformed extras line is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenCodeConfig.addMcp(JSONObject(), "x", "https://a.b", "nocolonhere")
        }
    }
}

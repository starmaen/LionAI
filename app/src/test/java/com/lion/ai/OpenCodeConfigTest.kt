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

    @Test fun `custom provider is written in opencode format and can be removed`() {
        var cfg = OpenCodeConfig.addProvider(JSONObject(), "myplat", "My Platform", "https://api.x.com/v1/chat/completions/", "sk-1", "m-a, m-b ,m-a")
        cfg = OpenCodeConfig.addProvider(cfg, "other", "Other", "https://o.y/v1", "", "z")
        val p = cfg.getJSONObject("provider").getJSONObject("myplat")
        assertEquals("@ai-sdk/openai-compatible", p.getString("npm"))
        assertEquals("https://api.x.com/v1", p.getJSONObject("options").getString("baseURL"))
        assertEquals("sk-1", p.getJSONObject("options").getString("apiKey"))
        assertEquals(listOf("m-a", "m-b"), OpenCodeConfig.providerList(cfg).first { it.id == "myplat" }.models.sorted())
        assertFalse(cfg.getJSONObject("provider").getJSONObject("other").getJSONObject("options").has("apiKey"))
        assertEquals(2, OpenCodeConfig.providerList(cfg).size)
        val after = OpenCodeConfig.removeProvider(cfg, "myplat")
        assertEquals(listOf("other"), OpenCodeConfig.providerList(after).map { it.id })
    }

    @Test fun `custom provider requires models and https url`() {
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addProvider(JSONObject(), "p", "P", "https://a.b/v1", "", " ") }
        assertThrows(IllegalArgumentException::class.java) { OpenCodeConfig.addProvider(JSONObject(), "p", "P", "ftp://a.b", "", "m") }
    }

    @Test fun `host phone paths become guest paths and local mcp gets a long timeout`() {
        val cfg = OpenCodeConfig.addMcp(
            JSONObject(), "memory",
            "/data/user/0/com.lion.ai/files//workspace/tools/node/bin/node /workspace/x.js",
            "MEMORY_FILE_PATH=/data/user/0/com.lion.ai/files/workspace/m.jsonl"
        )
        val e = cfg.getJSONObject("mcp").getJSONObject("memory")
        assertEquals("/workspace/tools/node/bin/node", e.getJSONArray("command").getString(0))
        assertEquals("/workspace/m.jsonl", e.getJSONObject("environment").getString("MEMORY_FILE_PATH"))
        assertEquals(OpenCodeConfig.LOCAL_MCP_TIMEOUT_MS, e.getInt("timeout"))
    }

    @Test fun `normalize repairs old entries without timeout`() {
        val old = JSONObject().put("mcp", JSONObject().put("m", JSONObject()
            .put("type", "local").put("enabled", true)
            .put("command", org.json.JSONArray(listOf("/data/user/0/com.lion.ai/files//workspace/tools/node/bin/node", "a.js")))))
        val fixed = OpenCodeConfig.normalizeMcp(old).getJSONObject("mcp").getJSONObject("m")
        assertEquals("/workspace/tools/node/bin/node", fixed.getJSONArray("command").getString(0))
        assertEquals(OpenCodeConfig.LOCAL_MCP_TIMEOUT_MS, fixed.getInt("timeout"))
    }
}

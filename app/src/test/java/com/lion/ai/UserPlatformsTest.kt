package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPlatformsTest {
    @Test fun `slug keeps ascii letters and digits only`() {
        assertEquals("together-ai", UserPlatforms.slug("Together AI"))
        assertEquals("", UserPlatforms.slug("منصة"))
    }

    @Test fun `models accept commas arabic commas and newlines without duplicates`() {
        assertEquals(listOf("a", "b", "c"), UserPlatforms.parseModels("a, b،c\na"))
    }

    @Test fun `add stores platform and key then remove deletes both`() {
        val prefs = FakePrefs()
        val e = UserPlatforms.add(prefs, "Together AI", "https://api.together.xyz/v1", " sk-1 ", "m1,m2")
        assertEquals("usr_together-ai", e.id)
        assertEquals(listOf("m1", "m2"), UserPlatforms.list(prefs).single().models)
        assertEquals("sk-1", prefs.getString("api_key_usr_together-ai", ""))
        val info = UserPlatforms.toInfo(UserPlatforms.list(prefs).single())
        assertEquals("m1", info.defaultModel)
        assertEquals("https://api.together.xyz/v1", info.endpoint)
        assertEquals(ProviderGroup.API, info.group)
        assertFalse(info.custom)
        UserPlatforms.remove(prefs, e.id)
        assertTrue(UserPlatforms.list(prefs).isEmpty())
        assertEquals("", prefs.getString("api_key_usr_together-ai", ""))
    }

    @Test fun `duplicate name and unsafe url are rejected`() {
        val prefs = FakePrefs()
        UserPlatforms.add(prefs, "X", "https://a.b/v1", "", "")
        assertThrows(IllegalArgumentException::class.java) { UserPlatforms.add(prefs, "x", "https://c.d/v1", "", "") }
        assertThrows(IllegalArgumentException::class.java) { UserPlatforms.add(prefs, "Y", "http://evil.example.com/v1", "", "") }
        assertThrows(IllegalArgumentException::class.java) { UserPlatforms.add(prefs, "", "https://a.b", "", "") }
    }

    @Test fun `blank key marks the platform keyless and survives serialization`() {
        val prefs = FakePrefs()
        UserPlatforms.add(prefs, "Local Ollama", "http://192.168.1.5:11434/v1", "", "llama3")
        val e = UserPlatforms.list(prefs).single()
        assertTrue(e.keyless)
        assertTrue(UserPlatforms.toInfo(e).keyless)
    }

    @Test fun `model ids parse from openai style and plain array responses`() {
        assertEquals(listOf("a", "b"), UserPlatforms.parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
        assertEquals(listOf("x", "y"), UserPlatforms.parseModelIds("""["y","x"]"""))
        assertTrue(UserPlatforms.parseModelIds("""{"object":"list"}""").isEmpty())
    }
}

package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenCodeLocalTest {
    @Test fun `provider and model ids are separated only at first slash`() {
        assertEquals("opencode" to "big-pickle", OpenCodeLocal.splitModel("opencode/big-pickle"))
        assertEquals("apinex" to "free/kimi-k3", OpenCodeLocal.splitModel("apinex/free/kimi-k3"))
    }

    @Test fun `invalid local model is rejected before request`() {
        assertThrows(IllegalArgumentException::class.java) { OpenCodeLocal.splitModel("big-pickle") }
    }

    @Test fun `apinex and official local client are distinct providers`() {
        val apinex = PROVIDERS.single { it.id == "apinex" }
        val local = PROVIDERS.single { it.id == "opencode-local" }
        assert(apinex.models.any { it.startsWith("free/") })
        assert(local.keyless)
        assert(local.defaultModel.contains('/'))
    }
}

package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRemovalTest {
    @Test fun parseAndSerializeRoundTrip() {
        val ids = ProviderRemoval.parse(" b ,a,,b ")
        assertEquals(setOf("a", "b"), ids)
        assertEquals("a,b", ProviderRemoval.serialize(ids))
    }

    @Test fun withoutContextNothingIsRemovedAndResolveKeepsIds() {
        assertTrue(ProviderRemoval.removedIds().isEmpty())
        assertEquals("gemini", ProviderRemoval.resolve("gemini"))
        assertTrue(ProviderRemoval.resolve("").isNotBlank())
        assertTrue(PROVIDERS.isNotEmpty())
    }
}

package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPlatformsUrlTest {
    @Test fun `dashboard and trailing slash are cleaned`() {
        assertEquals("https://freemodel.in", UserPlatforms.sanitizeBaseUrl("https://freemodel.in/dashboard"))
        assertEquals("https://freemodel.in/v1", UserPlatforms.sanitizeBaseUrl(" https://freemodel.in/v1/ "))
        assertEquals("https://api.together.xyz/v1", UserPlatforms.sanitizeBaseUrl("https://api.together.xyz/v1?x=1"))
    }

    @Test fun `models url follows chat v1 normalisation`() {
        assertEquals("https://freemodel.in/v1/models", UserPlatforms.modelsUrls("https://freemodel.in/v1").first())
        assertEquals("https://freemodel.in/v1/models", UserPlatforms.modelsUrls("https://freemodel.in/dashboard").first())
    }
}

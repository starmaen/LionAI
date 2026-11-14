package com.lion.ai

import org.junit.Assert.*
import org.junit.Test

class WebPageLoadTest {
    @Test fun requestedUrlIsNotReadyUntilDocumentCommits() {
        val load = WebPageLoad()
        assertEquals(WebPageLoad.Phase.NEW, load.phase)
        load.start("https://arena.ai/text/direct", 100)
        assertEquals(WebPageLoad.Phase.LOADING, load.phase)
        load.commit("https://arena.ai/text/direct", true)
        assertEquals(WebPageLoad.Phase.READY, load.phase)
    }
    @Test fun pageFinishedDoesNotEraseNetworkOrHttpError() {
        val load = WebPageLoad()
        load.start("https://arena.ai/")
        load.fail("HTTP 403")
        load.commit("https://arena.ai/", true)
        assertEquals(WebPageLoad.Phase.FAILED, load.phase)
        assertEquals("HTTP 403", load.error)
    }
    @Test fun explicitRetryResetsFailureBeforeLoadingAgain() {
        val load = WebPageLoad()
        load.start("https://arena.ai/"); load.fail("DNS")
        load.start("https://arena.ai/", 500)
        assertEquals(WebPageLoad.Phase.LOADING, load.phase)
        assertEquals("", load.error)
        load.commit("https://arena.ai/", true)
        assertEquals(WebPageLoad.Phase.READY, load.phase)
    }
    @Test fun authRedirectIsNotAcceptedAsChatPage() {
        val load = WebPageLoad()
        load.start("https://accounts.google.com/login?code=secret")
        load.commit("https://accounts.google.com/login?code=secret", false)
        assertEquals(WebPageLoad.Phase.REDIRECT, load.phase)
        assertEquals("https://accounts.google.com", load.origin)
    }
    @Test fun loadTimeoutOnlyAppliesWhileLoading() {
        val load = WebPageLoad()
        load.start("https://arena.ai/", 100)
        assertFalse(load.loadExpired(30099))
        assertTrue(load.loadExpired(30100))
        load.commit("https://arena.ai/", true)
        assertFalse(load.loadExpired(90000))
    }
    @Test fun diagnosticsNeverExposeCredentialsPathOrQuery() {
        assertEquals("https://example.com:8443", WebPageLoad.originOf("https://user:password@example.com:8443/private?token=secret#code"))
        assertEquals("about:blank", WebPageLoad.originOf("about:blank"))
        assertEquals("chrome-error://chromewebdata", WebPageLoad.originOf("chrome-error://chromewebdata/"))
        assertEquals("unknown", WebPageLoad.originOf("not a url"))
    }
}

package com.lion.ai

import java.net.URI

/** Pure navigation state. A requested URL is not proof that its document has loaded. */
internal class WebPageLoad {
    enum class Phase { NEW, LOADING, READY, REDIRECT, FAILED }
    var phase = Phase.NEW
        private set
    var origin = "unknown"
        private set
    var error = ""
        private set
    var startedAt = 0L
        private set

    fun start(url: String, now: Long = System.currentTimeMillis()) {
        phase = Phase.LOADING
        origin = originOf(url)
        error = ""
        startedAt = now
    }
    fun commit(url: String, allowed: Boolean) {
        // onPageFinished also fires after HTTP/network failures; it must not erase an error.
        if (phase == Phase.FAILED) return
        origin = originOf(url)
        phase = if (allowed) Phase.READY else Phase.REDIRECT
    }
    fun fail(message: String) { phase = Phase.FAILED; error = message }
    fun loadExpired(now: Long, timeoutMs: Long = 30_000): Boolean =
        phase == Phase.LOADING && now - startedAt >= timeoutMs

    companion object {
        /** Never log login codes, query strings, path contents, user-info or cookies. */
        fun originOf(raw: String): String = runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching "unknown"
            val host = uri.host?.lowercase()
            if (host != null) "$scheme://$host" + if (uri.port >= 0) ":${uri.port}" else ""
            else if (scheme == "about" && raw == "about:blank") "about:blank" else "$scheme:"
        }.getOrDefault("unknown")
    }
}

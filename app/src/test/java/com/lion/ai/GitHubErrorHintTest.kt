package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubErrorHintTest {
    @Test fun rateLimitIsNotConfusedWithPermission() {
        assertTrue("حد المعدل" in GitHubErrorHint.explain(403, "API rate limit exceeded"))
        assertTrue("حد المعدل" in GitHubErrorHint.explain(429, "slow down"))
        assertTrue("الصلاحية" in GitHubErrorHint.explain(403, "Resource not accessible by personal access token"))
    }

    @Test fun shaConflictDetected() {
        assertTrue(GitHubErrorHint.isShaConflict(409, "contents/x.kt does not match abc123"))
        assertTrue(GitHubErrorHint.isShaConflict(422, "Invalid request. \"sha\" wasn't supplied."))
        assertFalse(GitHubErrorHint.isShaConflict(422, "Reference already exists"))
        assertFalse(GitHubErrorHint.isShaConflict(404, "sha"))
        assertTrue("تعارض إصدار" in GitHubErrorHint.explain(422, "\"sha\" wasn't supplied"))
    }

    @Test fun commonStatusesHaveActionableHints() {
        assertTrue("التوكن" in GitHubErrorHint.explain(401, "Bad credentials"))
        assertTrue("404" in GitHubErrorHint.explain(404, "Not Found") || "غير موجود" in GitHubErrorHint.explain(404, "Not Found"))
        assertTrue("مؤقت" in GitHubErrorHint.explain(503, "unavailable"))
        assertTrue(GitHubErrorHint.explain(418, "teapot").isEmpty())
    }
}

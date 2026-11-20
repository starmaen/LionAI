package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * يقفل إصلاح 404 في github_push_workspace: تمرير مسار مساحة عمل مثل repos/owner/repo
 * كان يُفسَّر owner="repos" وrepo="owner" فيفشل كل استدعاء برغم صحة المستودع.
 */
class GitHubRepoTargetTest {

    @Test
    fun parsesPlainOwnerRepo() {
        assertEquals("starmaen" to "LionAI", GitHubClient.parseFullName("starmaen/LionAI"))
    }

    @Test
    fun stripsWorkspacePrefixAndGitSuffix() {
        assertEquals("starmaen" to "LionAI", GitHubClient.parseFullName("repos/starmaen/LionAI"))
        assertEquals("starmaen" to "LionAI", GitHubClient.parseFullName("repos/starmaen/LionAI/"))
        assertEquals("starmaen" to "LionAI", GitHubClient.parseFullName("https://github.com/starmaen/LionAI.git"))
        assertEquals("starmaen" to "LionAI", GitHubClient.parseFullName("  workspace/starmaen/LionAI  "))
    }

    @Test
    fun rejectsMalformedNamesWithArabicReason() {
        val e = runCatching { GitHubClient.parseFullName("repos") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("owner/repo"))
        val bad = runCatching { GitHubClient.parseFullName("starmaen/Lion AI") }.exceptionOrNull()
        assertTrue(bad is IllegalArgumentException)
    }

    @Test
    fun riskPolicyDoesNotAskForOrdinaryPush() {
        val call = ToolCall("github_push_workspace", JSONObject("""{"repo":"a/b","path":"repos/a/b","dry_run":true}"""))
        assertNull(RiskPolicy.assess(call))
    }

    @Test
    fun permissionCardExplainsReasonTargetAndScope() {
        val call = ToolCall("github_push_workspace", JSONObject("""{"repo":"starmaen/LionAI","branch":"main","path":"repos/starmaen/LionAI"}"""))
        val card = PermissionReason.writeCard(call)
        assertTrue(card.contains("starmaen/LionAI"))
        assertTrue(card.contains("لماذا الآن"))
        assertTrue(card.contains("نطاق الإذن"))
        assertTrue(card.contains("لو رفضت"))
        assertTrue(card.contains("هذه المهمة"))
    }
}

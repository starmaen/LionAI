package com.lion.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** اختبارات على جهاز/محاكي حقيقي: org.json الفعلي من أندرويد، ومخزن الإشعارات SQLite، وتحقق وسائط الأدوات. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appPackageIsLionAi() {
        assertEquals("com.lion.ai", context.packageName)
    }

    @Test
    fun githubGetRunAcceptsMissingRunId() {
        assertNull(AiTools.validateArgs(ToolCall("github_get_run", JSONObject().put("repo", "owner/name"))))
    }

    @Test
    fun githubRerunStillRequiresRunId() {
        assertNotNull(AiTools.validateArgs(ToolCall("github_rerun", JSONObject())))
    }

    @Test
    fun liveStreamParsesBothOpenAiAndGemini() {
        assertEquals("a", LiveStream.parseLine("""data: {"choices":[{"delta":{"content":"a"}}]}"""))
        assertEquals("b", LiveStream.parseGeminiLine("""data: {"candidates":[{"content":{"parts":[{"text":"b"}]}}]}"""))
    }

    @Test
    fun notificationStoreStartsAndCounts() {
        NotificationStore.init(context)
        assertTrue(NotificationStore.count(context) >= 0)
    }
}

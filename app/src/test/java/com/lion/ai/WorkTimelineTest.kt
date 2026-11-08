package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkTimelineTest {
    @Test
    fun timelineKeepsProviderResultsAndPermissionStates() {
        val steps = listOf(
            AgentStep("file_read", true, "قرأ الملف", "Main.kt"),
            AgentStep("provider", true, "انتقل التنفيذ إلى Claude"),
            AgentStep("note", true, "بانتظار رد المستخدم على إذن الكتابة")
        )
        val visuals = buildWorkVisuals(emptyList(), steps)
        assertEquals(WorkVisualState.SUCCESS, visuals[0].state)
        assertEquals(WorkVisualState.PERMISSION, visuals[1].state)
        assertEquals(2, visuals.size)
    }

    @Test
    fun activeOperationIsAppendedWithoutErasingSavedWork() {
        val saved = listOf(AgentStep("write_file", true, "تم", "a.kt"))
        val visuals = buildWorkVisuals(listOf("✅ write_file", "⏳ 🛠️ shell_exec ← ./gradlew test"), saved)
        assertEquals(2, visuals.size)
        assertEquals(WorkVisualState.SUCCESS, visuals.first().state)
        assertEquals(WorkVisualState.RUNNING, visuals.last().state)
    }
}

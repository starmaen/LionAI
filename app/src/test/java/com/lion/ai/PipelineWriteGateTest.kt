package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يقفل إصلاح «لا يوجد Manifest / لا يمكن تجاوز البوابة» في المشاريع القائمة. */
class PipelineWriteGateTest {

    @Test
    fun existingProjectWithoutManifestOrPipelineCanWrite() {
        val prefs = FakePrefs()
        assertTrue(PipelineStore.canWrite(prefs, "chat-1"))
    }

    @Test
    fun blankChatIdCanWrite() {
        assertTrue(PipelineStore.canWrite(FakePrefs(), ""))
    }

    @Test
    fun unapprovedManifestStillBlocksWrites() {
        val prefs = FakePrefs()
        ManifestStore.write(prefs, "chat-2", BuildManifest(project = "p", approved = false))
        assertFalse(PipelineStore.canWrite(prefs, "chat-2"))
    }

    @Test
    fun planPhaseBlocksButImplementAndDoneAllow() {
        val prefs = FakePrefs()
        PipelineStore.write(prefs, PipelineState("chat-3", "p", "", PipelinePhase.PLAN, manifestApproved = false))
        assertFalse(PipelineStore.canWrite(prefs, "chat-3"))
        PipelineStore.write(prefs, PipelineState("chat-3", "p", "", PipelinePhase.IMPLEMENT, manifestApproved = true))
        assertTrue(PipelineStore.canWrite(prefs, "chat-3"))
        PipelineStore.write(prefs, PipelineState("chat-3", "p", "", PipelinePhase.DONE, manifestApproved = true))
        assertTrue(PipelineStore.canWrite(prefs, "chat-3"))
    }
}

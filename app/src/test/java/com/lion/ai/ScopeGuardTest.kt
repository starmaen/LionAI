package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeGuardTest {
    @Test
    fun singleLookupRequestsAreScoped() {
        assertTrue(ScopeGuard.isLookup("اريد اخر بناء ناجح على غيت هاب"))
        assertTrue(ScopeGuard.isLookup("أعطني آخر بناء ناجح"))
        assertTrue(ScopeGuard.isLookup("show me the latest successful build"))
        assertTrue(ScopeGuard.isLookup("اعرض حالة الفرع main"))
    }

    @Test
    fun actionRequestsAreNeverScopedAsLookup() {
        assertFalse(ScopeGuard.isLookup("اعرض آخر بناء ثم ارفع التعديلات"))
        assertFalse(ScopeGuard.isLookup("عدل الملف src/Main.kt واحفظه"))
        assertFalse(ScopeGuard.isLookup("اريد ان تصلح الخطأ في المشروع"))
        assertFalse(ScopeGuard.isLookup("show the build then push the fix"))
        assertFalse(ScopeGuard.isLookup("نفذ"))
        assertFalse(ScopeGuard.isLookup(""))
    }

    @Test
    fun lookupBlocksWritesButExplicitFollowUpDoesNot() {
        assertTrue(WriteIntent.isReadOnlyRequest("اريد اخر بناء ناجح"))
        assertFalse(WriteIntent.isReadOnlyRequest("نفذ"))
    }

    @Test
    fun progressNoteOnlyOnInterval() {
        assertTrue(ScopeGuard.progressNote(6).contains("تقريراً مرحلياً"))
        assertTrue(ScopeGuard.progressNote(5).isEmpty())
        assertTrue(ScopeGuard.progressNote(0).isEmpty())
    }
}

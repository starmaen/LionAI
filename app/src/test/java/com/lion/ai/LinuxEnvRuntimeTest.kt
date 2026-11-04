package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LinuxEnvRuntimeTest {
    @Test
    fun prootProcessSearchesTheApkNativeLibraryDirectory() {
        val nativeDir = "/data/app/com.lion.ai/lib/arm64"
        val environment = prootProcessEnvironment(nativeDir)

        assertEquals(nativeDir, environment["LD_LIBRARY_PATH"])
        assertFalse(environment["LD_LIBRARY_PATH"].isNullOrBlank())
    }
}

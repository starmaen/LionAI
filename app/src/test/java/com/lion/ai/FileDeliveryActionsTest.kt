package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileDeliveryActionsTest {
    @Test
    fun deliveryIncludesOpenShareAndSafeLocalLinks() {
        assertEquals("application/pdf", FileDelivery.mime("report.pdf"))
        assertEquals("application/vnd.android.package-archive", FileDelivery.mime("app.apk"))
        assertTrue(FileDelivery.canRunInTermux(File("app.apk")))
        assertTrue(FileDelivery.canRunInTermux(File("script.sh")))
        assertFalse(FileDelivery.canRunInTermux(File("report.pdf")))
    }
}

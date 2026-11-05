package com.lion.ai

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class BootImageToolsTest {
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    @Test fun v2HeaderRoundTripKeepsEveryComponent() {
        val h = JSONObject().put("header_version", 2).put("page_size", 4096L)
            .put("kernel_addr", 0x8000L).put("ramdisk_addr", 0x1000000L).put("second_addr", 0L)
            .put("tags_addr", 0x100L).put("os_version", 0x1234L).put("name", "lion")
            .put("cmdline", "console=ttyS0 androidboot.selinux=permissive").put("extra_cmdline", "")
            .put("dtb_addr", 0x2000L)
        val kernel = bytes(5000, 1); val ramdisk = bytes(3000, 2); val second = ByteArray(0)
        val dtbo = bytes(700, 3); val dtb = bytes(1200, 4)
        val image = BootImageTools.buildImage(h, kernel, ramdisk, second, dtbo, dtb, ByteArray(0))
        assertEquals(0, image.size % 4096)
        val p = BootImageTools.parse(image)
        assertArrayEquals(kernel, p.kernel); assertArrayEquals(ramdisk, p.ramdisk)
        assertArrayEquals(dtbo, p.dtbo); assertArrayEquals(dtb, p.dtb)
        assertEquals("console=ttyS0 androidboot.selinux=permissive", p.header.getString("cmdline"))
        assertEquals("lion", p.header.getString("name"))
    }

    @Test fun v3AndV4RoundTrip() {
        for (v in listOf(3, 4)) {
            val h = JSONObject().put("header_version", v).put("page_size", 4096L)
                .put("os_version", 7L).put("cmdline", "bootconfig").put("extra_cmdline", "")
            val kernel = bytes(4100, 5); val ramdisk = bytes(10, 6); val sig = if (v >= 4) bytes(300, 7) else ByteArray(0)
            val image = BootImageTools.buildImage(h, kernel, ramdisk, ByteArray(0), ByteArray(0), ByteArray(0), sig)
            val p = BootImageTools.parse(image)
            assertArrayEquals(kernel, p.kernel); assertArrayEquals(ramdisk, p.ramdisk); assertArrayEquals(sig, p.signature)
            assertEquals("bootconfig", p.header.getString("cmdline"))
        }
    }

    @Test fun cpioNewcRoundTripPreservesModes() {
        val entries = listOf(
            BootImageTools.Cpio(".", 0x41EDL, 0, 0, 2, 0, 0, 0, 0, 0, ByteArray(0)),
            BootImageTools.Cpio("init", 0x81EDL, 0, 0, 1, 100, 0, 0, 0, 0, "#!/bin/sh\n".toByteArray()),
            BootImageTools.Cpio("system", 0x41EDL, 0, 0, 2, 0, 0, 0, 0, 0, ByteArray(0)),
            BootImageTools.Cpio("sbin/link", 0xA1FFL, 0, 0, 1, 0, 0, 0, 0, 0, "/init".toByteArray())
        )
        val parsed = BootImageTools.parseCpio(BootImageTools.buildCpio(entries))
        assertEquals(entries.map { it.name }, parsed.map { it.name })
        assertEquals(entries.map { it.mode }, parsed.map { it.mode })
        assertTrue(String(parsed[1].data).startsWith("#!/bin/sh"))
        assertEquals("/init", String(parsed[3].data))
    }

    @Test fun rejectsNonBootImages() {
        val result = runCatching { BootImageTools.parse(ByteArray(4000)) }
        assertTrue(result.isFailure)
    }

    @Test fun streamingRamdiskExtractWritesFilesAndMeta() {
        val entries = listOf(
            BootImageTools.Cpio(".", 0x41EDL, 0, 0, 2, 0, 0, 0, 0, 0, ByteArray(0)),
            BootImageTools.Cpio("init", 0x81EDL, 0, 0, 1, 100, 0, 0, 0, 0, "#!/bin/sh\n".toByteArray()),
            BootImageTools.Cpio("sbin", 0x41EDL, 0, 0, 2, 0, 0, 0, 0, 0, ByteArray(0)),
            BootImageTools.Cpio("sbin/init", 0x81EDL, 0, 0, 1, 0, 0, 0, 0, 0, "x".toByteArray())
        )
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(BootImageTools.buildCpio(entries)) }
        }.toByteArray()
        val dir = File.createTempFile("lion-rd", "").apply { delete(); mkdirs() }
        try {
            val raw = File(dir, "ramdisk.raw")
            raw.writeBytes(gz)
            val message = BootImageTools.extractRamdisk(raw, dir)
            assertTrue(message.contains("4 عنصراً"))
            assertEquals("#!/bin/sh\n", File(dir, "ramdisk/init").readText())
            assertEquals("x", File(dir, "ramdisk/sbin/init").readText())
            assertTrue(File(dir, "ramdisk.meta.json").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun fileHeaderReadsOnlyHeaderAndKeepsPartOffsets() {
        val h = JSONObject().put("header_version", 2).put("page_size", 4096L)
            .put("kernel_addr", 0x8000L).put("ramdisk_addr", 0x1000000L).put("second_addr", 0L)
            .put("tags_addr", 0x100L).put("os_version", 0x1234L).put("name", "lion")
            .put("cmdline", "console=ttyS0").put("extra_cmdline", "").put("dtb_addr", 0x2000L)
        val kernel = bytes(9000, 11); val ramdisk = bytes(5000, 12); val dtbo = bytes(300, 13)
        val image = BootImageTools.buildImage(h, kernel, ramdisk, ByteArray(0), dtbo, ByteArray(0), ByteArray(0))
        val file = File.createTempFile("lion-boot", ".img")
        try {
            file.writeBytes(image)
            val meta = BootImageTools.headerOf(file)
            assertEquals(kernel.size.toLong(), meta.kernel.size)
            assertEquals(ramdisk.size.toLong(), meta.ramdisk.size)
            assertEquals(dtbo.size.toLong(), meta.dtbo.size)
            assertTrue(meta.ramdisk.offset >= meta.kernel.offset + meta.kernel.size)
            assertEquals(2, meta.header.getInt("header_version"))
        } finally {
            file.delete()
        }
    }
}

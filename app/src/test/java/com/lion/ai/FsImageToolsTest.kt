package com.lion.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FsImageToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun sparseToRawHandlesRawFillAndDontCareChunks() {
        val block = 4096
        val raw = ByteArray(block) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val head = le(28).putInt(0xED26FF3A.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(block).putInt(4).putInt(3).putInt(0)
        out.write(head.array())
        out.write(le(12).putShort(0xCAC1.toShort()).putShort(0).putInt(1).putInt(12 + block).array()); out.write(raw)
        out.write(le(12).putShort(0xCAC2.toShort()).putShort(0).putInt(2).putInt(16).array()); out.write(byteArrayOf(7, 7, 7, 7))
        out.write(le(12).putShort(0xCAC3.toShort()).putShort(0).putInt(1).putInt(12).array())
        val src = tmp.newFile("a.sparse").also { it.writeBytes(out.toByteArray()) }
        val dst = tmp.newFile("a.raw")
        assertTrue(FsImageTools.isSparse(src))
        assertEquals(4L * block, FsImageTools.sparseToRaw(src, dst))
        val result = dst.readBytes()
        assertArrayEquals(raw, result.copyOfRange(0, block))
        assertTrue(result.copyOfRange(block, 3 * block).all { it == 7.toByte() })
        assertTrue(result.copyOfRange(3 * block, 4 * block).all { it == 0.toByte() })
    }

    @Test fun detectsExt4AndErofsAndReadsExt4Superblock() {
        val ext = ByteArray(4096)
        val b = ByteBuffer.wrap(ext).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1024 + 4, 25600).putInt(1024 + 24, 2).putInt(1024 + 92, 0x4)
        b.putShort(1080, 0xEF53.toShort())
        "vendor".toByteArray().copyInto(ext, 1024 + 120)
        val extFile = tmp.newFile("e.img").also { it.writeBytes(ext) }
        assertEquals(FsImageTools.Format.EXT4, FsImageTools.detect(extFile))
        val info = FsImageTools.ext4Info(extFile)
        assertEquals("vendor", info.label); assertEquals(4096L, info.blockSize)
        assertEquals(25600L, info.blocks); assertTrue(info.journal)

        val ero = ByteArray(4096)
        ByteBuffer.wrap(ero).order(ByteOrder.LITTLE_ENDIAN).putInt(1024, 0xE0F5E1E2.toInt())
        assertEquals(FsImageTools.Format.EROFS, FsImageTools.detect(tmp.newFile("r.img").also { it.writeBytes(ero) }))
        assertEquals(FsImageTools.Format.UNKNOWN, FsImageTools.detect(tmp.newFile("u.img").also { it.writeBytes(ByteArray(4096)) }))
    }

    @Test fun rejectsFakeSparse() {
        val f = tmp.newFile("x.img").also { it.writeBytes(ByteArray(64)) }
        assertTrue(!FsImageTools.isSparse(f))
        assertTrue(runCatching { FsImageTools.sparseToRaw(f, tmp.newFile("y.raw")) }.isFailure)
    }
}

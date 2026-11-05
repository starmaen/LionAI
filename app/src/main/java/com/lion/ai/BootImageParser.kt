package com.lion.ai

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BootImageParser {
    data class BootImage(val kernel: ByteArray?, val ramdisk: ByteArray?, 
                         val second: ByteArray?, val dtb: ByteArray?, val header: BootHeader)
    data class BootHeader(val magic: String, val kernelSize: Int, val kernelAddr: Int,
                          val ramdiskSize: Int, val ramdiskAddr: Int, val secondSize: Int,
                          val secondAddr: Int, val tagsAddr: Int, val pageSize: Int, val version: Int)

    fun parse(imagePath: String): Result<BootImage> = runCatching {
        FileInputStream(imagePath).use { fis ->
            val hdr = ByteArray(2048).also { fis.read(it) }
            val magic = String(hdr.take(8).toByteArray()).trimEnd('\u0000')
            val header = BootHeader(magic, readInt(hdr,8), readInt(hdr,12), readInt(hdr,16),
                readInt(hdr,20), readInt(hdr,24), readInt(hdr,28), readInt(hdr,32),
                readInt(hdr,36), readInt(hdr,40))
            val kernel = if (header.kernelSize > 0) readSection(fis, header.kernelSize, header.pageSize) else null
            val ramdisk = if (header.ramdiskSize > 0) readSection(fis, header.ramdiskSize, header.pageSize) else null
            val second = if (header.secondSize > 0) readSection(fis, header.secondSize, header.pageSize) else null
            BootImage(kernel, ramdisk, second, null, header)
        }
    }

    fun build(boot: BootImage, outputPath: String): Result<File> = runCatching {
        throw UnsupportedOperationException("بناء boot.img غير مدعوم بعد: الترويسة الصفرية تنتج صورة غير قابلة للإقلاع. استخدم mkbootimg.")
    }

    private fun readInt(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) or ((d[o+1].toInt() and 0xFF) shl 8) or
        ((d[o+2].toInt() and 0xFF) shl 16) or ((d[o+3].toInt() and 0xFF) shl 24))

    private fun readSection(fis: FileInputStream, size: Int, ps: Int): ByteArray {
        val data = ByteArray(size).also { fis.read(it) }
        if (size % ps > 0) fis.skip((ps - size % ps).toLong())
        return data
    }
}
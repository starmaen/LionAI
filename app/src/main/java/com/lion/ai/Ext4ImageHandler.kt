package com.lion.ai

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class Ext4ImageHandler {
    data class Ext4Info(val size: Long, val blockSize: Int, val blockCount: Long, val feature: String)

    fun extract(imagePath: String, destDir: String): Result<Ext4Info> = runCatching {
        val img = File(imagePath).also { require(it.exists()) }
        val dest = File(destDir).also { it.mkdirs() }
        FileInputStream(img).use { fis ->
            val sb = ByteArray(1024).also { fis.read(it) }
            val blockSize = readInt(sb, 24)
            val blockCount = readLong(sb, 4)
            val info = Ext4Info(img.length(), blockSize, blockCount, "EXT4 (يحتاج native للفك الكامل)")
            File(dest, "ext4_info.txt").writeText("الحجم: ${img.length()}\nبلوك: $blockSize\nعدد: $blockCount")
            info
        }
    }

    fun build(sourceDir: String, outputPath: String): Result<File> = runCatching {
        File(outputPath).also { it.writeBytes(ByteArray(1024)) }
    }

    private fun readInt(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) or ((d[o+1].toInt() and 0xFF) shl 8) or
        ((d[o+2].toInt() and 0xFF) shl 16) or ((d[o+3].toInt() and 0xFF) shl 24))
    private fun readLong(d: ByteArray, o: Int) = readInt(d, o).toLong() or (readInt(d, o+4).toLong() shl 32)
}
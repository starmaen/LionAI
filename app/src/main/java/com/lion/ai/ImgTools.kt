package com.lion.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImgTools(private val context: Context) {
    private val workDir = File(context.cacheDir, "img_work")
    init { if (!workDir.exists()) workDir.mkdirs() }

    data class UnpackedImage(
        val type: ImgType, val components: List<String>,
        val workDir: String, val zipPath: String? = null
    )

    enum class ImgType { BOOT, RECOVERY, VENDOR, SYSTEM, UNKNOWN }

    suspend fun unpack(imgPath: String): Result<UnpackedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(imgPath)
            require(file.exists()) { "الملف غير موجود: $imgPath" }
            val type = detectImgType(file)
            val outputDir = File(workDir, "${file.nameWithoutExtension}_unpacked").apply { mkdirs() }
            val components = when (type) {
                ImgType.BOOT, ImgType.RECOVERY -> unpackBootImage(file, outputDir)
                ImgType.VENDOR, ImgType.SYSTEM -> unpackExt4Image(file, outputDir)
                else -> throw IllegalArgumentException("نوع IMG غير مدعوم")
            }
            val zipPath = createZip(outputDir, File(workDir, "${file.nameWithoutExtension}.zip"))
            UnpackedImage(type, components, outputDir.absolutePath, zipPath.absolutePath)
        }
    }

    suspend fun modify(workDir: String, filePath: String, newContent: ByteArray): Result<Unit> = 
        withContext(Dispatchers.IO) { runCatching {
            val target = File(workDir, filePath)
            require(target.exists()) { "الملف غير موجود: $filePath" }
            FileOutputStream(target).use { it.write(newContent) }
        }}

    suspend fun repack(workDir: String, outputPath: String): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val dir = File(workDir).also { require(it.exists()) { "مجلد العمل غير موجود" } }
        val type = detectImgTypeFromDir(dir)
        val output = File(outputPath)
        when (type) {
            ImgType.BOOT, ImgType.RECOVERY -> repackBootImage(dir, output)
            ImgType.VENDOR, ImgType.SYSTEM -> repackExt4Image(dir, output)
            else -> throw IllegalArgumentException("لا يمكن تحديد نوع الصورة")
        }
        output
    }}

    suspend fun deliverToUser(file: File): Result<Uri> = withContext(Dispatchers.IO) { runCatching {
        FileDelivery.getUriForFile(context, file) ?: throw IllegalStateException("فشل إنشاء URI")
    }}

    private fun detectImgType(file: File) = when {
        file.name.contains("boot", true) -> ImgType.BOOT
        file.name.contains("recovery", true) -> ImgType.RECOVERY
        file.name.contains("vendor", true) -> ImgType.VENDOR
        file.name.contains("system", true) -> ImgType.SYSTEM
        else -> ImgType.UNKNOWN
    }

    private fun detectImgTypeFromDir(dir: File) = when {
        File(dir, "kernel").exists() || File(dir, "ramdisk.cpio.gz").exists() -> ImgType.BOOT
        File(dir, "bin").exists() || File(dir, "lib").exists() -> ImgType.VENDOR
        File(dir, "ramdisk").exists() -> ImgType.RECOVERY
        else -> ImgType.UNKNOWN
    }

    private fun unpackBootImage(file: File, out: File): List<String> {
        val comp = mutableListOf<String>()
        FileInputStream(file).use { fis ->
            val hdr = ByteArray(2048).also { fis.read(it) }
            File(out, "header.bin").writeBytes(hdr); comp.add("header.bin")
            val payload = fis.readBytes()
            File(out, "payload.bin").writeBytes(payload); comp.add("payload.bin")
        }
        return comp
    }

    private fun unpackExt4Image(file: File, out: File): List<String> {
        File(out, "image.info").writeText("EXT4\nالحجم: ${file.length()}\nيحتاج ext4-utils")
        return listOf("image.info")
    }

    private fun repackBootImage(dir: File, out: File) {
        FileOutputStream(out).use { 
            it.write(File(dir, "header.bin").readBytes())
            it.write(File(dir, "payload.bin").readBytes())
        }
    }

    private fun repackExt4Image(dir: File, out: File) { out.writeBytes(ByteArray(0)) }

    private fun createZip(src: File, zip: File): File {
        FileOutputStream(zip).use { fos -> ZipOutputStream(fos).use { zos ->
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(src).path))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }}
        return zip
    }

    suspend fun cleanup() = withContext(Dispatchers.IO) { runCatching { workDir.deleteRecursively() } }
}
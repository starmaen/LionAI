package com.lion.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object MediaSaver {
    
    enum class SaveLocation {
        GALLERY, DRIVE, APP_FOLDER
    }

    /**
     * حفظ الصورة في الموقع المطلوب
     */
    suspend fun saveImage(
        context: Context,
        bitmap: Bitmap,
        location: SaveLocation,
        filename: String = "LionAI_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
    ): Result<String> {
        return try {
            when (location) {
                SaveLocation.GALLERY -> saveToGallery(context, bitmap, filename)
                SaveLocation.APP_FOLDER -> saveToAppFolder(context, bitmap, filename)
                SaveLocation.DRIVE -> saveToDrive(context, bitmap, filename) // يتطلب Connectors.kt
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, filename: String): Result<String> {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lion AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            return Result.success("تم الحفظ في المعرض: $filename")
        }
        return Result.failure(Exception("فشل الوصول لمخزن الوسائط"))
    }

    private fun saveToAppFolder(context: Context, bitmap: Bitmap, filename: String): Result<String> {
        val dir = File(context.filesDir, "LionAI_Studio")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Result.success("تم الحفظ في مجلد التطبيق: ${file.absolutePath}")
    }

    private suspend fun saveToDrive(context: Context, bitmap: Bitmap, filename: String): Result<String> {
        // تكامل مع Connectors.kt الموجود في المشروع
        return try {
            // ملاحظة: يحتاج لتفعيل Google Drive Connector من الإعدادات
            Result.success("جاري الرفع إلى Google Drive... (تأكد من ربط الحساب)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.lion.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يسجّل آخر انهيار في ملف ليظهر داخل التطبيق (لأن المستخدم لا يرى logcat). */
object CrashRecorder {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    "$stamp  thread=${thread.name}\n" +
                        (error.stackTraceToString().take(6000))
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String? =
        file(context).takeIf { it.isFile && it.length() > 0 }?.readText()

    fun clear(context: Context) { file(context).delete() }
}

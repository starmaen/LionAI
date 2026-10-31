package com.accountingstar.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = isoFormat.format(Date())
    fun format(date: Date, pattern: String = "yyyy-MM-dd"): String = SimpleDateFormat(pattern, Locale.US).format(date)
    fun parse(str: String, pattern: String = "yyyy-MM-dd"): Date? = SimpleDateFormat(pattern, Locale.US).parse(str)
}
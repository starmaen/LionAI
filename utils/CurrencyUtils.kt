package com.accountingstar.utils

import java.math.BigDecimal
import java.math.RoundingMode

object CurrencyUtils {
    fun round(value: Double, scale: Int = 2): Double =
        BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).toDouble()
    fun format(value: Double, currencySymbol: String = "\u0623.س"): String =
        "$currencySymbol ${"%,.2f".format(value)}"
}
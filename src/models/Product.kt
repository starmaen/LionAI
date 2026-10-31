package com.accountingstar.models

data class Product(
    val id: Long = 0,
    val code: String,
    val name: String,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val unit: String? = null,
    val purchasePrice: Double,
    val salePrice: Double
)
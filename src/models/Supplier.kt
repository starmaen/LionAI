package com.accountingstar.models

data class Supplier(
    val id: Long = 0,
    val code: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val taxNumber: String? = null
)
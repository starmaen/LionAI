package com.accountingstar.models

data class Warehouse(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val location: String? = null
)
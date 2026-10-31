package com.accountingstar.models

data class Account(
    val id: Long = 0,
    val code: String,
    val name: String,
    val parentId: Long? = null,
    val type: String? = null
)
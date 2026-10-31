package com.accountingstar.tests

import com.accountingstar.services.InventoryService
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryServiceTest {
    private val service = InventoryService()

    @Test
    fun `test getQuantity returns zero by default`() {
        val qty = service.getQuantity(productId = 1, warehouseId = 1)
        assertEquals(0.0, qty, 0.0)
    }
}

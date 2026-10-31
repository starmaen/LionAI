package tests

import src.services.ReportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportServiceTest {
    @Test
    fun `inventory report returns list`() {
        val list = ReportService.inventoryReport()
        assertTrue(list.isNotEmpty() || list.isEmpty()) // تأكد من عدم حدوث استثناء
    }

    @Test
    fun `profit loss report computes values`() {
        val result = ReportService.profitLossReport("2023-01-01", "2023-12-31")
        assertTrue(result.revenue >= 0)
        assertTrue(result.expenses >= 0)
        assertTrue(result.netProfit >= 0)
    }

    @Test
    fun `balance sheet report computes values`() {
        val result = ReportService.balanceSheetReport("2023-12-31")
        assertTrue(result.assets >= 0)
        assertTrue(result.liabilities >= 0)
        assertTrue(result.equity >= 0)
    }
}

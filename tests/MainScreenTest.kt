package tests

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import frontend.MainScreen
import frontend.RoleManager

class MainScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreenShowsAdminCardWhenUserIsAdmin() {
        // إعداد مدير الأدوار لتعيد دور "ADMIN"
        RoleManager.currentRole = "ADMIN"
        composeTestRule.setContent { MainScreen() }
        // نتوقع وجود بطاقة تحمل نص "لوحة الإدارة"
        composeTestRule.onNodeWithText("لوحة الإدارة").assertIsDisplayed()
    }

    @Test
    fun mainScreenShowsUserCardWhenUserIsUser() {
        RoleManager.currentRole = "USER"
        composeTestRule.setContent { MainScreen() }
        composeTestRule.onNodeWithText("لوحة المستخدم").assertIsDisplayed()
    }
}

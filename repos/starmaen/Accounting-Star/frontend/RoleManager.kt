package frontend

class RoleManager {
    private val roles = mutableMapOf(
        "ADMIN" to listOf("view_reports", "manage_users", "edit_accounts"),
        "ACCOUNTANT" to listOf("view_reports", "edit_accounts"),
        "VIEWER" to listOf("view_reports")
    )

    fun hasPermission(role: String, permission: String): Boolean {
        return roles[role]?.contains(permission) ?: false
    }

    fun getPermissions(role: String): List<String> {
        return roles[role] ?: emptyList()
    }
}

fun main() {
    val roleManager = RoleManager()
    println(roleManager.hasPermission("ADMIN", "manage_users")) // true
    println(roleManager.hasPermission("ACCOUNTANT", "manage_users")) // false
}
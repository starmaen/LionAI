package frontend

import java.security.MessageDigest

class UserManager {
    private val users = mutableMapOf<String, User>()

    fun addUser(username: String, password: String, role: String) {
        val hashedPassword = hashPassword(password)
        users[username] = User(username, hashedPassword, role)
    }

    fun authenticate(username: String, password: String): Boolean {
        val user = users[username] ?: return false
        return user.password == hashPassword(password)
    }

    fun getUserRole(username: String): String? {
        return users[username]?.role
    }

    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}

data class User(val username: String, val password: String, val role: String)
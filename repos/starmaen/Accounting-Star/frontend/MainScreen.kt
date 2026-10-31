package frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(username: String) {
    val userManager = UserManager()
    val role = userManager.getUserRole(username) ?: "GUEST"
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounting Star") },
                actions = {
                    IconButton(onClick = { /* Logout action */ }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome, $username",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Text(
                text = "Role: $role",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            when (role) {
                "ADMIN" -> AdminDashboard()
                "MANAGER" -> ManagerDashboard()
                else -> UserDashboard()
            }
        }
    }
}

@Composable
private fun AdminDashboard() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Admin Dashboard", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Manage all system settings and users")
            }
        }
        // Add more admin-specific UI components
    }
}

@Composable
private fun ManagerDashboard() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Manager Dashboard", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("View and manage department reports")
            }
        }
        // Add more manager-specific UI components
    }
}

@Composable
private fun UserDashboard() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("User Dashboard", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("View your assigned tasks and reports")
            }
        }
        // Add more user-specific UI components
    }
}

@Preview
@Composable
fun PreviewMainScreen() {
    MainScreen("admin")
}

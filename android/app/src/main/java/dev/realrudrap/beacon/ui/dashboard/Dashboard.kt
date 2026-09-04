package dev.realrudrap.beacon.ui.dashboard


import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.realrudrap.beacon.ui.attendance.AttendanceScreen
import dev.realrudrap.beacon.ui.cash.CashScreen
import dev.realrudrap.beacon.ui.dashboard.home.HomeScreen
import dev.realrudrap.beacon.ui.profile.ProfileScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.realrudrap.beacon.ui.dashboard.home.HomeUiState
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalTime::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val innerNavController = rememberNavController()
    val currentEntry by innerNavController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    val HomeUiState = HomeUiState(
        workerName = "Test Worker"
    )
    Scaffold(
        bottomBar = {
            DashboardNavBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    innerNavController.navigate(route) {
                        popUpTo(innerNavController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { padding ->
        NavHost(
            navController = innerNavController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen() }
            composable("attendance") { AttendanceScreen() }
            composable("cash") { ComingSoonScreen(feature = "Petty Cash") }
            composable("profile") { ProfileScreen(onLogout = { viewModel.logout() }) }
        }
    }
}

@Composable
fun DashboardNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == "home",
            onClick = { onNavigate("home") },
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") }
        )
        NavigationBarItem(
            selected = currentRoute == "attendance",
            onClick = { onNavigate("attendance") },
            icon = { Icon(Icons.Default.DateRange, contentDescription = "Attendance") },
            label = { Text("Attendance") }
        )
        NavigationBarItem(
            selected = currentRoute == "cash",
            onClick = { onNavigate("cash") },
            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Cash") },
            label = { Text("Cash") }
        )
        NavigationBarItem(
            selected = currentRoute == "profile",
            onClick = { onNavigate("profile") },
            icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
            label = { Text("Profile") }
        )
    }
}

@Composable
fun ComingSoonScreen(feature: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = feature,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "This feature is coming soon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
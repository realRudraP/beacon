package dev.realrudrap.beacon

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.realrudrap.beacon.ui.MainViewModel
import dev.realrudrap.beacon.ui.auth.LoginScreen
import dev.realrudrap.beacon.ui.dashboard.DashboardScreen
import dev.realrudrap.beacon.ui.permissions.PermissionDashboardScreen
import dev.realrudrap.beacon.ui.theme.BeaconTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val startDestination by viewModel.startDestination.collectAsState()
            val navController = rememberNavController()

            // Local state tracking whether the entry gate is cleared
            var allPermissionsGranted by remember { mutableStateOf(false) }


            fun checkCurrentPermissions(): Boolean {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

                val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

                val hasBgLocation =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

                val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true

                val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else true

                return hasCamera && hasFineLocation && hasBgLocation && hasNotifications && hasExactAlarm
            }


            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                allPermissionsGranted = checkCurrentPermissions()
            }

            BeaconTheme {
                when {

                    startDestination == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }


                    !allPermissionsGranted -> {
                        PermissionDashboardScreen(
                            onAllPermissionsGranted = {
                                allPermissionsGranted = true
                            }
                        )
                    }


                    else -> {
                        NavHost(
                            navController = navController,
                            startDestination = startDestination!!
                        ) {
                            composable("auth") {
                                LoginScreen(
                                    onLoginSuccess = {
                                        navController.navigate("dashboard") {
                                            popUpTo("auth") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("dashboard") {
                                DashboardScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
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
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.domain.repository.UserRepository
import dev.realrudrap.beacon.ui.MainViewModel
import dev.realrudrap.beacon.ui.auth.LoginScreen
import dev.realrudrap.beacon.ui.dashboard.DashboardScreen
import dev.realrudrap.beacon.ui.permissions.PermissionDashboardScreen
import dev.realrudrap.beacon.ui.theme.BeaconTheme
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Timber.d("MainActivity: onCreate")
        registerFcmToken()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val isLoggedIn by viewModel.isLoggedIn.collectAsState()

            var allPermissionsGranted by remember { mutableStateOf(false) }

            fun checkCurrentPermissions(): Boolean {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

                val hasCamera = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasBgLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else true

                val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else true


                val granted = hasCamera && hasFineLocation && hasBgLocation && hasNotifications && hasExactAlarm
                if (!granted) {
                    Timber.d(
                        "MainActivity: missing permissions camera=%s fineLocation=%s bgLocation=%s notifications=%s exactAlarm=%s",
                        hasCamera, hasFineLocation, hasBgLocation, hasNotifications, hasExactAlarm
                    )
                }
                return granted
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                allPermissionsGranted = checkCurrentPermissions()
            }

            BeaconTheme {
                when {
                    // 1. Still checking initial session
                    isLoggedIn == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    // 2. Permission gate
                    !allPermissionsGranted -> {
                        PermissionDashboardScreen(
                            onAllPermissionsGranted = {
                                allPermissionsGranted = true
                            }
                        )
                    }

                    // 3. User is not logged in -> Show Login
                    isLoggedIn == false -> {
                        LoginScreen(
                            onLoginSuccess = {
                                Timber.i("MainActivity: login succeeded")
                                registerFcmToken()
                                viewModel.checkSession()
                            }
                        )
                    }

                    // 4. User is logged in -> Show Dashboard
                    else -> {
                        DashboardScreen()
                    }
                }
            }
        }
    }

    private fun registerFcmToken() {
        if (tokenManager.getRefreshToken().isNullOrBlank()) {
            Timber.d("MainActivity: skipping FCM token registration until sign-in")
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val result = userRepository.updateFcmToken(token)
                        if (result.isSuccess) {
                            Timber.d("MainActivity: FCM token registered")
                        } else {
                            Timber.w(result.exceptionOrNull(), "MainActivity: failed to register FCM token")
                        }
                    }
                }
            }.addOnFailureListener { e ->
                Timber.w(e, "Failed to fetch FCM token")
            }
        } catch (e: Exception) {
            Timber.e(e, "Firebase not initialized or missing google-services.json")
        }
    }
}
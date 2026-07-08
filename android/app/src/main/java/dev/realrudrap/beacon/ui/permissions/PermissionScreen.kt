package dev.realrudrap.beacon.ui.permissions

/**
 * Note: CameraAlt, MyLocation, Alarm, and TipsAndUpdates live in the extended
 * Material icon set. If they aren't already available, add:
 *   implementation("androidx.compose.material:material-icons-extended:<version>")
 */

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.text.toFloat

@Composable
fun PermissionDashboardScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }

    // Dynamic states for each permission tracking category
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasForegroundLocation by remember { mutableStateOf(false) }
    var hasBackgroundLocation by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasExactAlarmPermission by remember { mutableStateOf(false) }

    // Helper function to evaluate current permission states
    fun checkAllPermissions() {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasForegroundLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        hasBackgroundLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    // Trigger check on initialization and every time the user resumes the app
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checkAllPermissions()
    }

    // --- Standard Launchers ---
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasCameraPermission = isGranted
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasNotificationPermission = isGranted
    }

    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasForegroundLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasBackgroundLocation = isGranted
    }

    // --- Generate the Structured List Matrix ---
    val permissionsList = remember(hasCameraPermission, hasForegroundLocation, hasBackgroundLocation, hasNotificationPermission, hasExactAlarmPermission) {
        listOf(
            PermissionItem(
                title = "Camera Access",
                description = "Snap a quick verification photo when you punch in and out at the farm site.",
                isGranted = hasCameraPermission,
                onActionClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }
            ),
            PermissionItem(
                title = "Precise Location (Foreground)",
                description = "Confirms you're actually on the farm grounds when logging attendance.",
                isGranted = hasForegroundLocation,
                onActionClick = {
                    foregroundLocationLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            ),
            PermissionItem(
                title = "Always-On Location (Background)",
                description = "Keeps an occasional eye on your location across the property, even when the app is in the background. (Set Location Access to 'Allow all the time'.)",
                isGranted = hasBackgroundLocation,
                onActionClick = {
                    if (hasForegroundLocation) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        // Ensure foreground is handled first to comply with Android OS guidelines
                        foregroundLocationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }
            ),
            PermissionItem(
                title = "Push Notifications",
                description = "Lets you know the moment a sync finishes or something needs your attention.",
                isGranted = hasNotificationPermission,
                onActionClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            ),
            PermissionItem(
                title = "Precision Alarms & Timing",
                description = "Powers the scheduled background syncs and occasional check-ins, even on battery-conscious devices.",
                isGranted = hasExactAlarmPermission,
                onActionClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                }
            )
        )
    }

    val grantedCount = permissionsList.count { it.isGranted }
    val totalCount = permissionsList.size
    val allGranted = grantedCount == totalCount

    // --- UI Structure ---
    Scaffold(
        bottomBar = {
            // navigationBarsPadding() keeps this clear of the 3-button / gesture nav bar
            // when the screen is drawn edge-to-edge.
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (!allGranted) {
                        Text(
                            text = "$grantedCount of $totalCount ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LinearProgressIndicator(
                            progress = { grantedCount / totalCount.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    Button(
                        onClick = onAllPermissionsGranted,
                        enabled = allGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (allGranted) "All Set — Continue" else "Grant Remaining Permissions")
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Quick Setup Before We Begin",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "A few permissions help Beacon track attendance accurately, even out on remote sites with patchy connectivity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

            items(permissionsList) { item ->
                PermissionRow(item=item)
            }


        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isGranted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        ListItem(
            headlineContent = {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(
                    imageVector = iconFor(item.title),
                    contentDescription = null,
                    tint = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Crossfade(targetState = item.isGranted, label = "permissionStatus") { granted ->
                    if (granted) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Granted") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                disabledLeadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = null
                        )
                    } else {
                        FilledTonalButton(onClick = item.onActionClick) {
                            Text("Grant")
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

private fun iconFor(title: String): ImageVector = when (title) {
    "Camera Access" -> Icons.Filled.CameraAlt
    "Precise Location (Foreground)" -> Icons.Filled.LocationOn
    "Always-On Location (Background)" -> Icons.Filled.MyLocation
    "Push Notifications" -> Icons.Filled.Notifications
    "Precision Alarms & Timing" -> Icons.Filled.Alarm
    else -> Icons.Filled.CheckCircle
}
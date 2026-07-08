package dev.realrudrap.beacon.ui.dashboard.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.realrudrap.beacon.ui.dashboard.home.camera.AttendanceCameraScreen
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.homeUiState.collectAsStateWithLifecycle()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cameraEvent by remember { mutableStateOf<HomeEvent?>(null) }


    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.ShowError -> errorMessage = event.message
                is HomeEvent.OpenCameraForClockIn -> cameraEvent = event
                is HomeEvent.OpenCameraForClockOut -> cameraEvent = event
            }
        }
    }

    AnimatedContent(targetState = uiState.isFirstSyncPending) { isPending ->
        if (isPending) {
            FirstSyncScreen()
        } else {
            HomeContent(
                uiState = uiState,
                onRefresh = viewModel::onRefresh,
                onClockIn = viewModel::onClockIn,
                onClockOut = viewModel::onClockOut
            )
        }
    }
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            },
            title = {
                Text("Something went wrong")
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }

    cameraEvent?.let { event ->
        val fileName = when (event) {
            is HomeEvent.OpenCameraForClockIn -> "${UUID.randomUUID()}_in.jpg"
            is HomeEvent.OpenCameraForClockOut -> "${event.recordId}_out.jpg"
            else -> return@let
        }
        AttendanceCameraScreen(
            fileName = fileName,
            onPhotoCaptured = { path ->
                cameraEvent = null
                when (event) {
                    is HomeEvent.OpenCameraForClockIn ->
                        viewModel.onClockInPhotoTaken(event.lat, event.long, path)
                    is HomeEvent.OpenCameraForClockOut ->
                        viewModel.onClockOutPhotoTaken(event.recordId, event.lat, event.long, path)
                    else -> {}
                }
            },
            onDismiss = {
                cameraEvent = null
                viewModel.onCameraDismissed()
            }
        )
    }
}

@Composable
private fun FirstSyncScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Setting up Beacon…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {



        // Greeting
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = greeting(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = uiState.workerName.ifBlank { "Worker" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Petty cash card
        uiState.pettyCashBalance?.let { balance ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Petty Cash Balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "₹%.2f".format(balance),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // Sync status card
        SyncStatusCard(
            uiState = uiState,
            onRefresh = onRefresh
        )

        Spacer(modifier = Modifier.weight(1f))

        // Main CTA
        ClockInOutButton(
            clockInState = uiState.clockInState,
            onClockIn = onClockIn,
            onClockOut = onClockOut,
            isClockInLoading = uiState.isClockInLoading
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun SyncStatusCard(
    uiState: HomeUiState,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Last synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Staleness indicator dot
                    if (uiState.syncStatus == SyncStatus.Stale) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Stale data",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = when (uiState.syncStatus) {
                            SyncStatus.Never -> "Never synced"
                            SyncStatus.Fresh -> formatLastSynced(uiState.lastSyncedAt)
                            SyncStatus.Stale -> formatLastSynced(uiState.lastSyncedAt)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (uiState.syncStatus) {
                            SyncStatus.Stale -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Sync button
            IconButton(
                onClick = onRefresh,
                enabled = !uiState.isSyncing
            ) {
                if (uiState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync now",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockInOutButton(
    clockInState: ClockInState,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    isClockInLoading: Boolean,
) {
    when (clockInState) {
        is ClockInState.GeofenceNotConfigured -> {
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Attendance not configured",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        is ClockInState.NotClockedIn -> {
            Button(
                onClick = onClockIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (isClockInLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.error,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Clock In", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        is ClockInState.ClockedIn -> {
            OutlinedButton(
                onClick = onClockOut,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isClockInLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                if (isClockInLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.error,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Clock Out", style = MaterialTheme.typography.titleMedium)
                }
            }
        }


        is ClockInState.DoneForTheDay -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Done for the day",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun formatLastSynced(instant: kotlin.time.Instant?): String {
    if (instant == null) return "Never"
    val minutes = (Clock.System.now() - instant).inWholeMinutes
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60}h ago"
    }
}

private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning,"
        hour < 17 -> "Good afternoon,"
        else -> "Good evening,"
    }
}
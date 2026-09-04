package dev.realrudrap.beacon.ui.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Colors
private val ColorPresentSynced = Color(0xFF2E7D32)   // Darker green
private val ColorPresentPending = Color(0xFFF57C00)  // Orange / Amber for unsynced
private val ColorAbsent = Color(0xFFD32F2F)          // Red
private val ColorWeeklyOff = Color(0xFFC2185B)       // Rose / Distinct off-day color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attendance History") },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isRefreshing
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Month Switcher Header
            MonthHeader(
                month = state.currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                onPrev = { viewModel.onPreviousMonth() },
                onNext = { viewModel.onNextMonth() }
            )

            // Day of week labels (Sun - Sat)
            DaysOfWeekHeader()

            // Calendar Month Grid
            CalendarGrid(
                days = state.days,
                firstDayOfWeekOffset = (state.currentMonth.atDay(1).dayOfWeek.value % 7),
                onDateSelected = { viewModel.onDateSelected(it) }
            )

            // Legend
            CalendarLegend()

            HorizontalDivider()

            // Details card for selected date
            SelectedDayDetailCard(
                date = state.selectedDate,
                status = state.selectedDateStatus,
                record = state.selectedRecord
            )
        }
    }
}

@Composable
fun MonthHeader(
    month: String,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Month")
        }
        Text(
            text = month,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Month")
        }
    }
}

@Composable
fun DaysOfWeekHeader() {
    val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Text(
                text = day,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun CalendarGrid(
    days: List<CalendarDay>,
    firstDayOfWeekOffset: Int,
    onDateSelected: (LocalDate) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(firstDayOfWeekOffset) {
            Spacer(modifier = Modifier.size(36.dp))
        }

        items(days, key = { it.date.toString() }) { day ->
            DayCell(day = day, onClick = { onDateSelected(day.date) })
        }
    }
}

@Composable
fun DayCell(
    day: CalendarDay,
    onClick: () -> Unit
) {
    val (bgColor, textColor) = when (val s = day.status) {
        is DayStatus.Present -> {
            if (s.record.syncStatus == AttendanceSyncStatus.SYNCED.name) {
                ColorPresentSynced to Color.White
            } else {
                ColorPresentPending to Color.White
            }
        }
        is DayStatus.Absent -> ColorAbsent to Color.White
        is DayStatus.WeeklyOff -> Color.Transparent to ColorWeeklyOff
        is DayStatus.Today -> Color.Transparent to MaterialTheme.colorScheme.primary
        is DayStatus.Future -> Color.Transparent to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(
                when {
                    day.isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    day.status is DayStatus.Today && day.status.record == null -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    day.status is DayStatus.WeeklyOff -> Modifier.border(1.dp, ColorWeeklyOff.copy(alpha = 0.5f), CircleShape)
                    else -> Modifier
                }
            )
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (day.isSelected || day.status is DayStatus.WeeklyOff || day.status is DayStatus.Present) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CalendarLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        LegendDot(color = ColorPresentSynced, label = "Present")
        LegendDot(color = ColorPresentPending, label = "Pending Sync")
        LegendDot(color = ColorAbsent, label = "Absent")
        LegendDot(color = ColorWeeklyOff, label = "Weekly Off", isHollow = true)
    }
}

@Composable
fun LegendDot(
    color: Color,
    label: String,
    isHollow: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .then(
                    if (isHollow) Modifier.border(1.5.dp, color, CircleShape)
                    else Modifier.background(color)
                )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SelectedDayDetailCard(
    date: LocalDate,
    status: DayStatus?,
    record: AttendanceEntity?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (record != null) {
                    SyncBadge(status = record.syncStatus)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                record != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Clock In", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatTime(record.clockInTime), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Clock Out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatTime(record.clockOutTime), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                status is DayStatus.WeeklyOff -> {
                    Text("Weekly Off Day", color = ColorWeeklyOff, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                status is DayStatus.Absent -> {
                    Text("Absent — No clock-in recorded", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                status is DayStatus.Today -> {
                    Text("Today — You haven't clocked in yet.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text("Upcoming work day", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun SyncBadge(status: String) {
    val (icon, tint, label) = when (status) {
        AttendanceSyncStatus.SYNCED.name -> Triple(Icons.Default.CloudDone, ColorPresentSynced, "Synced")
        AttendanceSyncStatus.PENDING.name -> Triple(Icons.Default.CloudQueue, ColorPresentPending, "Offline (Pending)")
        else -> Triple(Icons.Default.CloudOff, MaterialTheme.colorScheme.error, "Sync Failed")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
    }
}

// In AttendanceScreen.kt
private fun formatTime(isoTimestamp: String?): String {
    if (isoTimestamp.isNullOrEmpty() || isoTimestamp.startsWith("0001-01-01")) return "—"
    return try {
        val instant = java.time.Instant.parse(isoTimestamp)
        val localDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        localDateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } catch (_: Exception) {
        isoTimestamp
    }
}
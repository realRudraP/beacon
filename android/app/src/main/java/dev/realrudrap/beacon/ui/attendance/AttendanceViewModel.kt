package dev.realrudrap.beacon.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.UserDao
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.domain.model.User
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface DayStatus {
    data class Present(val record: AttendanceEntity) : DayStatus
    data class Today(val record: AttendanceEntity?) : DayStatus
    object Absent : DayStatus
    object WeeklyOff : DayStatus
    object Future : DayStatus
}

data class CalendarDay(
    val date: LocalDate,
    val status: DayStatus,
    val isSelected: Boolean
)

private data class CalendarData(
    val month: YearMonth,
    val days: List<CalendarDay>,
    val selectedDate: LocalDate,
    val selectedRecord: AttendanceEntity?,
    val selectedStatus: DayStatus?
)

data class AttendanceUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val days: List<CalendarDay> = emptyList(),
    val selectedRecord: AttendanceEntity? = null,
    val selectedDateStatus: DayStatus? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val repository: AttendanceRepository,
    private val userDao: UserDao
) : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    // 1. Combine the 4 core data flows (type-safe)
    private val _calendarData: Flow<CalendarData> = combine(
        repository.attendanceHistory,
        userDao.getUser(),
        _currentMonth,
        _selectedDate
    ) { records, user, month, selectedDate ->
        val recordsByDate = records.associateBy { record ->
            parseLocalDate(record)
        }

        val days = generateMonthDays(month, recordsByDate, user, selectedDate)
        val selectedRecord = recordsByDate[selectedDate]
        val selectedStatus = days.find { it.date == selectedDate }?.status

        CalendarData(
            month = month,
            days = days,
            selectedDate = selectedDate,
            selectedRecord = selectedRecord,
            selectedStatus = selectedStatus
        )
    }

    // 2. Combine calendar data with UI status flags (3 flows, type-safe)
    val uiState: StateFlow<AttendanceUiState> = combine(
        _calendarData,
        _isRefreshing,
        _errorMessage
    ) { calendar, isRefreshing, errorMessage ->
        AttendanceUiState(
            currentMonth = calendar.month,
            days = calendar.days,
            selectedDate = calendar.selectedDate,
            selectedRecord = calendar.selectedRecord,
            selectedDateStatus = calendar.selectedStatus,
            isRefreshing = isRefreshing,
            errorMessage = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AttendanceUiState()
    )

    init {
        refresh()
    }

    fun onPreviousMonth() {
        val newMonth = _currentMonth.value.minusMonths(1)
        _currentMonth.value = newMonth
        fetchMonth(newMonth)
    }

    fun onNextMonth() {
        val newMonth = _currentMonth.value.plusMonths(1)
        _currentMonth.value = newMonth
        fetchMonth(newMonth)
    }

    fun refresh() {
        fetchMonth(_currentMonth.value)
    }

    fun onDateSelected(date: LocalDate) {
        _selectedDate.value = date
    }

    private fun fetchMonth(month: YearMonth) {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            val result = repository.refreshAttendanceHistory(month)
            if (result.isFailure) {
                _errorMessage.value = "Failed to update ${month.format(DateTimeFormatter.ofPattern("MMMM"))}. Showing offline data."
            }
            _isRefreshing.value = false
        }
    }

    private fun generateMonthDays(
        month: YearMonth,
        records: Map<LocalDate, AttendanceEntity>,
        user: User?,
        selectedDate: LocalDate
    ): List<CalendarDay> {
        val today = LocalDate.now()
        val weeklyOffDays = user?.weeklyOffDays ?: emptyList()

        val daysInMonth = month.lengthOfMonth()
        val dayList = mutableListOf<CalendarDay>()

        for (day in 1..daysInMonth) {
            val date = month.atDay(day)
            val record = records[date]

            // Convert Java DayOfWeek to 0=Sunday..6=Saturday
            val dayOfWeekIndex = if (date.dayOfWeek == DayOfWeek.SUNDAY) 0 else date.dayOfWeek.value
            val isWeeklyOff = weeklyOffDays.contains(dayOfWeekIndex)

            val status = when {
                record != null -> DayStatus.Present(record)
                isWeeklyOff -> DayStatus.WeeklyOff
                date == today -> DayStatus.Today(record)
                date.isAfter(today) -> DayStatus.Future
                else -> DayStatus.Absent
            }

            dayList.add(
                CalendarDay(
                    date = date,
                    status = status,
                    isSelected = date == selectedDate
                )
            )
        }

        return dayList
    }

    // In AttendanceViewModel.kt
    private fun parseLocalDate(record: AttendanceEntity): LocalDate {
        // If we have a local clock-in timestamp, convert UTC Instant -> Device Local Date
        return try {
            val instant = java.time.Instant.parse(record.clockInTime)
            instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        } catch (_: Exception) {
            // Fallback to serverConfirmedDate if timestamp parsing fails
            record.serverConfirmedDate?.let {
                try { LocalDate.parse(it) } catch (_: Exception) { null }
            } ?: LocalDate.now()
        }
    }
}
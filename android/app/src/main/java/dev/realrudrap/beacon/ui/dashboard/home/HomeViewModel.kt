package dev.realrudrap.beacon.ui.dashboard.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.location.LocationException
import dev.realrudrap.beacon.data.location.LocationProvider
import dev.realrudrap.beacon.domain.model.Config
import dev.realrudrap.beacon.domain.model.Geofence
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import dev.realrudrap.beacon.domain.repository.ConfigRepository
import dev.realrudrap.beacon.domain.repository.UserRepository
import dev.realrudrap.beacon.work.AttendanceSyncWorker
import dev.realrudrap.beacon.work.LocationCheckScheduler
import dev.realrudrap.beacon.work.LocationCheckSyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

data class HomeUiState @OptIn(ExperimentalTime::class) constructor(
    val workerName: String = "",
    val lastSyncedAt: Instant? = null,
    val syncStatus: SyncStatus = SyncStatus.Never,
    val isSyncing: Boolean = false,
    val isFirstSyncPending: Boolean = true,
    val pettyCashBalance: Double? = null,
    val clockInState: ClockInState = ClockInState.NotClockedIn,
    val isClockInLoading: Boolean = false,
    val isOffline: Boolean = false,
)

enum class SyncStatus { Fresh, Stale, Never }

sealed interface ClockInState {
    data object NotClockedIn : ClockInState
    data object ClockedIn : ClockInState
    data object DoneForTheDay : ClockInState
    data object GeofenceNotConfigured : ClockInState
}

sealed interface HomeEvent {
    /** Sent after location is validated — UI should open the camera. */
    data class OpenCameraForClockIn(val lat: Double, val long: Double) : HomeEvent
    /** Sent after clock-out location is obtained — UI should open the camera. */
    data class OpenCameraForClockOut(val recordId: String, val lat: Double, val long: Double) : HomeEvent
    /** Worker-readable error to display as a dialog or snackbar. */
    data class ShowError(val message: String) : HomeEvent
}

@OptIn(ExperimentalTime::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val attendanceRepository: AttendanceRepository,
    private val configRepository: ConfigRepository,
    private val locationProvider: LocationProvider,
    private val logger: AppLogger,
    private val workManager: WorkManager,
    private val locationCheckScheduler: LocationCheckScheduler,
) : ViewModel() {

    private val _homeUiState = MutableStateFlow(HomeUiState())
    val homeUiState: StateFlow<HomeUiState> = _homeUiState.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()



    /** Latest config snapshot — kept in memory so onClockIn can read geofences synchronously. */
    private var latestConfig: Config? = null

    init {
        // 1. Observe User
        viewModelScope.launch {
            userRepository.user.collect { user ->
                _homeUiState.update { it.copy(workerName = user?.fullName ?: "User") }
            }
        }

        // 2. Observe Config
        viewModelScope.launch {
            logger.d("HomeViewModel", "Waiting for config...")
            configRepository.config.filterNotNull().collect { config ->
                logger.d("HomeViewModel", "Config received: $config")
                latestConfig = config
                _homeUiState.update {
                    it.copy(
                        isFirstSyncPending = false,
                        clockInState = resolveClockInState(
                            geofencesEmpty = config.geofences.isNullOrEmpty(),
                            currentState = it.clockInState
                        )
                    )
                }
            }
        }

        // 3. Observe Last Synced Time
        viewModelScope.launch {
            configRepository.lastSyncedAt.collect { lastSyncedAt ->
                _homeUiState.update {
                    it.copy(
                        lastSyncedAt = lastSyncedAt,
                        syncStatus = computeSyncStatus(lastSyncedAt)
                    )
                }
            }
        }

        // 4. Observe Today's Attendance Record reactively
        viewModelScope.launch {
            attendanceRepository.todayRecord.collect { record ->
                _homeUiState.update {
                    val newState = when {
                        record?.clockOutTime != null -> ClockInState.DoneForTheDay
                        record != null -> ClockInState.ClockedIn
                        else -> resolveClockInState(
                            geofencesEmpty = latestConfig?.geofences.isNullOrEmpty(),
                            currentState = it.clockInState
                        )
                    }
                    it.copy(clockInState = newState)
                }
            }
        }

        // 5. TRIGGER INITIAL FETCH (Fixes the first login hang)
        viewModelScope.launch {
            _homeUiState.update { it.copy(isSyncing = true) }
            try {
                configRepository.refreshConfig()
                userRepository.refreshUser()
                AttendanceSyncWorker.syncNow(workManager)
            } catch (e: Exception) {
                logger.e("HomeViewModel", "Initial sync failed", e)
            } finally {
                _homeUiState.update {
                    it.copy(
                        isSyncing = false,
                        isFirstSyncPending = false
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Clock-in flow
    // ---------------------------------------------------------------------------

    fun onClockIn() {
        if (_homeUiState.value.isClockInLoading) return
        viewModelScope.launch {
            _homeUiState.update { it.copy(isClockInLoading = true) }

            val locationResult = locationProvider.getFreshLocation()
            logger.d("HomeViewModel", "Location result: $locationResult")
            if (locationResult.isFailure) {
                logger.d("HomeViewModel", "Location failed: ${locationResult.exceptionOrNull()}")
                val message = (locationResult.exceptionOrNull() as? LocationException)
                    ?.message
                    ?: "Failed to get location. Please try again."
                _events.send(HomeEvent.ShowError(message))
                _homeUiState.update { it.copy(isClockInLoading = false) }
                return@launch
            }

            val location = locationResult.getOrThrow()
            val geofences = latestConfig?.geofences
            logger.d("HomeViewModel", "Geofences: $geofences")
            logger.d("HomeViewModel", "Worker location: ${location.latitude}, ${location.longitude}")

            if (geofences.isNullOrEmpty()) {
                logger.d("HomeViewModel", "Geofences empty")
                _events.send(HomeEvent.ShowError("Attendance is not configured yet. Please contact your supervisor."))
                _homeUiState.update { it.copy(isClockInLoading = false) }
                return@launch
            }
            val withinGeofence = isWithinAnyGeofence(location.latitude, location.longitude, geofences)
            logger.d("HomeViewModel", "Within geofence: $withinGeofence")

            if (!isWithinAnyGeofence(location.latitude, location.longitude, geofences)) {
                _events.send(
                    HomeEvent.ShowError(
                        "You are not within an approved work area.\n\n" +
                                "Current location: ${location.latitude}, ${location.longitude}\n" +
                                "Please move to your designated work area and try again.\n\n" +
                                "If you believe this is an error, take a screenshot and contact your supervisor."
                    )
                )
                _homeUiState.update { it.copy(isClockInLoading = false) }
                return@launch
            }

            AttendanceSyncWorker.syncNow(workManager)
            // Location validated — hand off to UI to open camera.
            // isClockInLoading stays true until onClockInPhotoTaken() is called.
            _events.send(HomeEvent.OpenCameraForClockIn(location.latitude, location.longitude))
        }
    }

    fun onCameraDismissed() {
        _homeUiState.update { it.copy(isClockInLoading = false) }
    }

    /**
     * Called by the UI after the worker takes the clock-in photo.
     * [photoLocalPath] is the path where the captured image was saved,
     * or null if the worker skipped the photo.
     */
    fun onClockInPhotoTaken(lat: Double, long: Double, photoLocalPath: String?) {
        viewModelScope.launch {
            val result = attendanceRepository.clockIn(lat, long, photoLocalPath)
            if (result.isSuccess) {
                _homeUiState.update { it.copy(clockInState = ClockInState.ClockedIn) }
                attendanceRepository.getOpenRecord()?.let { record ->
                    locationCheckScheduler.schedule(record)
                }
            } else {
                _events.send(
                    HomeEvent.ShowError(
                        "Failed to save your clock-in record. Please try again.\n\n" +
                                "If this keeps happening, take a screenshot and contact your supervisor."
                    )
                )
            }
            _homeUiState.update { it.copy(isClockInLoading = false) }
        }
    }

    // ---------------------------------------------------------------------------
    // Clock-out flow
    // ---------------------------------------------------------------------------

    fun onClockOut() {
        if (_homeUiState.value.isClockInLoading) return
        viewModelScope.launch {
            _homeUiState.update { it.copy(isClockInLoading = true) }

            val locationResult = locationProvider.getFreshLocation()
            if (locationResult.isFailure) {
                val message = (locationResult.exceptionOrNull() as? LocationException)
                    ?.message
                    ?: "Failed to get location. Please try again."
                _events.send(HomeEvent.ShowError(message))
                _homeUiState.update { it.copy(isClockInLoading = false) }
                return@launch
            }

            val location = locationResult.getOrThrow()

            // Get today's record ID for the update call
            val todayRecord = attendanceRepository.todayRecord
            val recordId = attendanceRepository.todayRecord.first()?.id
            if (recordId == null) {
                _events.send(HomeEvent.ShowError("Could not find today's attendance record. Please contact your supervisor."))
                _homeUiState.update { it.copy(isClockInLoading = false) }
                return@launch
            }

            AttendanceSyncWorker.syncNow(workManager)
            // Location obtained — no geofence check for clock-out.
            // Hand off to UI to open camera.
            _events.send(HomeEvent.OpenCameraForClockOut(recordId as String, location.latitude, location.longitude))
        }
    }

    /**
     * Called by the UI after the worker takes the clock-out photo.
     */
    fun onClockOutPhotoTaken(recordId: String, lat: Double, long: Double, photoLocalPath: String?) {
        viewModelScope.launch {
            val result = attendanceRepository.clockOut(recordId, lat, long, photoLocalPath)
            if (result.isSuccess) {
                _homeUiState.update { it.copy(clockInState = ClockInState.DoneForTheDay) }
                locationCheckScheduler.cancelAll()
                LocationCheckSyncWorker.syncNow(workManager)
            } else {
                _events.send(
                    HomeEvent.ShowError(
                        "Failed to save your clock-out record. Please try again.\n\n" +
                                "If this keeps happening, take a screenshot and contact your supervisor."
                    )
                )
            }
            _homeUiState.update { it.copy(isClockInLoading = false) }
        }
    }

    // ---------------------------------------------------------------------------
    // Manual refresh
    // ---------------------------------------------------------------------------

    fun onRefresh() {
        viewModelScope.launch {
            _homeUiState.update { it.copy(isSyncing = true) }
            configRepository.refreshConfig()
            AttendanceSyncWorker.syncNow(workManager)
            _homeUiState.update { it.copy(isSyncing = false) }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun resolveClockInState(
        geofencesEmpty: Boolean,
        currentState: ClockInState
    ): ClockInState {
        if (currentState == ClockInState.ClockedIn || currentState == ClockInState.DoneForTheDay) {
            return currentState
        }
        return if (geofencesEmpty) ClockInState.GeofenceNotConfigured else ClockInState.NotClockedIn
    }

    @OptIn(ExperimentalTime::class)
    private fun computeSyncStatus(lastSyncedAt: Instant?): SyncStatus {
        if (lastSyncedAt == null) return SyncStatus.Never
        val age = Clock.System.now() - lastSyncedAt
        return if (age.inWholeHours < 12) SyncStatus.Fresh else SyncStatus.Stale
    }

    /**
     * Haversine formula — checks if [lat]/[long] falls within any geofence circle.
     * [Geofence.radius] is in meters.
     */
    private fun isWithinAnyGeofence(
        lat: Double,
        long: Double,
        geofences: List<Geofence>
    ): Boolean {
        return geofences.any { geofence ->
            haversineDistance(lat, long, geofence.centerLat, geofence.centerLng) <= geofence.radius
        }
    }

    /**
     * Returns the distance in meters between two coordinates using the Haversine formula.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
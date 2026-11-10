package dev.realrudrap.beacon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.AuthEvent
import dev.realrudrap.beacon.data.remote.AuthEventBus
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import dev.realrudrap.beacon.domain.repository.ConfigRepository
import dev.realrudrap.beacon.work.AttendanceSyncWorker
import dev.realrudrap.beacon.work.LocationCheckScheduler
import dev.realrudrap.beacon.work.LocationCheckSyncWorker
import dev.realrudrap.beacon.work.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventBus: AuthEventBus,
    private val logger: AppLogger,
    private val crashReporter: CrashReportingProvider,
    private val configRepository: ConfigRepository,
    private val notificationScheduler: NotificationScheduler,
    private val locationCheckScheduler: LocationCheckScheduler,
    private val workManager: WorkManager
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn.asStateFlow()

    init {
        checkSession()

        viewModelScope.launch {
            refreshConfigAndSchedule()
        }

        viewModelScope.launch {
            authEventBus.events.collect { event ->
                when (event) {
                    is AuthEvent.Unauthorized,
                    is AuthEvent.Logout -> {
                        logger.w(TAG, "Auth event received: ${event.javaClass.simpleName}")
                        handleLogout()
                    }
                }
            }
        }

        AttendanceSyncWorker.schedulePeriodicSync(workManager)
        LocationCheckSyncWorker.schedulePeriodicSync(workManager)
    }

    fun checkSession() {
        val hasToken = tokenManager.getRefreshToken() != null
        logger.d(TAG, "Session check: hasRefreshToken=$hasToken")
        _isLoggedIn.value = hasToken
    }

    private fun handleLogout() {
        logger.i(TAG, "Logging out and clearing scheduled background work")
        tokenManager.clearTokens()
        crashReporter.setUserId(null)
        locationCheckScheduler.cancelAll()
        _isLoggedIn.value = false
    }

    private suspend fun refreshConfigAndSchedule() {
        try {
            val result = configRepository.refreshConfig()
            if (result.isFailure) {
                logger.w(TAG, "Config refresh failed; using cached config")
            }
            configRepository.config
                .filterNotNull()
                .first()
                .let { config ->
                    val rules = config.notificationRules ?: emptyList()
                    logger.d(TAG, "Scheduling ${rules.size} notification rule(s) from config")
                    notificationScheduler.schedule(rules)
                }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to refresh config or schedule notifications", e)
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
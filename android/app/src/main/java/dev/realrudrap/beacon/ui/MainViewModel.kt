package dev.realrudrap.beacon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.AuthEvent
import dev.realrudrap.beacon.data.remote.AuthEventBus
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
        _isLoggedIn.value = hasToken
    }

    private fun handleLogout() {
        tokenManager.clearTokens()
        locationCheckScheduler.cancelAll()
        _isLoggedIn.value = false
    }

    private suspend fun refreshConfigAndSchedule() {
        try {
            configRepository.refreshConfig()
            configRepository.config
                .filterNotNull()
                .first()
                .let { config ->
                    notificationScheduler.schedule(config.notificationRules ?: emptyList())
                }
        } catch (_: Exception) {}
    }
}
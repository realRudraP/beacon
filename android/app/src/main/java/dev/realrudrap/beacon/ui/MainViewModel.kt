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
import dev.realrudrap.beacon.work.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val workManager: WorkManager
): ViewModel() {
    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination : StateFlow<String?> = _startDestination

    init {
        checkSession()
        viewModelScope.launch {
            refreshConfigAndSchedule()
        }
        viewModelScope.launch {
            authEventBus.events.collect { event ->
                when(event){
                    is AuthEvent.Unauthorized,
                    is AuthEvent.Logout->{
                        handleLogout()
                    }

                }
            }
        }
        AttendanceSyncWorker.schedulePeriodicSync(workManager)
    }

    private fun checkSession(){
        val isLoggedIn = tokenManager.getRefreshToken() != null

        _startDestination.value = if (isLoggedIn) "dashboard" else "auth"
    }
    private fun handleLogout(){
        tokenManager.clearTokens()
        _startDestination.value = "auth"
    }

    private suspend fun refreshConfigAndSchedule() {
        configRepository.refreshConfig()
        configRepository.config
            .filterNotNull()
            .first()
            .let { config ->
                notificationScheduler.schedule(config.notificationRules ?: emptyList())
            }
    }

}
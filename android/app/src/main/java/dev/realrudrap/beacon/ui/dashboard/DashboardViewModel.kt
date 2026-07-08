package dev.realrudrap.beacon.ui.dashboard

import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.AuthEvent
import dev.realrudrap.beacon.data.remote.AuthEventBus
import dev.realrudrap.beacon.domain.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authEventBus: AuthEventBus,
    private val tokenManager: TokenManager
): ViewModel(){
    val user = userRepository.user.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun logout(){
        viewModelScope.launch {
            tokenManager.clearTokens()
            authEventBus.emit(AuthEvent.Logout)
        }
    }

    init{
        viewModelScope.launch {
            userRepository.refreshUser()
        }
    }
}
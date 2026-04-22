package dev.realrudrap.beacon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.AuthEvent
import dev.realrudrap.beacon.data.remote.AuthEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventBus: AuthEventBus
): ViewModel() {
    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination : StateFlow<String?> = _startDestination

    init {
        checkSession()
        viewModelScope.launch {
            authEventBus.events.collect { event ->
                when(event){
                    is AuthEvent.Unauthorized ->{
                        handleLogout()
                    }
                }
            }
        }
    }

    private fun checkSession(){
        val isLoggedIn = tokenManager.getRefreshToken() != null

        _startDestination.value = if (isLoggedIn) "dashboard" else "auth"
    }
    private fun handleLogout(){
        tokenManager.clearTokens()
        _startDestination.value = "auth"
    }

}
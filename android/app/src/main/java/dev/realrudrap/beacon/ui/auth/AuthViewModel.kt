package dev.realrudrap.beacon.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState{
    object Idle: AuthState()
    object Loading: AuthState()
    object Success: AuthState()
    data class Error(val message: String): AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel(){
    // Internal state
    private val _uiState = MutableStateFlow<AuthState>(AuthState.Idle)

    // Expose immutable state for UI to observe
    val uiState: StateFlow<AuthState> = _uiState

    fun login(username: String, password: String) {
     _uiState.value = AuthState.Loading

        viewModelScope.launch {
            val result = authRepository.login(username,password)

            result.onSuccess {
                _uiState.value = AuthState.Success
            }.onFailure { exception ->
                _uiState.value = AuthState.Error(exception.message ?: "Login failed")
            }
        }
    }

}
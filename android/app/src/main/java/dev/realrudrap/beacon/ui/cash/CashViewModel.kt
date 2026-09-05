package dev.realrudrap.beacon.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import dev.realrudrap.beacon.domain.repository.CashRepository
import dev.realrudrap.beacon.work.CashSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CashUiState(
    val balance: Double = 0.0,
    val transactions: List<CashTransactionEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val isSavingExpense: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class CashViewModel @Inject constructor(
    private val cashRepository: CashRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _isSavingExpense = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    // Direct 4-flow combine (within the 5-flow limit, fully type-safe)
    val uiState: StateFlow<CashUiState> = combine(
        cashRepository.balance,
        cashRepository.transactions,
        _isRefreshing,
        _errorMessage
    ) { balance, transactions, isRefreshing, errorMessage ->
        CashUiState(
            balance = balance,
            transactions = transactions,
            isRefreshing = isRefreshing,
            isSavingExpense = _isSavingExpense.value,
            errorMessage = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CashUiState()
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            val result = cashRepository.refreshLedger()
            if (result.isFailure) {
                _errorMessage.value = "Failed to sync latest ledger. Showing offline data."
            }
            CashSyncWorker.syncNow(workManager)
            _isRefreshing.value = false
        }
    }

    fun addExpense(amount: Double, category: String, description: String?) {
        viewModelScope.launch {
            _isSavingExpense.value = true
            val result = cashRepository.recordExpense(amount, category, description, null)
            if (result.isSuccess) {
                CashSyncWorker.syncNow(workManager)
            } else {
                _errorMessage.value = "Failed to record expense locally."
            }
            _isSavingExpense.value = false
        }
    }
}
package dev.realrudrap.beacon.domain.repository

import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import kotlinx.coroutines.flow.Flow

interface CashRepository {
    val balance: Flow<Double>
    val transactions: Flow<List<CashTransactionEntity>>

    suspend fun recordExpense(
        amount: Double,
        category: String,
        description: String?,
        photoLocalPath: String?
    ): Result<Unit>

    suspend fun refreshLedger(): Result<Unit>
}
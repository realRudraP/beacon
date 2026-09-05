package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.CashTransactionDao
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import dev.realrudrap.beacon.data.remote.CashApiService
import dev.realrudrap.beacon.data.remote.dto.CashTransactionResponse
import dev.realrudrap.beacon.domain.repository.CashRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Singleton
class CashRepositoryImpl @Inject constructor(
    private val cashTransactionDao: CashTransactionDao,
    private val cashApiService: CashApiService
) : CashRepository {

    override val balance: Flow<Double> = cashTransactionDao.getBalanceFlow()
    override val transactions: Flow<List<CashTransactionEntity>> = cashTransactionDao.getAllTransactionsFlow()

    @OptIn(ExperimentalTime::class)
    override suspend fun recordExpense(
        amount: Double,
        category: String,
        description: String?,
        photoLocalPath: String?
    ): Result<Unit> {
        return try {
            val entity = CashTransactionEntity(
                id = UUID.randomUUID().toString(),
                type = "debit",
                amount = amount,
                category = category,
                description = description,
                receiptPhotoLocalPath = photoLocalPath,
                receiptPhotoServerUri = null,
                syncStatus = AttendanceSyncStatus.PENDING.name,
                createdAt = Clock.System.now().toString()
            )
            cashTransactionDao.insert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to record expense locally")
            Result.failure(e)
        }
    }

    override suspend fun refreshLedger(): Result<Unit> {
        return try {
            val response = cashApiService.getTransactions()
            if (response.isSuccessful) {
                val ledger = response.body()
                val entities = ledger?.transactions?.map { it.toEntity() } ?: emptyList()
                cashTransactionDao.upsertServerTransactions(entities)
                Result.success(Unit)
            } else {
                Timber.e("Failed to refresh cash ledger: ${response.code()}")
                Result.failure(Exception("Server returned ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception refreshing cash ledger")
            Result.failure(e)
        }
    }

    private fun CashTransactionResponse.toEntity(): CashTransactionEntity {
        return CashTransactionEntity(
            id = this.id,
            type = this.type,
            amount = this.amount,
            category = this.category,
            description = this.description,
            receiptPhotoLocalPath = null,
            receiptPhotoServerUri = this.receiptPhotoUri,
            syncStatus = AttendanceSyncStatus.SYNCED.name,
            createdAt = this.createdAt
        )
    }
}
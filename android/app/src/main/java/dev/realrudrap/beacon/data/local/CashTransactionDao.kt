package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CashTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: CashTransactionEntity)

    @Update
    suspend fun update(transaction: CashTransactionEntity)

    @Query("SELECT * FROM cash_transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: String): CashTransactionEntity?

    @Query("SELECT * FROM cash_transactions ORDER BY createdAt DESC")
    fun getAllTransactionsFlow(): Flow<List<CashTransactionEntity>>

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'credit' THEN amount ELSE -amount END), 0.0) FROM cash_transactions")
    fun getBalanceFlow(): Flow<Double>

    @Query("SELECT * FROM cash_transactions WHERE syncStatus = 'PENDING'")
    suspend fun getPendingTransactions(): List<CashTransactionEntity>

    @Query("UPDATE cash_transactions SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("UPDATE cash_transactions SET receiptPhotoServerUri = :uri WHERE id = :id")
    suspend fun updateReceiptPhotoServerUri(id: String, uri: String)

    @Transaction
    suspend fun upsertServerTransactions(transactions: List<CashTransactionEntity>) {
        for (remote in transactions) {
            val local = getTransactionById(remote.id)
            when {
                local == null -> insert(remote)
                local.syncStatus != AttendanceSyncStatus.PENDING.name -> {
                    update(remote.copy(
                        receiptPhotoLocalPath = local.receiptPhotoLocalPath
                    ))
                }
            }
        }
    }
}
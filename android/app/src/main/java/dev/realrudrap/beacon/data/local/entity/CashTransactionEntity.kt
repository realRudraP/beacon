package dev.realrudrap.beacon.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cash_transactions")
data class CashTransactionEntity(
    @PrimaryKey val id: String,
    val type: String, // "credit" or "debit"
    val amount: Double,
    val category: String,
    val description: String?,
    val receiptPhotoLocalPath: String?,
    val receiptPhotoServerUri: String?,
    val syncStatus: String, // "PENDING", "SYNCED", "FAILED"
    val createdAt: String   // ISO-8601 string
)
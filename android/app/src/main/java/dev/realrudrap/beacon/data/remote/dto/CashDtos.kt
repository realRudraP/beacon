package dev.realrudrap.beacon.data.remote.dto


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateCashTransactionRequest(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String, // "debit"
    @SerialName("amount") val amount: Double,
    @SerialName("category") val category: String,
    @SerialName("description") val description: String?,
    @SerialName("receipt_photo_uri") val receiptPhotoUri: String?,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CashTransactionResponse(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("type") val type: String,
    @SerialName("amount") val amount: Double,
    @SerialName("category") val category: String,
    @SerialName("description") val description: String?,
    @SerialName("receipt_photo_uri") val receiptPhotoUri: String?,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String
)
@Serializable
data class CashLedgerResponse(
    @SerialName("balance") val balance: Double,
    @SerialName("transactions") val transactions: List<CashTransactionResponse>
)
package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.remote.dto.CashLedgerResponse
import dev.realrudrap.beacon.data.remote.dto.CashTransactionResponse
import dev.realrudrap.beacon.data.remote.dto.CreateCashTransactionRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface CashApiService {
    @GET("cash/transactions")
    suspend fun getTransactions(): Response<CashLedgerResponse>

    @POST("cash/transactions")
    suspend fun createTransaction(
        @Body request: CreateCashTransactionRequest
    ): Response<CashTransactionResponse>
}
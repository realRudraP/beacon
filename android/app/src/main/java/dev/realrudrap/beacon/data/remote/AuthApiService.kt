package dev.realrudrap.beacon.data.remote


import dev.realrudrap.beacon.data.remote.models.LoginRequest
import dev.realrudrap.beacon.data.remote.models.RefreshRequest
import dev.realrudrap.beacon.data.remote.models.TokenResponse
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {
    @POST("auth/refresh")
    fun refreshTokens(@Body request: RefreshRequest ): Call<TokenResponse>
    @POST("auth/login")
    fun login(@Body request: LoginRequest): Response<TokenResponse>
}
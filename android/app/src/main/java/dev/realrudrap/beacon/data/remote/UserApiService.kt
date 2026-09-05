package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.remote.dto.UpdateFcmTokenRequest
import dev.realrudrap.beacon.domain.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface UserApiService {
    @GET("users/me")
    suspend fun getMe(): Response<User>

    @POST("users/fcm-token")
    suspend fun updateFcmToken(
        @Body request: UpdateFcmTokenRequest
    ): Response<Unit>

}
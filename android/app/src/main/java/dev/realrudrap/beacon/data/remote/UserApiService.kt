package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.domain.model.User
import retrofit2.Response
import retrofit2.http.GET

interface UserApiService {
    @GET("users/me")
    suspend fun getMe(): Response<User>

}
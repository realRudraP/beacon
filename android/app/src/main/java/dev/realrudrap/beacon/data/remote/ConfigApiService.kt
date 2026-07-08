package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.domain.model.Config
import retrofit2.Response
import retrofit2.http.GET

interface ConfigApiService {
    @GET("config")
    suspend fun getConfig(): Response<Config>
}
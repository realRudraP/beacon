package dev.realrudrap.beacon.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.realrudrap.beacon.data.remote.AuthApiService
import dev.realrudrap.beacon.data.remote.TokenAuthenticator
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authenticator: TokenAuthenticator
    ): OkHttpClient{
        return OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit{
        val networkJson = Json {ignoreUnknownKeys = true}
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl("http://192.168.1.81:8080/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(networkJson.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit) : AuthApiService{
        return retrofit.create(AuthApiService::class.java)
    }
}
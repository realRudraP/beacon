package dev.realrudrap.beacon.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.realrudrap.beacon.data.remote.AttendanceApiService
import dev.realrudrap.beacon.data.remote.AuthApiService
import dev.realrudrap.beacon.data.remote.AuthInterceptor
import dev.realrudrap.beacon.data.remote.CashApiService
import dev.realrudrap.beacon.data.remote.ConfigApiService
import dev.realrudrap.beacon.data.remote.TokenAuthenticator
import dev.realrudrap.beacon.data.remote.UserApiService
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
        authInterceptor: AuthInterceptor,
        authenticator: TokenAuthenticator
    ): OkHttpClient{
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(authenticator)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit{
        val networkJson = Json {ignoreUnknownKeys = true}
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl("https://beacon.realrudrap.dev/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(networkJson.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit) : AuthApiService{
        return retrofit.create(AuthApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideUserApiService(retrofit: Retrofit): UserApiService{
        return retrofit.create(UserApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideConfigApiService(retrofit: Retrofit): ConfigApiService{
        return retrofit.create(ConfigApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideAttendanceApiService(retrofit: Retrofit): AttendanceApiService {
        return retrofit.create(AttendanceApiService::class.java)
    }
    @Provides
    @Singleton
    fun provideCashApiService(retrofit: Retrofit): CashApiService{
        return retrofit.create(CashApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideJson(): Json = Json{
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
}
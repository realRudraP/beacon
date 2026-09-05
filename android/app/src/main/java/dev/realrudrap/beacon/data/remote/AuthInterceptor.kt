package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventBus: AuthEventBus
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val accessToken = tokenManager.getAccessToken()
        val request = if (accessToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        // Catch 401 Unauthorized (ignore if the request was the login request itself)
        if (response.code == 401 && !originalRequest.url.encodedPath.contains("/auth/login")) {
            tokenManager.clearTokens()
            runBlocking {
                authEventBus.emit(AuthEvent.Unauthorized)
            }
        }

        return response
    }
}
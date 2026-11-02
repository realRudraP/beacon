package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.local.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Login and refresh must not carry a (possibly expired) access token.
        // Token lifecycle decisions are handled exclusively by TokenAuthenticator.
        if (isAuthEndpoint(originalRequest.url.encodedPath)) {
            return chain.proceed(originalRequest)
        }

        val accessToken = tokenManager.getAccessToken()
        val request = if (accessToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }

    private fun isAuthEndpoint(path: String): Boolean {
        return path.endsWith("/auth/login") || path.endsWith("/auth/refresh")
    }
}

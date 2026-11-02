package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.local.DeviceInfoManager
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.models.RefreshRequest
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenManager: TokenManager,
    private val authApiService: dagger.Lazy<AuthApiService>,
    private val deviceInfoManager: DeviceInfoManager,
    private val authEventBus: AuthEventBus,
    private val crashReporter: CrashReportingProvider
) : Authenticator {

    // This is used to prevent the thundering herd problem
    // in which multiple threads may try to refresh the tokens
    // simultaneously and engage in a race condition
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedRequest = response.request

        // Never try to authenticate the refresh/login calls themselves. If the
        // refresh call is rejected, the original call handles the failure below.
        if (isAuthEndpoint(failedRequest.url.encodedPath)) {
            return null
        }

        val failedToken = failedRequest.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val alreadyRetried = failedRequest.header(HEADER_TOKEN_REFRESHED) == "true"

        return runBlocking {
            mutex.withLock {
                val currentToken = tokenManager.getAccessToken()

                // Another call already refreshed while this one was in flight.
                // Retry with the newer token instead of refreshing again.
                if (!currentToken.isNullOrBlank() && currentToken != failedToken) {
                    log("retrying_with_newer_token")
                    return@runBlocking rewriteRequest(failedRequest, currentToken)
                }

                // We already retried this exact request after a refresh and it was
                // still rejected. This is a real auth failure, not a race.
                if (alreadyRetried) {
                    failPermanently("refreshed_token_rejected")
                    return@runBlocking null
                }

                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    // No way to recover without logging in again.
                    tokenManager.clearTokens()
                    logError("refresh_token_missing")
                    authEventBus.emit(AuthEvent.Unauthorized)
                    return@runBlocking null
                }

                val refreshRequest = RefreshRequest(
                    refreshToken,
                    deviceInfoManager.getDeviceInfoString()
                )

                val refreshResponse = try {
                    authApiService.get().refreshTokens(refreshRequest).execute()
                } catch (e: Exception) {
                    // Network error / timeout: the device may simply be offline.
                    // Keep the tokens so the request can be retried later instead of
                    // permanently logging the worker out.
                    logError("refresh_network_error", e)
                    return@runBlocking null
                }

                if (refreshResponse.isSuccessful) {
                    val newTokens = refreshResponse.body()
                    if (newTokens == null) {
                        logError("refresh_empty_body")
                        return@runBlocking null
                    }
                    tokenManager.saveTokens(newTokens.accessToken, newTokens.refreshToken)
                    log("refresh_success")
                    return@runBlocking rewriteRequest(failedRequest, newTokens.accessToken)
                }

                val code = refreshResponse.code()
                if (isDefinitiveAuthFailure(code)) {
                    failPermanently("refresh_rejected_http_$code")
                } else {
                    // 5xx / 408 / 429 etc. are transient. Keep tokens for a later retry.
                    logError("refresh_transient_http_$code")
                }
                return@runBlocking null
            }
        }
    }

    private suspend fun failPermanently(reason: String) {
        logError(reason)
        tokenManager.clearTokens()
        authEventBus.emit(AuthEvent.Unauthorized)
    }

    private fun rewriteRequest(request: Request, newAccessToken: String): Request {
        return request.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .header(HEADER_TOKEN_REFRESHED, "true")
            .build()
    }

    private fun isAuthEndpoint(path: String): Boolean {
        return path.endsWith("/auth/login") || path.endsWith("/auth/refresh")
    }

    private fun isDefinitiveAuthFailure(code: Int): Boolean {
        return code == 400 || code == 401 || code == 403
    }

    private fun log(message: String) {
        crashReporter.log("AUTH $message")
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        crashReporter.log("AUTH $message")
        crashReporter.recordException(AuthTokenException(message, throwable))
    }

    private class AuthTokenException(
        message: String,
        cause: Throwable? = null
    ) : Exception("beacon_auth_$message", cause)

    private companion object {
        const val HEADER_TOKEN_REFRESHED = "X-Beacon-Token-Refreshed"
    }
}

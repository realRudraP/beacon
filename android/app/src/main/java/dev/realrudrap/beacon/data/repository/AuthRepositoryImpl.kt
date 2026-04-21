package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.DeviceInfoManager
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.AuthApiService
import dev.realrudrap.beacon.data.remote.models.LoginRequest
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import dev.realrudrap.beacon.domain.repository.AuthRepository
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApiService,
    private val tokenManager: TokenManager,
    private val deviceInfoManager: DeviceInfoManager,
    private val logger: AppLogger,
    private val crashReporter: CrashReportingProvider
): AuthRepository{
    private val TAG = "AuthRepository"


    override suspend fun login(username: String, password: String): Result<Unit> {
        logger.d(TAG, "Attempting login for user: $username")
        return try {
            val deviceInfo = deviceInfoManager.getDeviceInfoString()
            val request = LoginRequest(username, password, deviceInfo)
            val response = api.login(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    logger.i(TAG, "login successful for user $username")
                    tokenManager.saveTokens(body.accessToken, body.refreshToken)
                    crashReporter.setUserId(username)
                    Result.success(Unit)
                } else {
                    logger.e(TAG, "Login successful, but response body is null")
                    Result.failure(Exception("Response body is null"))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "Invalid credentials. Please try again."
                    403 -> "Account is locked or restricted."
                    else -> "Unknown error. Code ${response.code()}"
                }
                logger.d(TAG, "Login rejected by server:" +
                        " HTTP ${response.code()} - $errorMessage")
                Result.failure(Exception(errorMessage))

            }
        } catch (e: Exception) {
            logger.e(TAG, "Network or unexpected error during login", e)
            crashReporter.recordException(e)
            Result.failure(e)
        }
    }

    override fun logout() {
        TODO("Not yet implemented")
    }
}

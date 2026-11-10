package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.local.UserDao
import dev.realrudrap.beacon.data.remote.UserApiService
import dev.realrudrap.beacon.data.remote.dto.UpdateFcmTokenRequest
import dev.realrudrap.beacon.domain.model.User
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val api: UserApiService,
    private val userDao: UserDao,
    private val tokenManager: TokenManager,
    private val logger: AppLogger
) : UserRepository{
    override val user: Flow<User?> = userDao.getUser()

    override suspend fun refreshUser(): Result<Unit> {
        return try {
            val response = api.getMe()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    userDao.saveUser(body)
                    logger.d(TAG, "User refreshed (HTTP ${response.code()})")
                } else {
                    logger.e(TAG, "User refresh returned an empty body (HTTP ${response.code()})", null)
                }
                Result.success(Unit)
            } else {
                logger.e(TAG, "Failed to fetch user: HTTP ${response.code()}", null)
                Result.failure(Exception("Failed to fetch user: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error fetching user", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFcmToken(token: String): Result<Unit> {
        if (tokenManager.getRefreshToken().isNullOrBlank()) {
            logger.d(TAG, "Skipping FCM token update because no user is signed in")
            return Result.success(Unit)
        }

        return try {
            val response = api.updateFcmToken(UpdateFcmTokenRequest(fcmToken = token))
            if (response.isSuccessful) {
                logger.d(TAG, "FCM token updated (HTTP ${response.code()})")
                Result.success(Unit)
            } else if (response.code() == 401 || response.code() == 403) {
                logger.w(TAG, "FCM token update was unauthorized (HTTP ${response.code()})")
                Result.failure(Exception("Failed to update FCM token on server: ${response.code()}"))
            } else {
                logger.e(TAG, "Failed to update FCM token: HTTP ${response.code()}", null)
                Result.failure(Exception("Failed to update FCM token on server: ${response.code()}"))
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error updating FCM token", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "UserRepository"
    }

}
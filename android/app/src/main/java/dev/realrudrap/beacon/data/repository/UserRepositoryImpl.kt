package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.UserDao
import dev.realrudrap.beacon.data.remote.UserApiService
import dev.realrudrap.beacon.data.remote.dto.UpdateFcmTokenRequest
import dev.realrudrap.beacon.domain.model.User
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import dev.realrudrap.beacon.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val api: UserApiService,
    private val userDao: UserDao,
    private val logger : AppLogger,
    private val crashReporter: CrashReportingProvider
) : UserRepository{
    override val user: Flow<User?> = userDao.getUser()

    override suspend fun refreshUser(): Result<Unit> {
        return try{
            val response = api.getMe()
            if(response.isSuccessful){
                response.body()?.let{userDao.saveUser(it)}
                Result.success(Unit)
            }else{
                Result.failure(Exception("Failed to fetch user"))
            }
        }catch (e: Exception){
            logger.e("UserRepository", "Error fetching user", e)
            crashReporter.recordException(e)
            Result.failure(e)
        }
        }

    override suspend fun updateFcmToken(token: String): Result<Unit> {
        return try {
            val response = api.updateFcmToken(UpdateFcmTokenRequest(fcmToken = token))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update FCM token on server: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
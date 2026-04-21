package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.local.DeviceInfoManager
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.remote.models.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val tokenManager : TokenManager,
    private val authApiService: dagger.Lazy<AuthApiService>,
    private val deviceInfoManager: DeviceInfoManager
) : Authenticator{
    // This is used to prevent the thundering herd problem
    // in which multiple threads may try to refresh the tokens
    // simultaneously and engage in a race condition
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val currentToken = tokenManager.getAccessToken()

        return runBlocking {
            mutex.withLock {
                val updatedToken = tokenManager.getAccessToken()

                // If the token changed while we waited for the lock,
                // another thread may have already updated it. Retry the request
                if (currentToken!=updatedToken){
                    return@runBlocking rewriteRequest(response.request, updatedToken)
                }

                // If we get a 401 the second time (which means we hit the /refresh)
                // and still are unauthenticated, the refresh token has expired and
                // the user needs to be logged out.
                if(response.priorResponse!=null){
                    tokenManager.clearTokens()
                    return@runBlocking null
                }

                val refreshToken = tokenManager.getRefreshToken() ?: return@runBlocking null
                val refreshRequest = RefreshRequest(refreshToken, deviceInfoManager.getDeviceInfoString())
                val refreshResponse = try{
                    authApiService.get().refreshTokens(refreshRequest).execute()
                }catch (e: Exception){
                    null
                }

                if (refreshResponse != null && refreshResponse.isSuccessful){
                    refreshResponse.body()?.let{ newTokens->
                        tokenManager.saveTokens(newTokens.accessToken, newTokens.refreshToken)
                        return@runBlocking rewriteRequest(response.request, newTokens.accessToken)
                    }
                }

                tokenManager.clearTokens()
                return@runBlocking null
            }
        }
    }

    private fun rewriteRequest(request: Request, newAccessToken: String?): Request {
        return request.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .build()
    }
}
package dev.realrudrap.beacon.domain.repository

interface AuthRepository {

    suspend fun login(username: String, password: String): Result<Unit>

    fun logout()
}
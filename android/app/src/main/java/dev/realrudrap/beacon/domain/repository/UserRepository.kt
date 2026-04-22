package dev.realrudrap.beacon.domain.repository

import dev.realrudrap.beacon.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    val user: Flow<User?>
    suspend fun refreshUser(): Result<Unit>
}
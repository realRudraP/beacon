package dev.realrudrap.beacon.domain.repository

import dev.realrudrap.beacon.domain.model.Config
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface ConfigRepository {
    val config: Flow<Config?>
    @OptIn(ExperimentalTime::class)
    val lastSyncedAt: Flow<Instant?>
    suspend fun refreshConfig(): Result<Unit>
}
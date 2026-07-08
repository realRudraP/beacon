package dev.realrudrap.beacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import dev.realrudrap.beacon.data.local.ConfigDao
import dev.realrudrap.beacon.data.remote.ConfigApiService
import dev.realrudrap.beacon.data.local.entity.ConfigEntity
import dev.realrudrap.beacon.domain.model.Config
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import dev.realrudrap.beacon.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ConfigRepositoryImpl @Inject constructor(
    private val api : ConfigApiService,
    private val configDao: ConfigDao,
    private val json: Json,
    private val logger: AppLogger,
    private val crashReporter: CrashReportingProvider,
    private val dataStore : DataStore<Preferences>
) : ConfigRepository{
    val LAST_SYNCED_AT_KEY = longPreferencesKey("last_synced_at")
    override val config: Flow<Config?> = configDao.getConfig().map { entity ->
        entity?.let { json.decodeFromString<Config>(it.json) }
    }
    @OptIn(ExperimentalTime::class)
    override suspend fun refreshConfig(): Result<Unit> {
        return try {
            logger.d("ConfigRepository", "Starting config refresh")
            val response = api.getConfig()
            logger.d("ConfigRepository", "Response code: ${response.code()}")
            if (response.isSuccessful) {
                val body = response.body()
                logger.d("ConfigRepository", "Body: $body")
                if (body != null) {
                    val encoded = json.encodeToString(body)
                    logger.d("ConfigRepository", "Encoded: $encoded")
                    configDao.saveConfig(ConfigEntity(json = encoded))
                    dataStore.edit { prefs ->
                        prefs[LAST_SYNCED_AT_KEY] = Clock.System.now().toEpochMilliseconds()
                    }
                    logger.d("ConfigRepository", "Saved to DB")
                    logger.d("ConfigRepository", "Config saved to DB successfully")
                } else {
                    logger.e("ConfigRepository", "Body was null despite 200", null)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to fetch config: ${response.code()}"))
            }
        } catch (e: Exception) {
            logger.e("ConfigRepository", "Error fetching config", e)
            crashReporter.recordException(e)
            Result.failure(e)
        }
    }
    @OptIn(ExperimentalTime::class)
    override val lastSyncedAt: Flow<Instant?> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences())
            else throw e
        }
        .map { prefs ->
            prefs[LAST_SYNCED_AT_KEY]?.let { Instant.fromEpochMilliseconds(it) }
        }

}
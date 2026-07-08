package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.realrudrap.beacon.data.local.entity.ConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao{
    @Query("SELECT * FROM config WHERE id = 1")
    fun getConfig(): Flow<ConfigEntity?>

    @Upsert
    suspend fun saveConfig(config: ConfigEntity)

    @Query("DELETE FROM config WHERE id = 1")
    suspend fun clearConfig()
}
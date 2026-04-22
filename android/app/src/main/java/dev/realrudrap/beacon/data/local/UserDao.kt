package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.realrudrap.beacon.domain.model.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun getUser(): Flow<User?>

    @Upsert
    suspend fun saveUser(user: User)

    @Query("DELETE FROM users")
    suspend fun clearUser()
}
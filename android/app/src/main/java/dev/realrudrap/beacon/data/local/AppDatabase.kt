package dev.realrudrap.beacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.realrudrap.beacon.domain.model.User

@Database(entities = [User::class], version = 1, exportSchema = false)
abstract class AppDatabase: RoomDatabase() {
    abstract fun userDao(): UserDao
}
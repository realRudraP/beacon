package dev.realrudrap.beacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import dev.realrudrap.beacon.data.local.entity.ConfigEntity
import dev.realrudrap.beacon.domain.model.User

@Database(entities = [User::class, ConfigEntity::class, AttendanceEntity::class, CashTransactionEntity::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase: RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun configDao(): ConfigDao
    abstract fun attendanceDao(): AttendanceDao
    abstract  fun cashTransactionDao(): CashTransactionDao
}
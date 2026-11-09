package dev.realrudrap.beacon.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.realrudrap.beacon.data.local.AppDatabase
import dev.realrudrap.beacon.data.local.AppMigrations
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.CashTransactionDao
import dev.realrudrap.beacon.data.local.ConfigDao
import dev.realrudrap.beacon.data.local.LocationCheckDao
import dev.realrudrap.beacon.data.local.UserDao
import javax.inject.Singleton


private val Context.dataStore by preferencesDataStore(name="beacon_prefs")
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase{
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "beacon_db"
        )
            .addMigrations(AppMigrations.MIGRATION_5_6)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideUserDao(db: AppDatabase): UserDao{
        return db.userDao()
    }

    @Provides
    @Singleton
    fun provideConfigDao(db: AppDatabase): ConfigDao{
        return db.configDao()
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences>{
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideAttendanceDao(db: AppDatabase): AttendanceDao {
        return db.attendanceDao()
    }
    @Provides
    @Singleton
    fun provideCashDao(db: AppDatabase): CashTransactionDao{
        return db.cashTransactionDao()
    }

    @Provides
    @Singleton
    fun provideLocationCheckDao(db: AppDatabase): LocationCheckDao {
        return db.locationCheckDao()
    }
}
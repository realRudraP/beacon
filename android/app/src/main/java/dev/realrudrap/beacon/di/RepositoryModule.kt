package dev.realrudrap.beacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.realrudrap.beacon.data.repository.AttendanceRepositoryImpl
import dev.realrudrap.beacon.data.repository.AuthRepositoryImpl
import dev.realrudrap.beacon.data.repository.ConfigRepositoryImpl
import dev.realrudrap.beacon.data.repository.UserRepositoryImpl
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import dev.realrudrap.beacon.domain.repository.AuthRepository
import dev.realrudrap.beacon.domain.repository.ConfigRepository
import dev.realrudrap.beacon.domain.repository.UserRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAuthRepository(
        authRepositoryImpl: AuthRepositoryImpl
    ): AuthRepository

    @Binds
    abstract fun bindAttendanceRepository(
        impl: AttendanceRepositoryImpl
    ): AttendanceRepository


    @Binds
    abstract fun bindUserRepository(
        userRepositoryImpl: UserRepositoryImpl
    ): UserRepository

    @Binds
    abstract fun bindConfigRepository(
        configRepositoryImpl: ConfigRepositoryImpl
    ): ConfigRepository
}
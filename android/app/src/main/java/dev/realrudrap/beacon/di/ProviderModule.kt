package dev.realrudrap.beacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.realrudrap.beacon.data.provider.AppLoggerImpl
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import dev.realrudrap.beacon.data.provider.SentryCrashReportingProvider
import dev.realrudrap.beacon.domain.provider.AppLogger

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {
    @Binds
    abstract fun bindCrashReportingProvider(
        sentryCrashReportingProvider: SentryCrashReportingProvider
    ): CrashReportingProvider
    @Binds
    abstract fun bindAppLogger(
        impl: AppLoggerImpl
    ): AppLogger
}

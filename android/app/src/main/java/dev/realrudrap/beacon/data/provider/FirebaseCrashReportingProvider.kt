package dev.realrudrap.beacon.data.provider

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import javax.inject.Inject

class FirebaseCrashReportingProvider @Inject constructor(): CrashReportingProvider {
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics.log(message)
    }

    override fun setUserId(userId: String) {
        crashlytics.setUserId(userId)
    }
}
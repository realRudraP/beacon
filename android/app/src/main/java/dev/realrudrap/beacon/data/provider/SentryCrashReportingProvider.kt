package dev.realrudrap.beacon.data.provider

import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import io.sentry.Sentry
import io.sentry.protocol.User
import javax.inject.Inject

class SentryCrashReportingProvider @Inject constructor() : CrashReportingProvider {
    override fun recordException(throwable: Throwable) {
        Sentry.captureException(throwable)
    }

    override fun setUserId(userId: String?) {
        Sentry.setUser(userId?.let { User().apply { id = it } })
    }
}

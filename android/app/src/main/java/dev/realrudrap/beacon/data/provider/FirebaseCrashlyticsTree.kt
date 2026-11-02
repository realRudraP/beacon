package dev.realrudrap.beacon.data.provider

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Forwards Timber logs to Firebase Crashlytics so diagnostics from worker
 * devices are available remotely even when the backend is unreachable.
 *
 * Crashlytics only uploads these as breadcrumbs alongside a crash or a
 * non-fatal report, so important failures should also be reported via
 * [dev.realrudrap.beacon.domain.provider.CrashReportingProvider.recordException].
 */
class FirebaseCrashlyticsTree : Timber.Tree() {
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return

        val line = if (tag.isNullOrBlank()) message else "$tag: $message"
        crashlytics.log(line)
    }
}

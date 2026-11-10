package dev.realrudrap.beacon.data.provider

import dev.realrudrap.beacon.domain.provider.AppLogger
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class AppLoggerImpl @Inject constructor(): AppLogger {
    override fun d(tag: String, message: String) {
        Timber.tag(tag).d(message)
    }

    override fun i(tag: String, message: String) {
        Timber.tag(tag).i(message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Timber.tag(tag).w(throwable, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Timber.tag(tag).e(throwable, message)
    }

    override suspend fun exportLogs(): File? {
        TODO("Not yet implemented")
    }

}
package dev.realrudrap.beacon.domain.provider

interface AppLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)

    suspend fun exportLogs(): java.io.File?
}

interface CrashReportingProvider {
    fun recordException(throwable: Throwable)
    fun setUserId(userId: String?)
}

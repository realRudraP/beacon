package dev.realrudrap.beacon.domain.provider

interface AppLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String)

    suspend fun exportLogs(): java.io.File?
}

interface CrashReportingProvider{
    fun recordException(throwable: Throwable)
    fun log(message: String)
    fun setUserId(userId: String)
}

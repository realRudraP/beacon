package dev.realrudrap.beacon.data.location

import android.location.Location

sealed interface LocationError {
    data object PermissionDenied : LocationError
    data object GpsDisabled : LocationError
    data object Timeout : LocationError
    data object Unavailable : LocationError

    fun toUserMessage(): String = when (this) {
        is PermissionDenied -> "Location permission was denied. Please enable it in Settings → Apps → Beacon → Permissions and try again."
        is GpsDisabled -> "GPS is turned off. Please enable Location in your device Settings and try again."
        is Timeout -> "Could not get your location in 60 seconds. Move to an open area, ensure GPS is enabled, and try again."
        is Unavailable -> "Location is unavailable on this device. Please contact your supervisor."
    }
}

interface LocationProvider {
    /**
     * Requests a fresh location fix. Never returns a cached location older than
     * [MAX_LOCATION_AGE_MS]. Tries FusedLocationProviderClient first, falls back
     * to GPS via LocationManager, then Network if GPS is unavailable.
     *
     * @return Result.success with a fresh [Location], or Result.failure with a
     * [LocationException] whose message is safe to show directly to the worker.
     */
    suspend fun getFreshLocation(): Result<Location>
}

class LocationException(val error: LocationError) : Exception(error.toUserMessage())
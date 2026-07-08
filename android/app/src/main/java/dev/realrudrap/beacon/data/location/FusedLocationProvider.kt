package dev.realrudrap.beacon.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.realrudrap.beacon.domain.provider.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

private const val LOCATION_TIMEOUT_MS = 60_000L
private const val MAX_LOCATION_AGE_MS = 30_000L

class FusedLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : LocationProvider {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun getFreshLocation(): Result<Location> {
        if (!hasLocationPermission()) {
            logger.d("LocationProvider","Permission denied")
            return Result.failure(LocationException(LocationError.PermissionDenied))
        }
        if (!isGpsEnabled() && !isNetworkEnabled()) {
            return Result.failure(LocationException(LocationError.GpsDisabled))
        }
        logger.d("LocationProvider", "Starting location request")
        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            logger.d("LocationProvider", "Trying fused...")
            val fused = tryFused()
            logger.d("LocationProvider", "Fused result: $fused")
            fused ?: run {
                logger.d("LocationProvider", "Trying GPS...")
                tryGps()
            } ?: run {
                logger.d("LocationProvider", "Trying network...")
                tryNetwork()
            }
        }
        logger.d("LocationProvider", "Final location: $location")
        return if (location != null) {
            Result.success(location)
        } else {
            if (!isGpsEnabled() && !isNetworkEnabled()) {
                Result.failure(LocationException(LocationError.GpsDisabled))
            } else {
                Result.failure(LocationException(LocationError.Timeout))
            }
        }
    }


    /**
     * Tries FusedLocationProviderClient with HIGH_ACCURACY.
     * Rejects cached locations older than [MAX_LOCATION_AGE_MS].
     */
    private suspend fun tryFused(): Location? {
        return suspendCancellableCoroutine { cont ->
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
                .setMaxUpdates(1)
                .setWaitForAccurateLocation(true)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    fusedClient.removeLocationUpdates(this)
                    val location = result.lastLocation
                    if (location != null && isFresh(location)) {
                        cont.resume(location)
                    } else {
                        cont.resume(null)
                    }
                }
            }

            try {
                fusedClient.requestLocationUpdates(request, callback, context.mainLooper)
                cont.invokeOnCancellation {
                    fusedClient.removeLocationUpdates(callback)
                }
            } catch (e: SecurityException) {
                cont.resume(null)
            }
        }
    }

    /**
     * Directly requests a fix from the GPS hardware provider.
     * Used when Fused returns stale or null.
     */
    private suspend fun tryGps(): Location? {
        if (!isGpsEnabled()) return null
        return requestFromProvider(LocationManager.GPS_PROVIDER)
    }

    /**
     * Last resort — network-based location.
     * Less accurate but better than nothing indoors or in weak GPS areas.
     */
    private suspend fun tryNetwork(): Location? {
        if (!isNetworkEnabled()) return null
        return requestFromProvider(LocationManager.NETWORK_PROVIDER)
    }

    private suspend fun requestFromProvider(provider: String): Location? {
        return suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    cont.resume(location)
                }

                @Deprecated("Deprecated in API level 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    locationManager.removeUpdates(this)
                    cont.resume(null)
                }
            }

            try {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,   // minTimeMs — get it as fast as possible
                    0f,   // minDistanceM — no distance filter
                    listener
                )
                cont.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
            } catch (e: SecurityException) {
                cont.resume(null)
            }
        }
    }

    private fun isFresh(location: Location): Boolean {
        return System.currentTimeMillis() - location.time < MAX_LOCATION_AGE_MS
    }

    private fun isGpsEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun isNetworkEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
package com.example.homedash.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Coarse location from the network (Wi-Fi and cell), or null if it's off, denied, or times out. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, LocationManager.NETWORK_PROVIDER, cancel, ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        } ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    /** City for a location from the device's geocoder, or null if it has none or the lookup fails. */
    @Suppress("DEPRECATION") // The listener version needs API 33; the blocking one is fine off the main thread.
    suspend fun cityName(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
        }.getOrNull()
    }
}

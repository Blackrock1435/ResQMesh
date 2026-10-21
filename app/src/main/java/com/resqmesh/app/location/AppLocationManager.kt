package com.resqmesh.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.resqmesh.core.LocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class LocResult {
    /** [stale] = true when this is a last-known fix older than [AppLocationManager.STALE_MS]. */
    data class Fix(val loc: LocationData, val stale: Boolean, val obtainedAtMs: Long) : LocResult()
    data class Error(val reason: String) : LocResult()
}

/**
 * Uses the platform LocationManager (GNSS chip). GPS positioning itself needs no internet.
 * Never fabricates coordinates: either a real fix, a clearly-labelled last-known fix, or an error.
 */
class AppLocationManager(private val ctx: Context) {
    companion object { const val STALE_MS = 60_000L }
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val last = MutableStateFlow<LocResult?>(null)
    val busy = MutableStateFlow(false)

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private class OneShot(val cb: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = cb(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    suspend fun acquire(timeoutMs: Long = 30_000): LocResult {
        if (!hasPermission()) return LocResult.Error("Location permission is not granted. You can still send text messages.").also { last.value = it }
        busy.value = true
        try {
            val gpsOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            var fresh: Location? = null
            if (gpsOn) {
                var l: OneShot? = null
                try {
                    fresh = withTimeoutOrNull(timeoutMs) {
                        suspendCancellableCoroutine<Location> { cont ->
                            val listener = OneShot { if (cont.isActive) cont.resume(it) }
                            l = listener
                            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                        }
                    }
                } finally { l?.let { runCatching { lm.removeUpdates(it) } } }
            }
            val now = System.currentTimeMillis()
            val res = if (fresh != null) toResult(fresh, now, forceFresh = true) else {
                val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
                if (lastKnown != null) toResult(lastKnown, now, forceFresh = false)
                else LocResult.Error(
                    if (!gpsOn) "Location is turned off. Turn on Location in system settings, then retry. You can still send text messages."
                    else "No GPS fix yet. Move near a window or outdoors and retry (a first fix can take a minute without internet). You can still send text messages."
                )
            }
            last.value = res
            return res
        } finally { busy.value = false }
    }

    private fun toResult(l: Location, now: Long, forceFresh: Boolean): LocResult {
        val t = if (l.time > 0) l.time else now
        val data = LocationData(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 9999f, t)
        return LocResult.Fix(data, stale = !forceFresh && now - t > STALE_MS, obtainedAtMs = now)
    }
}

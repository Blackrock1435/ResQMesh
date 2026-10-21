package com.resqmesh.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Perms {
    /** Runtime permissions needed for Nearby Connections on this Android version (official docs). */
    fun transport(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Notifications are helpful but not required for the mesh to work. */
    fun isOptional(p: String) = p == Manifest.permission.POST_NOTIFICATIONS

    fun missing(ctx: Context, perms: Array<String>) =
        perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun missingRequired(ctx: Context, perms: Array<String>) = missing(ctx, perms).filterNot { isOptional(it) }
    fun short(p: String) = p.substringAfterLast('.')
}

private val tf = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
fun fmtTime(ms: Long): String = if (ms <= 0) "never" else tf.format(Date(ms))
fun ageText(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when { s < 60 -> "$s s ago"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}
fun shortId(id: String) = id.take(6) + "…" + id.takeLast(4)
fun fmtCoord(lat: Double, lon: Double) = String.format(Locale.US, "%.6f, %.6f", lat, lon)

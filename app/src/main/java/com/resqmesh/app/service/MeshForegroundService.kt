package com.resqmesh.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.resqmesh.app.MainActivity
import com.resqmesh.app.R
import com.resqmesh.app.ResQApp

object Notifier {
    const val CH_MESH = "mesh"
    const val CH_SOS = "sos"
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESH, "Mesh network status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun sos(ctx: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val pi = android.app.PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_SOS).setSmallIcon(R.drawable.ic_launcher).setContentTitle("SOS received")
            .setContentText(text).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(2001, n)
    }
}

/** Keeps the process alive while the user has the mesh running. Shows a persistent, honest notification. */
class MeshForegroundService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as ResQApp).mesh.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        Notifier.channels(this)
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val stop = android.app.PendingIntent.getService(this, 1, Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, Notifier.CH_MESH).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ResQMesh network is ON").setContentText("This phone is discoverable and scanning for nearby devices.")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop network", stop).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, 1001, n, type)
        return START_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.resqmesh.app.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MeshForegroundService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, MeshForegroundService::class.java).setAction(ACTION_STOP)) }
    }
}

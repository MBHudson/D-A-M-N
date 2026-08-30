package com.damn.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.damn.app.R
import com.damn.app.util.Prefs
import kotlinx.coroutines.delay

class BootWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("DAMN-BootWorker", "Work started, auto-starting server...")
        
        // Slight delay to allow system/network to stabilize after boot
        delay(5000)
        
        val port = Prefs.getPort(applicationContext)
        val nat = Prefs.isNatEnabled(applicationContext)
        
        if (Build.VERSION.SDK_INT >= 35) { // Android 15+
            Log.i("DAMN-BootWorker", "Android 15+ detected, showing start notification instead of starting service")
            showStartNotification(port, nat)
            return Result.success()
        }

        try {
            ServerService.start(applicationContext, port, nat)
            Log.i("DAMN-BootWorker", "ServerService start signal sent")
        } catch (e: Exception) {
            Log.e("DAMN-BootWorker", "Failed to start ServerService: ${e.message}")
            return Result.retry()
        }
        
        return Result.success()
    }

    private fun showStartNotification(port: Int, nat: Boolean) {
        ServerService.createChannel(applicationContext)
        val label = Prefs.getHostLabel(applicationContext).ifEmpty { "host" }
        
        val startIntent = Intent(applicationContext, ServerService::class.java).apply {
            action = ServerService.ACTION_START
            putExtra(ServerService.EXTRA_PORT, port)
            putExtra(ServerService.EXTRA_NAT, nat)
        }
        
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(applicationContext, 2, startIntent, flags)
        } else {
            PendingIntent.getService(applicationContext, 2, startIntent, flags)
        }

        val notif = NotificationCompat.Builder(applicationContext, ServerService.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_boot_ready_title))
            .setContentText(applicationContext.getString(R.string.notif_boot_ready_text, label))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, applicationContext.getString(R.string.tap_to_start), pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1002, notif)
    }
}

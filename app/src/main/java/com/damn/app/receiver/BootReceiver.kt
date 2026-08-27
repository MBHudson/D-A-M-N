package com.damn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.damn.app.service.BootWorker
import com.damn.app.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("DAMN-Boot", "received $action")
        if (action in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) {
            val shouldStart = Prefs.isStartAtBoot(context) && Prefs.hasHost(context)
            if (shouldStart) {
                Log.i("DAMN-Boot", "enqueuing BootWorker for Android 15+ compatible auto-start")
                val workRequest = OneTimeWorkRequestBuilder<BootWorker>().build()
                WorkManager.getInstance(context).enqueue(workRequest)
            } else {
                Log.i("DAMN-Boot", "not auto-starting (startAtBoot=${Prefs.isStartAtBoot(context)}, hasHost=${Prefs.hasHost(context)})")
            }
        }
    }
}

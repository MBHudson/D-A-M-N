package com.damn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.damn.app.service.ServerService
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
            val shouldStart = Prefs.isStartAtBoot(context) && Prefs.wasRunning(context) && Prefs.hasHost(context)
            // Alternative policy: if startAtBoot is true, start even if wasn't running, as long as host exists
            // We honor both: if startAtBoot && hasHost -> start (user expects boot hosting)
            val shouldStartAlt = Prefs.isStartAtBoot(context) && Prefs.hasHost(context)
            if (shouldStart || shouldStartAlt) {
                Log.i("DAMN-Boot", "auto-starting D·A·M·N server")
                // slight delay to let network/wifi stabilize
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    ServerService.start(context, Prefs.getPort(context), Prefs.isNatEnabled(context))
                }, 8000)
            } else {
                Log.i("DAMN-Boot", "not auto-starting (startAtBoot=${Prefs.isStartAtBoot(context)}, wasRunning=${Prefs.wasRunning(context)}, hasHost=${Prefs.hasHost(context)})")
            }
        }
    }
}

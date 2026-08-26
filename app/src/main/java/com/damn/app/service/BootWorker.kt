package com.damn.app.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        
        try {
            ServerService.start(applicationContext, port, nat)
            Log.i("DAMN-BootWorker", "ServerService start signal sent")
        } catch (e: Exception) {
            Log.e("DAMN-BootWorker", "Failed to start ServerService: ${e.message}")
            return Result.retry()
        }
        
        return Result.success()
    }
}

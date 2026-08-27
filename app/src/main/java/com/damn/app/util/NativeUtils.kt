package com.damn.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object NativeUtils {
    private const val TAG = "DAMN-NativeUtils"

    /**
     * Extracts a binary from assets to internal storage and makes it executable.
     * @return Path to the executable, or null if failed.
     */
    fun installBinary(context: Context, assetPath: String, targetName: String): String? {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()

        val targetFile = File(binDir, targetName)
        
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Set executable permission
            targetFile.setExecutable(true, false)
            
            Log.i(TAG, "Binary installed: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install binary $assetPath", e)
            return null
        }
    }

    fun getBinaryPath(context: Context, targetName: String): String? {
        val binFile = File(context.filesDir, "bin/$targetName")
        return if (binFile.exists() && binFile.canExecute()) binFile.absolutePath else null
    }
}

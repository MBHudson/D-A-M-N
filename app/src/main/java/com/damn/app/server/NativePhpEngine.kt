package com.damn.app.server

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Engine that uses a native PHP binary to render scripts.
 */
class NativePhpEngine(private val binaryPath: String) : PhpEngine {
    
    override fun render(file: File, docRoot: File): String {
        return try {
            val pb = ProcessBuilder(binaryPath, file.absolutePath)
                .directory(docRoot)
                .redirectErrorStream(true)
            pb.environment()["TMPDIR"] = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
            val process = pb.start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(5, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return "<!-- DAMN Native engine timeout -->\n$output"
            }

            output
        } catch (e: Exception) {
            Log.e("DAMN-NativePhp", "Failed to execute PHP", e)
            "<!-- DAMN Native engine error: ${e.message} -->"
        }
    }
}

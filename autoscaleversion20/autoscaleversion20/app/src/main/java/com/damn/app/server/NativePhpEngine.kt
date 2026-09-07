package com.damn.app.server

import android.util.Log
import com.damn.app.util.DamnVfs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Engine that uses a native PHP binary to render scripts.
 */
class NativePhpEngine(private val binaryPath: String) : PhpEngine {
    
    override fun render(path: String, vfs: DamnVfs, cacheDir: File): String {
        val tempFile = vfs.getAsFile(path, cacheDir) ?: return "<!-- DAMN Native engine error: could not resolve file -->"
        return try {
            val pb = ProcessBuilder(binaryPath, tempFile.absolutePath)
                .directory(cacheDir)
                .redirectErrorStream(true)
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
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
        } finally {
            try { tempFile.delete() } catch (_: Exception) {}
        }
    }
}

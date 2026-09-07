package com.damn.app.server

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the embedded ngrok agent (a purpose-built bionic-linked binary built
 * from the official ngrok-go library) as a child process and translates its
 * stdout protocol into callbacks:
 *
 *   TUNNEL_URL <url>    -> onReady
 *   AGENT_ERROR <msg>   -> surfaced as errors
 *   anything else       -> onProgress
 */
class NgrokManager(private val context: Context) {

    companion object {
        private const val TAG = "DAMN-NgrokManager"
        private const val READY_TIMEOUT_MS = 30_000L
    }

    private var process: Process? = null
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    fun start(
        token: String,
        domain: String,
        localPort: Int,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
        onProgress: ((String) -> Unit)? = null
    ) {
        if (running.get()) return
        val bin = File(context.applicationInfo.nativeLibraryDir, "libngrok.so")
        if (!bin.exists()) {
            onError("ngrok agent (libngrok.so) missing from native library directory")
            return
        }
        running.set(true)
        stopRequested.set(false)

        val cleanDomain = domain.trim()
            .removePrefix("https://").removePrefix("http://")
            .trimEnd('/').substringBefore('/')

        val args = mutableListOf(
            bin.absolutePath,
            "--token=$token",
            "--port=$localPort"
        )
        if (cleanDomain.isNotBlank()) args.add("--domain=$cleanDomain")

        Thread {
            val ready = AtomicBoolean(false)
            try {
                Log.i(TAG, "Starting ngrok agent (port $localPort${if (cleanDomain.isNotBlank()) ", domain $cleanDomain" else ""})")
                val pb = ProcessBuilder(args).redirectErrorStream(true)
                pb.environment()["HOME"] = context.filesDir.absolutePath
                process = pb.start()

                // Safety net: if the tunnel isn't up within the timeout, say so.
                Thread {
                    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline &&
                        !ready.get() && process?.isAlive == true
                    ) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    }
                    if (!ready.get() && process?.isAlive == true && !stopRequested.get()) {
                        onProgress?.invoke("still connecting after ${READY_TIMEOUT_MS / 1000}s...")
                    }
                }.apply { isDaemon = true }.start()

                val reader = process!!.inputStream.bufferedReader()
                var lastError: String? = null
                val tail = ArrayDeque<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    Log.d(TAG, "ngrok: $l")
                    tail.addLast(l)
                    if (tail.size > 5) tail.removeFirst()
                    when {
                        l.startsWith("TUNNEL_URL ") && !ready.get() -> {
                            val url = l.removePrefix("TUNNEL_URL ").trim()
                            if (url.isNotEmpty() && ready.compareAndSet(false, true)) {
                                Log.i(TAG, "Tunnel ready: $url")
                                onReady(url)
                            }
                        }
                        l.startsWith("AGENT_ERROR ") -> {
                            lastError = l.removePrefix("AGENT_ERROR ").trim()
                            onProgress?.invoke(lastError!!)
                        }
                        else -> if (!ready.get()) onProgress?.invoke(l)
                    }
                }

                // Stream ended -> process exited
                if (!ready.get()) {
                    if (stopRequested.get()) {
                        Log.i(TAG, "ngrok agent terminated by request")
                    } else {
                        val code = process?.waitFor() ?: -1
                        Log.e(TAG, "ngrok exited with code $code")
                        val raw = if (lastError == null && tail.isNotEmpty()) " | last output: ${tail.joinToString(" ⏎ ")}" else ""
                        onError("ngrok exited (code $code)" +
                                (lastError?.let { ": $it" } ?: "") + raw +
                                ". Check Auth Token / domain (reserved & not used elsewhere?)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ngrok execution failed", e)
                if (!ready.get()) onError("ngrok failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stop() {
        stopRequested.set(true)
        running.set(false)
        process?.destroy()
        process = null
    }

    fun getIsRunning() = running.get()
}

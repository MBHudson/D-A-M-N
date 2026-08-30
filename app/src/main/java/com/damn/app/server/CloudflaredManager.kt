package com.damn.app.server

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CloudflaredManager(private val context: Context) {

    companion object {
        private const val TAG = "DAMN-Cloudflared"
        private const val READY_TIMEOUT_MS = 35000L
        private val URL_REGEX = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com[^\\s]*")
        private val CFARGOT_REGEX = Regex("https://[a-zA-Z0-9.-]+\\.cfargotunnel\\.com[^\\s]*")
    }

    private var process: Process? = null
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var dnsForwarder: LoopbackDnsForwarder? = null

    fun start(
        token: String,
        localPort: Int,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
        onProgress: ((String) -> Unit)? = null
    ) {
        if (running.get()) return
        // Prefer bundled android binary (nativeLibraryDir, now GOOS=android 25MB) — filesDir is noexec on API 29+ so Permission denied
        val rawCandidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so"),
            File(context.filesDir, "bin/cloudflared"),
            File(context.filesDir, "libcloudflared.so"),
            File("/data/data/com.termux/files/usr/bin/cloudflared"),
            File("/storage/emulated/0/Download/libcloudflared.so"),
            File("/sdcard/Download/libcloudflared.so")
        )
        // Resolve Download -> files copy before probing
        val candidates = rawCandidates.map { bin ->
            if (bin.absolutePath.contains("/Download/") && bin.exists()) {
                try {
                    val dst = File(context.filesDir, "bin/cloudflared")
                    dst.parentFile?.mkdirs()
                    bin.copyTo(dst, overwrite = true)
                    dst.setExecutable(true)
                    Log.i(TAG, "copied Download binary to ${dst.absolutePath}")
                    dst
                } catch (e: Exception) {
                    Log.w(TAG, "copy from Download failed: ${e.message}")
                    bin
                }
            } else bin
        }
        var bin: File? = null
        for (c in candidates) if (c.exists() && c.canRead()) { bin = c; break }
        if (bin == null || !bin.exists()) {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            onError("Cloudflare: binary not found for $abi architecture. (Note: Project only bundles arm64-v8a by default). Use 'Import' in Advanced settings to provide a compatible binary for this device.")
            return
        }
        // Ensure executable (nativeLibraryDir already exec, filesDir will fail on W^X but we fallback)
        try { bin.setExecutable(true) } catch (_: Exception) {}
        running.set(true)
        stopRequested.set(false)

        // DNS bridge for glibc cloudflared (needs [::1]:53, see LoopbackDnsForwarder)
        try {
            dnsForwarder = LoopbackDnsForwarder(context)
            val bound = dnsForwarder?.start { msg -> Log.i(TAG, msg) } ?: emptyList()
            if (bound.isEmpty()) Log.w(TAG, "loopback :53 not bound — quick tunnel DNS may fail (kernel blocked <1024)")
        } catch (e: Exception) {
            Log.w(TAG, "dns forwarder start failed: ${e.message}")
        }

        val isQuick = token.isBlank()
        fun buildArgs(binPath: String): MutableList<String> {
            val a = mutableListOf(binPath)
            // NOTE: --no-autoupdate is a global flag (must be before `tunnel`), not a `tunnel` subcommand flag.
            // User-reported fix: `cloudflared tunnel --url localhost:PORT` is the canonical quick-tunnel form;
            // we keep http://localhost for explicit http and move --no-autoupdate before tunnel.
            if (isQuick) a.addAll(listOf("--no-autoupdate", "tunnel", "--url", "http://localhost:$localPort"))
            else a.addAll(listOf("--no-autoupdate", "tunnel", "run", "--token", token))
            return a
        }

        Thread {
            val ready = AtomicBoolean(false)
            try {
                // Fallback loop: try each candidate until one starts (handles W^X Permission denied on filesDir)
                var lastStartErr: Exception? = null
                var started = false
                for (candidate in candidates) {
                    if (!candidate.exists() || !candidate.canRead()) continue
                    try { candidate.setExecutable(true) } catch (_: Exception) {}
                    val cArgs = buildArgs(candidate.absolutePath)
                    Log.i(TAG, "Trying cloudflared: ${cArgs.joinToString(" ")}")
                    try {
                        val pb = ProcessBuilder(cArgs).redirectErrorStream(true)
                        val env = pb.environment()
                        env["HOME"] = context.filesDir.absolutePath
                        env["TMPDIR"] = context.cacheDir.absolutePath
                        env["TEMP"] = context.cacheDir.absolutePath
                        // Force Go to use the system resolver (Bionic) instead of looking for /etc/resolv.conf
                        env["GODEBUG"] = "netdns=cgo"
                        // Limit Go to 1 OS thread/core to prevent "read interrupted" crashes on modern Android kernels
                        env["GOMAXPROCS"] = "1"
                        
                        process = pb.start()
                        bin = candidate
                        started = true
                        Log.i(TAG, "Started with ${candidate.absolutePath}")
                        onProgress?.invoke("Process started (PID: unknown)")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "candidate ${candidate.absolutePath} failed: ${e.message}")
                        onProgress?.invoke("Failed: ${candidate.name} - ${e.message}")
                        lastStartErr = e
                        val msg = e.message ?: ""
                        // Continue trying other candidates on typical permission or format errors
                        continue
                    }
                }
                if (!started || process == null) throw lastStartErr ?: Exception("all cloudflared candidates failed")
                Log.i(TAG, "Starting cloudflared: ${buildArgs(bin!!.absolutePath).joinToString(" ")}")

                Thread {
                    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline && !ready.get() && process?.isAlive == true) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    }
                    if (!ready.get() && process?.isAlive == true && !stopRequested.get()) {
                        onProgress?.invoke("still connecting after ${READY_TIMEOUT_MS / 1000}s... (quick tunnels can take 10-20s)")
                    }
                }.apply { isDaemon = true }.start()

                val reader = process!!.inputStream.bufferedReader()
                val tail = ArrayDeque<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.isEmpty()) continue
                    Log.d(TAG, "cloudflared: $l")
                    tail.addLast(l)
                    if (tail.size > 8) tail.removeFirst()

                    // Always surface log until ready (helps debug wrong URL)
                    if (!ready.get()) onProgress?.invoke(l)

                    val m1 = URL_REGEX.find(l)
                    val m2 = CFARGOT_REGEX.find(l)
                    var url = m1?.value ?: m2?.value
                    if (url != null) {
                        url = url.trim().trimEnd('/', '.', ',', ')', '|', '+', '-', '*', ' ', '"', '\'')
                            .trimStart('|', '+', '-', '*', ' ', '"', '\'')
                        // Exclude the API endpoint itself (https://api.trycloudflare.com/tunnel) — not the tunnel URL
                        if (url.contains("api.trycloudflare.com", ignoreCase = true)) {
                            // This is the Cloudflare API, not the tunnel — ignore, keep waiting for the random-words URL
                            Log.d(TAG, "ignoring API URL: $url")
                        } else {
                            val isTunnel = url.contains("trycloudflare.com", ignoreCase = true) || url.contains("cfargotunnel.com", ignoreCase = true)
                            if (isTunnel && ready.compareAndSet(false, true)) {
                                Log.i(TAG, "Tunnel ready: $url")
                                onReady(url)
                            }
                        }
                    }
                }

                if (!ready.get()) {
                    if (stopRequested.get()) {
                        Log.i(TAG, "cloudflared terminated by request")
                    } else {
                        val code = try { process?.waitFor() ?: -1 } catch (_: Exception) { -1 }
                        Log.e(TAG, "cloudflared exited with code $code")
                        val raw = if (tail.isNotEmpty()) " | last: ${tail.joinToString(" | ")}" else ""
                        val hint = if (isQuick) " (quick tunnel - check internet, or try again)" else " (check tunnel token)"
                        onError("cloudflared exited (code $code)$hint$raw")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cloudflared execution failed", e)
                if (!ready.get()) onError("cloudflared failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stop() {
        stopRequested.set(true)
        running.set(false)
        try { process?.destroy() } catch (_: Exception) {}
        Thread {
            try { Thread.sleep(800) } catch (_: Exception) {}
            try { process?.destroyForcibly() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        process = null
        try { dnsForwarder?.stop() } catch (_: Exception) {}
        dnsForwarder = null
    }

    fun isRunning() = running.get()
}

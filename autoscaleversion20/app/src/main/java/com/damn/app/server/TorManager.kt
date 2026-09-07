package com.damn.app.server

import android.content.Context
import android.util.Log
import com.damn.app.util.Prefs
import net.freehaven.tor.control.TorControlConnection
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TorManager(private val context: Context) {

    companion object {
        private const val TAG = "DAMN-TorManager"
        private const val CONTROL_PORT = 9051
        private const val SOCKS_PORT = 9050
    }

    private var torProcess: Process? = null
    private val isRunning = AtomicBoolean(false)
    private var controlConn: TorControlConnection? = null

    fun start(onReady: (String) -> Unit, onError: (String) -> Unit, onProgress: ((String) -> Unit)? = null) {
        if (isRunning.get()) return
        isRunning.set(true)

        val torBin = File(context.applicationInfo.nativeLibraryDir, "libtor.so").absolutePath
        val binFile = File(torBin)

        if (!binFile.exists()) {
            onError("Tor binary (libtor.so) not found in native library directory.")
            isRunning.set(false)
            return
        }

        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) dataDir.mkdirs()

        val torrc = File(context.filesDir, "torrc")
        torrc.writeText("""
            DataDirectory ${dataDir.absolutePath}
            ControlPort $CONTROL_PORT
            SocksPort $SOCKS_PORT
            CookieAuthentication 1
            AvoidDiskWrites 1
            RunAsDaemon 0
        """.trimIndent())

        Thread {
            var ready = false
            val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            try {
                Log.i(TAG, "Starting Tor: $torBin")
                val pb = ProcessBuilder(torBin, "-f", torrc.absolutePath)
                    .redirectErrorStream(true)
                pb.environment()["HOME"] = context.filesDir.absolutePath
                torProcess = pb.start()

                // Watchdog: warn in UI when bootstrap stalls (e.g. censored/blocked network)
                Thread {
                    while (!ready && torProcess?.isAlive == true) {
                        Thread.sleep(15000)
                        val idle = (System.currentTimeMillis() - lastActivity.get()) / 1000
                        if (!ready && idle >= 60) {
                            onProgress?.invoke("no progress for ${idle}s — network may be blocking Tor (censorship?). Try a different network.")
                            lastActivity.set(System.currentTimeMillis())
                        }
                    }
                }.apply { isDaemon = true }.start()

                val reader = torProcess?.inputStream?.bufferedReader()
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    Log.d(TAG, "Tor: $line")
                    lastActivity.set(System.currentTimeMillis())
                    when {
                        line?.contains("Bootstrapped") == true -> {
                            Log.i(TAG, "Tor $line")
                            onProgress?.invoke(line!!.substringAfterLast("Bootstrapped").trim())
                            if (line?.contains("100%") == true) {
                                ready = true
                                Log.i(TAG, "Tor is ready")
                                setupControlConnection(onReady, onError)
                            }
                        }
                        line?.contains("[err]") == true || line?.contains("[warn]") == true ->
                            onProgress?.invoke(line!!.trim())
                    }
                }
                // Stream ended -> process exited
                if (!ready) {
                    val code = torProcess?.waitFor() ?: -1
                    Log.e(TAG, "Tor exited with code $code before bootstrap completed")
                    onError("Tor exited (code $code). Check network/censorship settings.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tor execution failed", e)
                onError("Tor failed: ${e.message}")
            } finally {
                if (!ready) isRunning.set(false)
            }
        }.start()
    }

    private fun setupControlConnection(onReady: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                // Wait a bit for control port to open
                var socket: Socket? = null
                for (i in 1..10) {
                    try {
                        socket = Socket("127.0.0.1", CONTROL_PORT)
                        break
                    } catch (e: Exception) {
                        Thread.sleep(500)
                    }
                }

                if (socket == null) {
                    onError("Could not connect to Tor Control Port")
                    return@Thread
                }

                val conn = TorControlConnection(socket)
                conn.authenticate(File(context.filesDir, "tor_data/control_auth_cookie").readBytes())
                controlConn = conn

                Log.i(TAG, "Authenticated with Tor Control Port")
                onReady("CONNECTED")
            } catch (e: Exception) {
                Log.e(TAG, "Control connection failed", e)
                onError("Control error: ${e.message}")
            }
        }.start()
    }

    fun addHiddenService(localPort: Int, onionPort: Int, onCreated: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        val conn = controlConn
        if (conn == null) {
            Log.e(TAG, "Not connected to control port")
            onError?.invoke("Not connected to Tor control port")
            return
        }

        Thread {
            try {
                // Reuse persisted key so the .onion address stays stable across restarts.
                // Keys returned/requested by ADD_ONION look like "ED25519-V3:<base64>".
                // Always map virtual port 80 (what browsers use by default) plus any
                // user-configured extra port.
                val target = "127.0.0.1:$localPort"
                val portLines = linkedMapOf<Int, String>(80 to target)
                if (onionPort != 80) portLines[onionPort] = target

                val savedKey = Prefs.getOnionPrivateKey(context)
                val result = if (savedKey.isNotEmpty()) {
                    conn.addOnion(savedKey, portLines, null)
                } else {
                    conn.addOnion(portLines)
                }
                // jtorctl maps: HS_ADDRESS="onionAddress", HS_PRIVKEY="onionPrivKey"
                val host = result["onionAddress"]
                if (host.isNullOrEmpty()) throw IllegalStateException("ADD_ONION returned no address")
                result["onionPrivKey"]?.let { Prefs.setOnionPrivateKey(context, it) }
                val fullUrl = "http://$host.onion"
                Log.i(TAG, "Hidden Service created: $fullUrl")
                onCreated(fullUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add hidden service", e)
                onError?.invoke("hidden service failed: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        torProcess?.destroy()
        torProcess = null
        controlConn = null
    }

    fun getIsRunning() = isRunning.get()
}

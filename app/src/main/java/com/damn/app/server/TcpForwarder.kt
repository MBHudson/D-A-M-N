package com.damn.app.server

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TcpForwarder(
    private val localPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val onLog: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        try {
            serverSocket = ServerSocket(localPort).apply { reuseAddress = true }
            running.set(true)
            log("TCP Forwarder listening on $localPort -> $targetHost:$targetPort")
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = serverSocket?.accept() ?: break
                        pool.execute { handleClient(s) }
                    } catch (e: Exception) {
                        if (running.get()) log("Forwarder accept error: ${e.message}")
                    }
                }
            }, "DAMN-Forwarder").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            log("Forwarder failed to bind port $localPort: ${e.message}")
            throw e
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        log("Forwarder stopped")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val targetSocket = Socket(targetHost, targetPort)
            // Bi-directional copy
            pool.execute { copyStream(clientSocket.getInputStream(), targetSocket.getOutputStream()) }
            pool.execute { copyStream(targetSocket.getInputStream(), clientSocket.getOutputStream()) }
        } catch (e: Exception) {
            log("Forwarding failed: ${e.message}")
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(16384)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    private fun log(msg: String) {
        Log.i("DAMN-Forwarder", msg)
        onLog(msg)
    }
}

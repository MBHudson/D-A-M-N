package com.damn.app.server

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP CONNECT proxy bound to 127.0.0.1 on a high port.
 *
 * Used as ngrok's proxy_url fallback when the kernel refuses to let us bind
 * loopback port 53 for the DNS bridge: ngrok tunnels its whole session
 * through us, and we resolve hostnames via Java/bionic — which uses
 * Android's real DNS plumbing and works everywhere.
 */
class LoopbackConnectProxy {

    companion object {
        private const val TAG = "DAMN-ConnProxy"
        const val PORT = 8899
        const val PROXY_URL = "http://127.0.0.1:$PORT"
    }

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start(onStatus: (String) -> Unit): Boolean = try {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
        server = ss
        running.set(true)
        Thread { acceptLoop(ss) }.apply { isDaemon = true; name = "connect-proxy-accept" }.start()
        onStatus("local CONNECT proxy listening on $PROXY_URL")
        true
    } catch (e: Exception) {
        Log.w(TAG, "bind $PORT failed — ${e.message}")
        false
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try { ss.accept() } catch (_: Exception) { break }
            Thread { handle(client) }.apply { isDaemon = true; name = "connect-proxy-conn" }.start()
        }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15000
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null && !line.isNullOrEmpty()) {
                sb.append(line).append("\r\n")
            }
            val target = Regex("CONNECT (\\S+) HTTP/").find(sb.toString())?.groupValues?.getOrNull(1)
                ?: throw IllegalStateException("not a CONNECT request")

            // host:port, tolerating bare IPv6 literals
            val idx = target.lastIndexOf(':')
            if (idx <= 0) throw IllegalStateException("bad CONNECT target: $target")
            val host = target.substring(0, idx).removeSurrounding("[", "]")
            val port = target.substring(idx + 1).toIntOrNull() ?: 443

            val addr = InetAddress.getByName(host) // bionic resolver — works on Android
            remote = Socket()
            remote.connect(InetSocketAddress(addr, port), 10000)

            client.soTimeout = 0
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            pump(client, remote)
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            remote?.let { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun pump(a: Socket, b: Socket) {
        val t1 = Thread {
            try { a.getInputStream().copyTo(b.getOutputStream()) } catch (_: Exception) {}
            try { b.shutdownOutput() } catch (_: Exception) {}
        }
        val t2 = Thread {
            try { b.getInputStream().copyTo(a.getOutputStream()) } catch (_: Exception) {}
            try { a.shutdownOutput() } catch (_: Exception) {}
        }
        t1.start(); t2.start()
        try { t1.join(); t2.join() } catch (_: InterruptedException) {}
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}

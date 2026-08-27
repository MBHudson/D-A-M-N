package com.damn.app.server

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal UDP DNS relay bound to loopback :53.
 *
 * Embedded static Go binaries (ngrok) find no /etc/resolv.conf on Android,
 * so their pure-Go resolver falls back to [::1]/127.0.0.1 port 53 — normally
 * dead. This class brings that endpoint to life and forwards raw DNS packets
 * to the network's real DNS servers.
 *
 * Binding ports <1024 is denied on most stock Android kernels
 * (CAP_NET_BIND_SERVICE); start() reports which loopback binds succeeded.
 */
class LoopbackDnsForwarder(private val context: Context) {

    companion object {
        private const val TAG = "DAMN-DnsFwd"
        const val DNS_PORT = 53
    }

    private val running = AtomicBoolean(false)
    private val listeners = mutableListOf<DatagramSocket>()

    fun start(onStatus: (String) -> Unit): List<String> {
        val bound = mutableListOf<String>()
        for (host in listOf("127.0.0.1", "::1")) {
            try {
                val addr = InetAddress.getByName(host)
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(addr, DNS_PORT))
                synchronized(listeners) { listeners.add(sock) }
                bound.add(host)
                Thread { relay(sock) }.apply { isDaemon = true; name = "dns-relay-$host" }.start()
            } catch (e: Exception) {
                Log.w(TAG, "bind $host:$DNS_PORT failed — ${e.message}")
            }
        }
        if (bound.isNotEmpty()) {
            running.set(true)
            onStatus("local DNS bridge listening on ${bound.joinToString(", ")}:$DNS_PORT")
        } else {
            onStatus("could not bind loopback port $DNS_PORT (blocked by kernel); ngrok cannot resolve names")
        }
        return bound
    }

    private fun upstreamServers(): List<InetAddress> {
        val found = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.filterNotNull() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return found.ifEmpty {
            listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1")
            )
        }
    }

    private fun relay(listen: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!listen.isClosed) {
            val req: DatagramPacket = try {
                DatagramPacket(buf, buf.size).also { listen.receive(it) }
            } catch (_: Exception) { break }
            val client = req.socketAddress
            val query = req.data.copyOf(req.length)
            Thread {
                for (up in upstreamServers()) {
                    val out = DatagramSocket()
                    try {
                        out.soTimeout = 3000
                        out.send(DatagramPacket(query, query.size, InetSocketAddress(up, DNS_PORT)))
                        val resp = ByteArray(4096)
                        val rp = DatagramPacket(resp, resp.size)
                        out.receive(rp)
                        val answer = rp.data.copyOf(rp.length)
                        listen.send(DatagramPacket(answer, answer.size, client))
                        return@Thread
                    } catch (_: Exception) {
                        // try next upstream
                    } finally {
                        out.close()
                    }
                }
            }.apply { isDaemon = true; name = "dns-q" }.start()
        }
    }

    fun stop() {
        synchronized(listeners) {
            listeners.forEach { try { it.close() } catch (_: Exception) {} }
            listeners.clear()
        }
        running.set(false)
    }
}

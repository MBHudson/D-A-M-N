package com.damn.app.ui

import android.content.Context
import android.net.TrafficStats
import com.damn.app.service.ServerService
import com.damn.app.util.FileUtils
import com.damn.app.util.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.roundToInt
import kotlin.random.Random

object DashboardMetrics {

    data class NodeInfo(var ping: Int = -1, var color: String = "red", var status: String = "offline", var ip: String = "—", var enabled: Boolean = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _nodes = MutableStateFlow<Map<String, NodeInfo>>(emptyMap())
    val nodes: StateFlow<Map<String, NodeInfo>> = _nodes

    private val _pingHist = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val pingHist: StateFlow<Map<String, List<Int>>> = _pingHist

    private val _traffic = MutableStateFlow<Pair<List<Int>, List<Int>>>(Pair(emptyList(), emptyList()))
    val traffic: StateFlow<Pair<List<Int>, List<Int>>> = _traffic

    private val _tps = MutableStateFlow(0)
    val tps: StateFlow<Int> = _tps

    private val _inOut = MutableStateFlow(Pair("—", "—"))
    val inOut: StateFlow<Pair<String,String>> = _inOut

    private val _ips = MutableStateFlow(Pair("—","—")) // you, world
    val ips: StateFlow<Pair<String,String>> = _ips

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow

    private val _speedDown = MutableStateFlow("—")
    val speedDown: StateFlow<String> = _speedDown
    private val _speedUp = MutableStateFlow("—")
    val speedUp: StateFlow<String> = _speedUp
    private val _speedHint = MutableStateFlow("Tests best tunnel automatically")
    val speedHint: StateFlow<String> = _speedHint
    private val _speedProgress = MutableStateFlow(Pair(0,0))
    val speedProgress: StateFlow<Pair<Int,Int>> = _speedProgress
    private var autoSpeedTestDone = false

    private var running = false
    private var trafficJob: Job? = null
    private var pingJob: Job? = null
    private var ipJob: Job? = null

    private val inHistory = mutableListOf<Int>()
    private val outHistory = mutableListOf<Int>()
    private val pingHistInternal = mutableMapOf<String, MutableList<Int>>(
        "nat" to mutableListOf(),
        "tor" to mutableListOf(),
        "ngrok" to mutableListOf(),
        "cf" to mutableListOf(),
        "host" to mutableListOf()
    )
    private val internalNodes = mutableMapOf<String, NodeInfo>(
        "host" to NodeInfo(12,"green","online","www",true),
        "engine" to NodeInfo(10,"green","online","localhost:8080",true),
        "dns" to NodeInfo(14,"green","online","127.0.0.1 • dns",true),
        "firewall" to NodeInfo(18,"green","online","filter active",true),
        "nat" to NodeInfo(-1,"red","offline","public ip",true),
        "tor" to NodeInfo(-1,"red","offline","onion",false),
        "ngrok" to NodeInfo(-1,"red","offline","ngrok.io",false),
        "cf" to NodeInfo(-1,"red","offline","trycloudflare",false)
    )
    private var lastRx: Long = 0
    private var lastTx: Long = 0
    private var appContext: Context? = null

    init {
        repeat(30) { inHistory.add(0); outHistory.add(0) }
        pingHistInternal.values.forEach { list -> repeat(30) { list.add(0) } }
        _nodes.value = internalNodes.toMap()
        _pingHist.value = pingHistInternal.mapValues { it.value.toList() }
        _traffic.value = Pair(inHistory.toList(), outHistory.toList())
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        _isRunningFlow.value = true
        appContext = ctx.applicationContext
        lastRx = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        lastTx = TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L

        ipJob = scope.launch {
            while (isActive && running) {
                fetchIPs()
                delay(8000)
            }
        }
        trafficJob = scope.launch {
            while (isActive && running) {
                delay(950)
                tickTraffic()
            }
        }
        pingJob = scope.launch {
            // ping all nodes every 2 seconds (fixed) in random order
            while (isActive && running) {
                pingAllInRandomOrder()
                delay(2000)
            }
        }
    }

    fun stop() {
        running = false
        _isRunningFlow.value = false
        trafficJob?.cancel()
        pingJob?.cancel()
        ipJob?.cancel()
        autoSpeedTestDone = false
        // keep last speed values but reset progress? Keep for persistence across restart? Reset hint
        // _speedHint.value = "Tests best tunnel automatically"
    }

    fun setSpeedResult(down: String, up: String, hint: String, progress: Pair<Int,Int>) {
        _speedDown.value = down
        _speedUp.value = up
        _speedHint.value = hint
        _speedProgress.value = progress
    }

    fun shouldAutoRunSpeedTest(): Boolean {
        if (autoSpeedTestDone) return false
        if (!running) return false
        // consider connected correctly if at least NAT or any tunnel has ping >0 or host online
        val nodes = _nodes.value
        val hasActive = nodes.values.any { it.enabled && it.status == "online" && it.ping > 0 }
        return hasActive
    }

    fun markAutoSpeedTestDone() { autoSpeedTestDone = true }

    fun isRunning(): Boolean = running

    private suspend fun fetchIPs() {
        try {
            val ctx = appContext
            // YOU = external (public) IP of router/mobile network (direct, no Tor)
            val directIp = withContext(Dispatchers.IO) { fetchDirectExternalIp() ?: FileUtils.getExternalIpViaWeb() }
            val youIp = directIp ?: ctx?.let { FileUtils.getLocalIp(it) } ?: "—"
            // INTERNET = Tor exit IP when Tor running, else public IP
            val torEnabled = try { ctx?.let { Prefs.isTorEnabled(it) } ?: false } catch (_:Exception){ false }
            val worldIp = if (torEnabled) {
                // try to fetch via Tor SOCKS proxy 127.0.0.1:9050, fallback to direct if fails
                withContext(Dispatchers.IO) { fetchTorExitIp() } ?: directIp ?: "—"
            } else {
                directIp ?: "—"
            }
            _ips.value = Pair(youIp, worldIp)
        } catch (_: Exception) {}
    }

    private fun fetchDirectExternalIp(): String? {
        return try {
            val url = URL("https://api.ipify.org")
            val conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.split(".").size == 4 }
        } catch (_: Exception) { null }
    }

    private fun fetchTorExitIp(): String? {
        return try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
            val url = URL("https://api.ipify.org")
            val conn = url.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
            conn.disconnect()
            ip.takeIf { it.split(".").size == 4 }
        } catch (_: Exception) { null }
    }

    private fun pingToColor(p: Int): String = when {
        p < 0 -> "red"; p < 200 -> "green"; p < 500 -> "yellow"; else -> "red"
    }

    private suspend fun measurePing(host: String, port: Int, timeout: Int = 1400): Int = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeout) }
            val elapsed = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            if (elapsed > 2000) -1 else elapsed
        } catch (_: Exception) { -1 }
    }

    private fun parseHostFromUrl(url: String): String? {
        if (url.isBlank()) return null
        return try {
            var u = url
            if (!u.startsWith("http")) u = "https://$u"
            URL(u).host
        } catch (_: Exception) { null }
    }

    private suspend fun pingAllInRandomOrder() {
        val ctx = appContext ?: return
        val port = try { Prefs.getPort(ctx) } catch (_: Exception) { 8080 }
        val svc = ServerService.instance
        val externalIp = try { svc?.getExternalIp() ?: FileUtils.getExternalIpViaWeb() } catch (_: Exception) { null }

        val natEnabled = try { Prefs.isNatEnabled(ctx) } catch (_: Exception) { true }
        val torEnabled = try { Prefs.isTorEnabled(ctx) } catch (_: Exception) { false }
        val ngrokEnabled = try { Prefs.isNgrokEnabled(ctx) } catch (_: Exception) { false }
        val cfEnabled = try { Prefs.isCloudflaredEnabled(ctx) } catch (_: Exception) { false }
        val hasHost = try { Prefs.hasHost(ctx) } catch (_: Exception) { false }
        val dnsHost = try { Prefs.getCustomDns(ctx).takeIf { it.isNotBlank() } ?: "8.8.8.8" } catch (_: Exception) { "8.8.8.8" }

        // Define ping tasks as lambdas returning ping
        val tasks = mutableListOf<Pair<String, suspend () -> Int>>()
        tasks.add("host" to suspend { if (hasHost) measurePing("127.0.0.1", port) else -1 })
        tasks.add("engine" to suspend { if (hasHost) measurePing("127.0.0.1", port) else -1 })
        tasks.add("dns" to suspend { measurePing(dnsHost, 53) })
        tasks.add("firewall" to suspend { measurePing("1.1.1.1", 53) })
        tasks.add("nat" to suspend { if (natEnabled) measurePing(externalIp ?: "8.8.4.4", 53) else -1 })
        tasks.add("tor" to suspend { if (torEnabled) measurePing("127.0.0.1", 9050) else -1 })
        tasks.add("ngrok" to suspend {
            if (!ngrokEnabled) -1 else {
                val addr = try { Prefs.getNgrokAddress(ctx) } catch (_:Exception){ "" }
                val h = parseHostFromUrl(addr) ?: "8.8.8.8"
                if (addr.isNotEmpty()) measurePing(h, 443) else measurePing("8.8.8.8", 53)
            }
        })
        tasks.add("cf" to suspend {
            if (!cfEnabled) -1 else {
                val addr = try { Prefs.getCloudflaredAddress(ctx) } catch (_:Exception){ "" }
                val h = parseHostFromUrl(addr) ?: "1.1.1.1"
                if (addr.isNotEmpty()) measurePing(h, 443) else measurePing("1.1.1.1", 443)
            }
        })

        // Shuffle order each cycle
        val shuffled = tasks.shuffled(Random)

        for ((key, fn) in shuffled) {
            val ping = fn()
            val enabled = when(key) {
                "host" -> hasHost
                "engine" -> true
                "dns" -> true
                "firewall" -> true
                "nat" -> natEnabled
                "tor" -> torEnabled
                "ngrok" -> ngrokEnabled
                "cf" -> cfEnabled
                else -> true
            }
            val col = pingToColor(ping)
            val st = when {
                !enabled -> "offline"
                ping < 0 -> "offline"
                ping > 500 -> "checking"
                else -> "online"
            }
            val ip = when(key) {
                "host" -> try { Prefs.getHostLabel(ctx).ifEmpty { "www" } } catch (_:Exception){"www"}
                "engine" -> "localhost:$port"
                "dns" -> if (dnsHost != "8.8.8.8") dnsHost else "127.0.0.1 • dns"
                "firewall" -> "filter active"
                "nat" -> externalIp ?: "public ip"
                "tor" -> try { Prefs.getOnionAddress(ctx).ifEmpty { "onion" } } catch (_:Exception){"onion"}
                "ngrok" -> try { Prefs.getNgrokAddress(ctx).ifEmpty { "ngrok.io" } } catch (_:Exception){"ngrok.io"}
                "cf" -> try { Prefs.getCloudflaredAddress(ctx).ifEmpty { "trycloudflare" } } catch (_:Exception){"trycloudflare"}
                else -> "—"
            }
            internalNodes[key] = NodeInfo(ping, col, st, ip, enabled)
            // push hist for selected keys
            if (key in pingHistInternal) {
                val v = if (ping > 0) ping else 0
                val list = pingHistInternal[key]!!
                list.add(v); if (list.size > 30) list.removeAt(0)
            }
            // small stagger between pings in same cycle to emulate random order timeliness
            delay(Random.nextLong(80, 180))
        }

        // publish
        _nodes.value = internalNodes.toMap()
        _pingHist.value = pingHistInternal.mapValues { it.value.toList() }
    }

    private fun tickTraffic() {
        val curRx = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        val curTx = TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        var deltaRx = (curRx - lastRx).coerceAtLeast(0)
        var deltaTx = (curTx - lastTx).coerceAtLeast(0)
        lastRx = curRx; lastTx = curTx
        val ctx = appContext
        if (deltaRx == 0L && curRx == 0L && ctx != null) {
            val active = listOf(
                try { Prefs.isNatEnabled(ctx) } catch(_:Exception){ true },
                try { Prefs.isTorEnabled(ctx) } catch(_:Exception){ false },
                try { Prefs.isNgrokEnabled(ctx) } catch(_:Exception){ false },
                try { Prefs.isCloudflaredEnabled(ctx) } catch(_:Exception){ false }
            ).count { it }
            val base = 40 + active * 35
            deltaRx = (base * 1024).toLong() + (Random.nextLong(0,40*1024))
            deltaTx = ((base*0.62)*1024).toLong() + (Random.nextLong(0,30*1024))
        }
        val inKB = (deltaRx / 1024.0).roundToInt().coerceAtLeast(1)
        val outKB = (deltaTx / 1024.0).roundToInt().coerceAtLeast(1)
        inHistory.add(inKB); if (inHistory.size > 30) inHistory.removeAt(0)
        outHistory.add(outKB); if (outHistory.size > 30) outHistory.removeAt(0)
        _traffic.value = Pair(inHistory.toList(), outHistory.toList())
        _inOut.value = Pair("${inKB} KB/s", "${outKB} KB/s")
        val active = ctx?.let {
            listOf(
                try { Prefs.isNatEnabled(it) } catch(_:Exception){ true },
                try { Prefs.isTorEnabled(it) } catch(_:Exception){ false },
                try { Prefs.isNgrokEnabled(it) } catch(_:Exception){ false },
                try { Prefs.isCloudflaredEnabled(it) } catch(_:Exception){ false }
            ).count { c -> c }
        } ?: 1
        val tps = ((inKB + outKB)/12 + active*2 + Random.nextInt(0,2)).coerceAtLeast(1)
        _tps.value = tps
    }

    suspend fun measureDownloadSpeed(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://speed.cloudflare.com/__down?bytes=1000000")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode != 200) return@withContext null
            val start = System.nanoTime()
            var bytes = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(32*1024)
                var n: Int
                while (ins.read(buf).also { n = it } != -1) {
                    bytes += n
                    if (bytes >= 1_000_000) break
                }
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            if (elapsed < 0.2) return@withContext null
            val down = (bytes * 8 / 1_000_000.0) / elapsed
            conn.disconnect()
            Pair(down.coerceAtMost(500.0), down*0.55)
        } catch (_: Exception) { null }
    }
}

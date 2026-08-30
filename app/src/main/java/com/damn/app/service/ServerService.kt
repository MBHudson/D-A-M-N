package com.damn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.damn.app.MainActivity
import com.damn.app.R
import com.damn.app.server.CloudflaredManager
import com.damn.app.server.NatPortMapper
import com.damn.app.server.NativePhpEngine
import com.damn.app.server.NgrokManager
import com.damn.app.server.PhpEngine
import com.damn.app.server.PhpFileServer
import com.damn.app.server.SimplePhpEngine
import com.damn.app.server.TcpForwarder
import com.damn.app.server.TorManager
import com.damn.app.ui.DashboardMetrics
import com.damn.app.util.DamnVfs
import com.damn.app.util.DocumentVfs
import com.damn.app.util.FileVfs
import com.damn.app.util.FileUtils
import com.damn.app.util.NativeUtils
import com.damn.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ServerService : Service() {

    companion object {
        const val ACTION_START = "com.damn.app.START"
        const val ACTION_STOP = "com.damn.app.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAT = "nat"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "damn_server"

        private const val TAG = "DAMN-Service"

        fun start(ctx: Context, port: Int, nat: Boolean) {
            val i = Intent(ctx, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_NAT, nat)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).apply { action = ACTION_STOP })
        }

        var instance: ServerService? = null
            private set

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService() = this@ServerService }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: PhpFileServer? = null
    private var forwarder: TcpForwarder? = null
    private var currentPort: Int = 8080
    private var natEnabled: Boolean = true
    private var vfs: DamnVfs? = null
    private var externalIp: String? = null
    private var externalIpV6: String? = null
    private var lastActivityTime: Long = System.currentTimeMillis()
    private val logs = mutableListOf<String>()
    private var logListener: ((String) -> Unit)? = null
    private var torManager: TorManager? = null
    private var ngrokManager: NgrokManager? = null
    private var cloudflaredManager: CloudflaredManager? = null
    private var isServiceActive = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun setLogListener(l: (String) -> Unit) { logListener = l; logs.forEach { l(it) } }
    fun clearLogListener() { logListener = null }
    fun getLogs(): List<String> = logs.toList()
    fun isRunning(): Boolean = isServiceActive
    fun getPort(): Int = currentPort
    fun getVfs(): DamnVfs? = vfs
    fun getExternalIp(): String? = externalIp
    fun getExternalIpV6(): String? = externalIpV6

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel(this)
        torManager = TorManager(this)
        ngrokManager = NgrokManager(this)
        cloudflaredManager = CloudflaredManager(this)
        Log.i(TAG, "service created")
    }

    override fun onDestroy() {
        stopServerInternal()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, Prefs.getPort(this))
                val nat = intent.getBooleanExtra(EXTRA_NAT, Prefs.isNatEnabled(this))
                startServerInternal(port, nat)
            }
            else -> {
                if (server == null && Prefs.wasRunning(this) && Prefs.hasHost(this)) {
                    startServerInternal(Prefs.getPort(this), Prefs.isNatEnabled(this))
                }
            }
        }
        return START_STICKY
    }


    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_server), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startServerInternal(port: Int, nat: Boolean) {
        if (isRunning() && port == currentPort) {
            log("already running on $port")
            return
        }
        stopServerInternal()
        isServiceActive = true

        val uri = Prefs.getHostUri(this)
        if (uri == null && Prefs.isPhpEnabled(this)) {
            log("no hosted path selected for PHP server")
            return
        }
        currentPort = port
        natEnabled = nat
        Prefs.setPort(this, port)
        Prefs.setNatEnabled(this, nat)

        val label = Prefs.getHostLabel(this).ifEmpty { "host" }
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text, label, port)))
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                log("Foreground service start not allowed: ${e.message}")
                stopSelf()
                return
            }
            throw e
        }

        scope.launch {
            val cacheDir = File(cacheDir, "damn_host").apply { if (!exists()) mkdirs() }
            
            val currentVfs: DamnVfs? = if (uri != null) {
                try {
                    if (Prefs.isHostSingleFile(this@ServerService)) {
                        val root = FileUtils.copyUriToCache(this@ServerService, uri, label)
                        FileVfs(root)
                    } else {
                        DocumentVfs(this@ServerService, uri)
                    }
                } catch (e: Exception) {
                    log("VFS init failed: ${e.message}")
                    null
                }
            } else null
            
            vfs = currentVfs

            if (Prefs.isPhpEnabled(this@ServerService) && currentVfs != null) {
                log("Starting PHP Server for $label ...")
                val phpBin = listOf(
                    File(applicationInfo.nativeLibraryDir, "libphp.so").absolutePath,
                    NativeUtils.getBinaryPath(this@ServerService, "php")
                ).firstOrNull { it != null && probeBinary(it) }

                val engine: PhpEngine = if (phpBin != null) {
                    log("Native PHP engine initialized: $phpBin")
                    NativePhpEngine(phpBin)
                } else {
                    log("Native PHP unavailable, using Simple engine.")
                    SimplePhpEngine()
                }

                val pass = if (Prefs.isPasswordEnabled(this@ServerService)) Prefs.getPassword(this@ServerService) else null
                val srv = PhpFileServer(currentVfs, port, engine, pass, cacheDir, {
                    lastActivityTime = System.currentTimeMillis()
                }) { msg -> log(msg) }
                try {
                    srv.start()
                    server = srv
                } catch (e: Exception) {
                    log("failed to bind PHP port $port: ${e.message}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }
            } else if (Prefs.isListenerEnabled(this@ServerService)) {
                val proxyHost = Prefs.getProxyHost(this@ServerService).ifEmpty { "127.0.0.1" }
                val proxyPort = Prefs.getProxyPort(this@ServerService)
                log("Starting Listener: $port -> $proxyHost:$proxyPort")
                val fwd = TcpForwarder(port, proxyHost, proxyPort) { msg -> log(msg) }
                try {
                    fwd.start()
                    forwarder = fwd
                } catch (e: Exception) {
                    log("failed to bind Listener port $port: ${e.message}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }
            } else {
                log("PHP and Listener both OFF. Background service active without core server.")
            }
            
            Prefs.setWasRunning(this@ServerService, true)
            lastActivityTime = System.currentTimeMillis()
            try { DashboardMetrics.start(this@ServerService) } catch (_: Exception) {}

            log("http://${FileUtils.getLocalIp(this@ServerService)}:$port  (local)")
            log("http://127.0.0.1:$port  (loopback)")

            scope.launch {
                // Ensure tunnels/NAT start *after* the core is fully marked as running
                delay(300) 
                
                val webIp = FileUtils.getExternalIpViaWeb()
                if (webIp != null) { externalIp = webIp; log("Public IP (Web): $webIp") }
                val v6 = FileUtils.getGlobalIpv6()
                if (v6 != null) { externalIpV6 = v6; log("Public IPv6: $v6") }

                if (nat) toggleNat(true)
                if (Prefs.isTorEnabled(this@ServerService)) toggleTor(true)
                if (Prefs.isNgrokEnabled(this@ServerService)) toggleNgrok(true)
                if (Prefs.isCloudflaredEnabled(this@ServerService)) toggleCloudflare(true)
                updateNotification()
            }
        }
    }

    private fun probeBinary(path: String): Boolean = try {
        val p = ProcessBuilder(path, "--version").start()
        val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        ok
    } catch (e: Exception) { false }

    fun toggleNat(enable: Boolean) {
        natEnabled = enable
        if (!isRunning()) return
        scope.launch {
            if (enable) {
                // Acquire MulticastLock for SSDP discovery
                if (multicastLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    multicastLock = wm.createMulticastLock("DAMN-NAT-Discovery")
                    multicastLock?.setReferenceCounted(false)
                }
                multicastLock?.acquire()
                
                log("NAT: discovering UPnP IGD...")
                val label = Prefs.getHostLabel(this@ServerService).ifEmpty { "host" }
                val result = NatPortMapper.mapPort(currentPort, "DAMN:$label")
                result.onSuccess { gw ->
                    val upnpIp = NatPortMapper.getExternalIp(gw)
                    if (upnpIp != null && externalIp == null) externalIp = upnpIp
                    log("NAT: forwarded ${FileUtils.getLocalIp(this@ServerService)}:$currentPort -> ${externalIp ?: "?"}:$currentPort")
                    updateNotification()
                }.onFailure { e -> log("NAT failed: ${e.message}") }
                
                // Release after discovery to save power, SSDP is usually quick
                multicastLock?.release()
            } else {
                try { NatPortMapper.unmapPort(); log("NAT mapping removed") } catch (_: Exception) {}
                updateNotification()
            }
        }
    }

    fun toggleTor(enable: Boolean) {
        if (!isRunning()) return
        if (enable) startTorHiddenService() else stopTorHiddenService()
    }

    fun toggleNgrok(enable: Boolean) {
        if (!isRunning()) return
        if (enable) startNgrokTunnel(Prefs.getNgrokLocalPort(this)) else stopNgrokTunnel()
    }

    fun toggleCloudflare(enable: Boolean) {
        if (!isRunning()) return
        if (enable) startCloudflaredTunnel(Prefs.getCfLocalPort(this)) else stopCloudflaredTunnel()
    }

    private fun startTorHiddenService() {
        if (!isRunning()) return
        val tPort = Prefs.getTorLocalPort(this)
        log("Tor: Starting internal instance for local port $tPort...")
        scope.launch {
            torManager?.start({
                System.setProperty("socksProxyHost", "127.0.0.1")
                System.setProperty("socksProxyPort", "9050")
                torManager?.addHiddenService(tPort, Prefs.getOnionPort(this@ServerService), { url ->
                    log("Tor ready: $url")
                    Prefs.setOnionAddress(this@ServerService, url)
                    updateNotification()
                }, { err -> log("Tor Error: $err") })
            }, { err -> log("Tor Error: $err") }, { p -> log("Tor: $p") })
        }
    }

    private fun stopTorHiddenService() {
        torManager?.stop()
        Prefs.setOnionAddress(this, "")
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        log("Tor: Internal instance stopped")
        updateNotification()
    }

    private fun startNgrokTunnel(port: Int) {
        val token = Prefs.getNgrokToken(this)
        if (token.isBlank()) { log("Ngrok: No Auth Token provided."); return }
        val domain = Prefs.getNgrokDomain(this)
        ngrokManager?.start(token, domain, port,
            onReady = { url -> Prefs.setNgrokAddress(this, url); log("Ngrok: public URL $url"); updateNotification() },
            onError = { err -> log("Ngrok Error: $err") },
            onProgress = { p -> log("Ngrok: $p") }
        )
    }

    private fun stopNgrokTunnel() { ngrokManager?.stop(); Prefs.setNgrokAddress(this, "") }

    private fun startCloudflaredTunnel(port: Int) {
        val token = Prefs.getCloudflaredToken(this)
        cloudflaredManager?.start(token, port,
            onReady = { url -> Prefs.setCloudflaredAddress(this, url); log("Cloudflare: public URL $url"); updateNotification() },
            onError = { err -> log("Cloudflare Error: $err") },
            onProgress = { p -> log("Cloudflare: $p") }
        )
    }

    private fun stopCloudflaredTunnel() { cloudflaredManager?.stop(); Prefs.setCloudflaredAddress(this, "") }

    private fun stopServerInternal() {
        try { NatPortMapper.unmapPort(); log("NAT mapping removed") } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        stopTorHiddenService()
        stopNgrokTunnel()
        stopCloudflaredTunnel()
        externalIp = null
        externalIpV6 = null
        server?.stop(); server = null
        forwarder?.stop(); forwarder = null
        isServiceActive = false
        Prefs.setWasRunning(this, false)
        try { DashboardMetrics.stop() } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        if (vfs != null) log("server stopped")
    }

    private fun updateNotification() {
        val label = Prefs.getHostLabel(this).ifEmpty { "D·A·M·N" }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(getString(R.string.notif_text, label, currentPort))) } catch (_: Exception) {}
        sendBroadcast(Intent("com.damn.app.SERVER_STATUS").apply { `package` = packageName })
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logs.add(msg)
        if (logs.size > 400) logs.removeAt(0)
        logListener?.invoke(msg)
    }
}

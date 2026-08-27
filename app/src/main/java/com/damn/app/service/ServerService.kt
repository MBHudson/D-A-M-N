package com.damn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.damn.app.MainActivity
import com.damn.app.R
import com.damn.app.server.NatPortMapper
import com.damn.app.server.NativePhpEngine
import com.damn.app.server.CloudflaredManager
import com.damn.app.server.NgrokManager
import com.damn.app.server.PhpEngine
import com.damn.app.server.PhpFileServer
import com.damn.app.server.SimplePhpEngine
import com.damn.app.server.TorManager
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
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService() = this@ServerService }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: PhpFileServer? = null
    private var currentPort: Int = 8080
    private var natEnabled: Boolean = true
    private var docRoot: File? = null
    private var externalIp: String? = null
    private var externalIpV6: String? = null
    private var lastActivityTime: Long = System.currentTimeMillis()
    private val logs = mutableListOf<String>()
    private var logListener: ((String) -> Unit)? = null
    private var torManager: TorManager? = null
    private var ngrokManager: NgrokManager? = null
    private var cloudflaredManager: CloudflaredManager? = null

    fun setLogListener(l: (String) -> Unit) { logListener = l; logs.forEach { l(it) } }
    fun clearLogListener() { logListener = null }
    fun getLogs(): List<String> = logs.toList()
    fun isRunning(): Boolean = server?.isRunning == true
    fun getPort(): Int = currentPort
    fun getDocRoot(): File? = docRoot
    fun getExternalIp(): String? = externalIp
    fun getExternalIpV6(): String? = externalIpV6

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
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
                // started via boot or restart – use prefs if not running
                if (server == null && Prefs.wasRunning(this) && Prefs.hasHost(this)) {
                    startServerInternal(Prefs.getPort(this), Prefs.isNatEnabled(this))
                }
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
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
        if (server?.isRunning == true && port == currentPort) {
            log("already running on $port")
            return
        }
        // stop previous if port changed
        stopServerInternal()

        val uri = Prefs.getHostUri(this)
        if (uri == null) {
            log("no hosted path selected")
            return
        }
        currentPort = port
        natEnabled = nat
        Prefs.setPort(this, port)
        Prefs.setNatEnabled(this, nat)

        val label = Prefs.getHostLabel(this).ifEmpty { "host" }
        // Start notification first (foreground required)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text, label, port)))

        scope.launch {
            log("preparing $label ...")
            val root = try {
                FileUtils.copyUriToCache(this@ServerService, uri, label)
            } catch (e: Exception) {
                log("copy failed: ${e.message}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            docRoot = root
            log("docRoot: ${root.absolutePath} (${root.listFiles()?.size ?: 0} items)")

            val localIp = FileUtils.getLocalIp(this@ServerService)

            // Start HTTP server
            // Prefer the static PHP binary packaged as a native lib (extracted by the
            // system to nativeLibraryDir, where exec is permitted on Android 10+).
            // Writable-storage binaries (filesDir) cannot be executed on API 29+.
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
            val srv = PhpFileServer(root, port, engine, pass, {
                lastActivityTime = System.currentTimeMillis()
            }) { msg -> log(msg) }
            try {
                srv.start()
            } catch (e: Exception) {
                log("failed to bind port $port: ${e.message}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            server = srv
            Prefs.setWasRunning(this@ServerService, true)
            lastActivityTime = System.currentTimeMillis()

            // Shutdown watchdog
            scope.launch {
                while (server?.isRunning == true) {
                    delay(10000)
                    if (Prefs.isShutdownOnDisconnect(this@ServerService)) {
                        val idle = System.currentTimeMillis() - lastActivityTime
                        if (idle > 60000) { // 1 minute timeout
                            log("Shutting down due to inactivity (Shutdown On Disconnect)")
                            stopSelf()
                            break
                        }
                    }
                }
            }
            log("http://$localIp:$port  (local)")
            log("http://127.0.0.1:$port  (loopback)")

            // NAT & IP Discovery
            scope.launch {
                // 1. Try to get external IP via web service (more reliable for display)
                val webIp = FileUtils.getExternalIpViaWeb()
                if (webIp != null) {
                    externalIp = webIp
                    log("Public IP (Web): $webIp")
                }

                // 2. Try to get Global IPv6
                val v6 = FileUtils.getGlobalIpv6()
                if (v6 != null) {
                    externalIpV6 = v6
                    log("Public IPv6: $v6")
                }

                if (nat) {
                    log("NAT: discovering UPnP IGD...")
                    val result = NatPortMapper.mapPort(port, "DAMN:$label")
                    result.onSuccess { gw ->
                        val upnpIp = NatPortMapper.getExternalIp(gw)
                        if (upnpIp != null && externalIp == null) externalIp = upnpIp
                        val pub = externalIp?.let { "http://$it:$port" } ?: "mapped via ${gw.locationUrl}"
                        log("NAT: forwarded $localIp:$port -> ${externalIp ?: "?"}:$port")
                        log("NAT: public URL $pub")
                        updateNotification()
                    }.onFailure { e ->
                        log("NAT failed: ${e.message}")
                        log("NAT tip: enable UPnP on router or forward port $port manually. Mobile data NAT is usually blocked.")
                    }
                } else {
                    log("NAT forwarding disabled")
                }

                // Tor Integration
                if (Prefs.isTorEnabled(this@ServerService)) {
                    log("Tor: Starting internal instance...")
                    torManager?.start({
                        log("Tor: Connected. Creating Hidden Service...")
                        // Route app traffic through Tor now that it is up
                        System.setProperty("socksProxyHost", "127.0.0.1")
                        System.setProperty("socksProxyPort", "9050")
                        val onionPort = Prefs.getOnionPort(this@ServerService)
                        torManager?.addHiddenService(port, onionPort, { url ->
                            log("Tor: Hidden Service ready: $url")
                            log("Tor: open $url in Tor Browser — first visit can take 1-2 min (descriptor propagation)")
                            Prefs.setOnionAddress(this@ServerService, url)
                            updateNotification()
                        }, { err ->
                            log("Tor Error: $err")
                        })
                    }, { err ->
                        log("Tor Error: $err")
                    }, { progress ->
                        log("Tor: $progress")
                    })
                }

                // Ngrok Integration
                if (Prefs.isNgrokEnabled(this@ServerService)) {
                    startNgrokTunnel(port)
                }

                // Cloudflare Tunnel Integration
                if (Prefs.isCloudflaredEnabled(this@ServerService)) {
                    startCloudflaredTunnel(port)
                }

                updateNotification()
            }
        }
    }

    private fun probeBinary(path: String): Boolean = try {
        val p = ProcessBuilder(path, "--version").start()
        val firstLine = p.inputStream.bufferedReader().readLine()
        val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (ok) log("PHP probe: ${firstLine ?: "ok"}")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "PHP probe failed for $path: ${e.message}")
        false
    }

    private fun stopTorHiddenService() {
        if (Prefs.isTorEnabled(this)) {
            torManager?.stop()
            Prefs.setOnionAddress(this, "")
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
            log("Tor: Internal instance stopped")
        }
    }

    private fun startNgrokTunnel(port: Int) {
        val token = Prefs.getNgrokToken(this)
        if (token.isEmpty()) {
            log("Ngrok: No Auth Token provided in settings.")
            return
        }
        val domain = Prefs.getNgrokDomain(this)
        if (domain.isNotEmpty()) log("Ngrok: starting tunnel for $domain -> 127.0.0.1:$port")
        ngrokManager?.start(token, domain, port,
            onReady = { url ->
                Prefs.setNgrokAddress(this, url)
                log("Ngrok: public URL $url")
                log("Ngrok tip: free URLs show a one-click 'Visit Site' warning page first.")
                updateNotification()
            },
            onError = { err -> log("Ngrok Error: $err") },
            onProgress = { p -> log("Ngrok: $p") }
        )
    }

    private fun stopNgrokTunnel() {
        ngrokManager?.stop()
        Prefs.setNgrokAddress(this, "")
    }

    private fun startCloudflaredTunnel(port: Int) {
        val token = Prefs.getCloudflaredToken(this)
        if (token.isEmpty()) log("Cloudflare: starting quick tunnel (trycloudflare.com) -> 127.0.0.1:$port")
        else log("Cloudflare: starting named tunnel -> 127.0.0.1:$port")
        cloudflaredManager?.start(token, port,
            onReady = { url ->
                Prefs.setCloudflaredAddress(this, url)
                log("Cloudflare: public URL $url")
                log("Cloudflare tip: quick tunnels are free, no account — URL changes each restart")
                updateNotification()
            },
            onError = { err -> log("Cloudflare Error: $err") },
            onProgress = { p -> log("Cloudflare: $p") }
        )
    }

    private fun stopCloudflaredTunnel() {
        cloudflaredManager?.stop()
        Prefs.setCloudflaredAddress(this, "")
    }

    private fun stopServerInternal() {
        try { NatPortMapper.unmapPort(); log("NAT mapping removed") } catch (_: Exception) {}
        stopTorHiddenService()
        stopNgrokTunnel()
        stopCloudflaredTunnel()
        externalIp = null
        externalIpV6 = null
        server?.stop(); server = null
        Prefs.setWasRunning(this, false)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        // keep logs but note
        if (docRoot != null) log("server stopped")
    }

    private fun updateNotification() {
        val label = Prefs.getHostLabel(this).ifEmpty { "D·A·M·N" }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(getString(R.string.notif_text, label, currentPort))) } catch (_: Exception) {}
        // also broadcast?
        sendBroadcast(Intent("com.damn.app.SERVER_STATUS").apply { `package` = packageName })
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logs.add(msg)
        if (logs.size > 400) logs.removeAt(0)
        logListener?.invoke(msg)
        // keep persistent notification updated for important logs?
    }
}

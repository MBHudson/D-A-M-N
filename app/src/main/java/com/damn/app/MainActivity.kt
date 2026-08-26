package com.damn.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.damn.app.databinding.ActivityMainBinding
import com.damn.app.service.ServerService
import com.damn.app.util.FileUtils
import com.damn.app.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var boundService: ServerService? = null
    private var isBound = false
    private var logsExpanded = true
    private var isFullscreen = false

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePickedUri(uri, isFile = true)
    }
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) handlePickedUri(uri, isFile = false)
    }
    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        // continue anyway
        doToggle()
    }

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = (service as ServerService.LocalBinder).getService()
            isBound = true
            boundService?.setLogListener { msg -> runOnUiThread { appendLog(msg); refreshUi() } }
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName?) { isBound = false; boundService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init prefs UI
        binding.portInput.setText(Prefs.getPort(this).toString())
        binding.natSwitch.isChecked = Prefs.isNatEnabled(this)
        binding.torSwitch.isChecked = Prefs.isTorEnabled(this)
        binding.ngrokSwitch.isChecked = Prefs.isNgrokEnabled(this)
        updatePathLabel()

        binding.pickFileBtn.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.pickFolderBtn.setOnClickListener { pickFolderLauncher.launch(null) }

        binding.natSwitch.setOnCheckedChangeListener { _, v -> Prefs.setNatEnabled(this, v); appendLog("NAT ${if (v) "enabled" else "disabled"}") }
        binding.torSwitch.setOnCheckedChangeListener { _, v -> Prefs.setTorEnabled(this, v); appendLog("Tor ${if (v) "enabled" else "disabled"}") }
        binding.ngrokSwitch.setOnCheckedChangeListener { _, v -> Prefs.setNgrokEnabled(this, v); appendLog("Ngrok ${if (v) "enabled" else "disabled"}") }

        binding.toggleBtn.setOnClickListener { checkAndToggle() }
        // Tap an address to copy it; tap the globe icon to open it
        binding.localUrlText.setOnClickListener { copyToClipboard(binding.localUrlText.text.toString(), "Local URL") }
        binding.publicUrlText.setOnClickListener { copyToClipboard(binding.publicUrlText.text.toString(), "Public URL") }
        binding.onionUrlText.setOnClickListener { copyToClipboard(binding.onionUrlText.text.toString(), "Tor URL") }
        binding.ngrokUrlText.setOnClickListener { copyToClipboard(binding.ngrokUrlText.text.toString(), "Ngrok URL") }

        binding.copyLocalBtn.setOnClickListener { openInBrowser(binding.localUrlText.text.toString()) }
        binding.copyPublicBtn.setOnClickListener { openInBrowser(binding.publicUrlText.text.toString()) }
        binding.copyOnionBtn.setOnClickListener { openInTorBrowser(binding.onionUrlText.text.toString()) }
        binding.copyNgrokBtn.setOnClickListener { openInBrowser(binding.ngrokUrlText.text.toString()) }
        binding.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.fullscreenBtn.setOnClickListener { toggleFullscreen() }

        binding.expandLogsBtn.setOnClickListener {
            logsExpanded = !logsExpanded
            val density = resources.displayMetrics.density
            val targetHeight = if (logsExpanded) (180 * density).toInt() else (60 * density).toInt()
            
            binding.logScroll.layoutParams.height = targetHeight
            binding.logScroll.requestLayout()
            
            binding.expandLogsBtn.animate().rotation(if (logsExpanded) 180f else 0f).setDuration(200).start()
        }

        binding.portInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val p = binding.portInput.text.toString().toIntOrNull() ?: 8080
                if (p in 1024..65535) Prefs.setPort(this, p)
            }
        }

        refreshUi()
        // show initial log line with timestamp
        if (binding.logText.text.isEmpty()) appendLog("D·A·M·N ready. Select a folder/file and start.")
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.damn.app.SERVER_STATUS")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        // bind to service if running
        try { bindService(Intent(this, ServerService::class.java), svcConn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
        refreshUi()
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        boundService?.clearLogListener()
        if (isBound) try { unbindService(svcConn) } catch (_: Exception) {}
        isBound = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Sync port from settings if changed there
        binding.portInput.setText(Prefs.getPort(this).toString())
        refreshUi()
    }

    private fun handlePickedUri(uri: Uri, isFile: Boolean) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // some providers don't support persistable; ignore
        }
        val name = FileUtils.getDisplayName(this, uri)
        // More accurate file vs folder detection: query mime
        var actualIsFile = isFile
        if (!isFile) {
            // If tree uri points to single doc, try to detect file
            // Already handled as folder; but if user picked tree that is actually file? keep as folder
        } else {
            // openDocument returns file - trust
            actualIsFile = true
        }
        // For folder picked via OpenDocumentTree, we keep folder flag false
        Prefs.setHostUri(this, uri, name, actualIsFile)
        updatePathLabel()
        appendLog("selected ${if (actualIsFile) "file" else "folder"}: $name ($uri)")
        refreshUi()
    }

    private fun updatePathLabel() {
        val uri = Prefs.getHostUri(this)
        val label = Prefs.getHostLabel(this)
        binding.pathText.text = if (uri != null) "$label\n$uri" else getString(R.string.no_path_selected)
    }

    private fun checkAndToggle() {
        val running = boundService?.isRunning() == true || ServerService.instance?.isRunning() == true
        if (running) {
            // stop
            doStop()
            return
        }
        // start – validate
        val uri = Prefs.getHostUri(this)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.error_no_path), Toast.LENGTH_SHORT).show()
            return
        }
        val port = binding.portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, getString(R.string.error_invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setPort(this, port)
        Prefs.setNatEnabled(this, binding.natSwitch.isChecked)
        // notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        doToggle()
    }


    private fun doToggle() {
        val port = Prefs.getPort(this)
        val nat = Prefs.isNatEnabled(this)
        ServerService.start(this, port, nat)
        // bind shortly after
        binding.root.postDelayed({
            try { bindService(Intent(this, ServerService::class.java), svcConn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
            refreshUi()
        }, 600)
        appendLog("starting server on port $port (NAT=$nat)...")
        refreshUi()
    }

    private fun doStop() {
        ServerService.stop(this)
        appendLog("stopping server...")
        binding.root.postDelayed({ refreshUi() }, 500)
    }

    private fun refreshUi() {
        val svc = boundService ?: ServerService.instance
        val running = svc?.isRunning() == true
        val port = svc?.getPort() ?: Prefs.getPort(this)

        binding.statusText.text = if (running) getString(R.string.server_running) else getString(R.string.server_stopped)
        binding.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(this, if (running) R.color.damn_success else android.R.color.darker_gray)

        binding.toggleBtn.text = if (running) getString(R.string.stop_server) else getString(R.string.start_server)
        binding.toggleBtn.backgroundTintList = ContextCompat.getColorStateList(this, if (running) android.R.color.holo_red_dark else R.color.damn_accent)

        if (running) {
            val localIp = FileUtils.getLocalIp(this)
            binding.localUrlText.text = "http://$localIp:$port/"
            
            val ext = svc?.getExternalIp()
            val extV6 = svc?.getExternalIpV6()
            
            if (extV6 != null) {
                binding.publicUrlText.text = "http://[$extV6]:$port/"
            } else {
                binding.publicUrlText.text = if (ext != null) "http://$ext:$port/" else if (Prefs.isNatEnabled(this)) "NAT: mapping..." else "NAT disabled"
            }
            
            // Tor Address
            val onion = Prefs.getOnionAddress(this)
            if (Prefs.isTorEnabled(this)) {
                binding.onionRow.visibility = View.VISIBLE
                binding.onionUrlText.text = if (onion.isNotEmpty()) onion else "Tor: connecting..."
            } else {
                binding.onionRow.visibility = View.GONE
            }

            // Ngrok Address
            val ngrok = Prefs.getNgrokAddress(this)
            if (Prefs.isNgrokEnabled(this)) {
                binding.ngrokRow.visibility = View.VISIBLE
                binding.ngrokUrlText.text = if (ngrok.isNotEmpty()) ngrok else "Ngrok: starting..."
            } else {
                binding.ngrokRow.visibility = View.GONE
            }
            
            binding.natStatusText.text = if (extV6 != null) "✓ IPv6 Active" else if (ext != null) getString(R.string.nat_active) else if (!Prefs.isNatEnabled(this)) "NAT off — only LAN" else "⏳ Waiting for NAT..."
        } else {
            binding.localUrlText.text = "—"
            binding.publicUrlText.text = "—"
            binding.onionRow.visibility = View.GONE
            binding.ngrokRow.visibility = View.GONE
            binding.natStatusText.text = ""
        }

        // sync switches
        if (!binding.natSwitch.isPressed) binding.natSwitch.isChecked = Prefs.isNatEnabled(this)
        if (!binding.torSwitch.isPressed) binding.torSwitch.isChecked = Prefs.isTorEnabled(this)
        if (!binding.ngrokSwitch.isPressed) binding.ngrokSwitch.isChecked = Prefs.isNgrokEnabled(this)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $msg\n"
        binding.logText.append(line)
        // auto scroll
        binding.logText.post {
            (binding.logText.parent as? android.widget.ScrollView)?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        if (text == "—" || text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun isUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://")

    private fun openInBrowser(url: String) {
        if (!isUrl(url)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInTorBrowser(url: String) {
        if (!isUrl(url)) return
        for (pkg in listOf("org.torproject.torbrowser", "org.torproject.torbrowser_alpha")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "Tor Browser not installed — URL copied instead", Toast.LENGTH_LONG).show()
        copyToClipboard(url, "Tor URL")
    }
}

package com.damn.app.ui

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.damn.app.R
import com.damn.app.databinding.FragmentHomeBinding
import com.damn.app.service.ServerService
import com.damn.app.ui.DashboardMetrics
import com.damn.app.util.FileUtils
import com.damn.app.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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
        if (!granted) Toast.makeText(requireContext(), getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
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
            boundService?.setLogListener { msg -> activity?.runOnUiThread { appendLog(msg); refreshUi() } }
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName?) { isBound = false; boundService = null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.portInput.setText(Prefs.getPort(requireContext()).toString())
        binding.natSwitch.isChecked = Prefs.isNatEnabled(requireContext())
        binding.torSwitch.isChecked = Prefs.isTorEnabled(requireContext())
        binding.ngrokSwitch.isChecked = Prefs.isNgrokEnabled(requireContext())
        binding.cloudflaredSwitch.isChecked = Prefs.isCloudflaredEnabled(requireContext())

        binding.pickFileBtn.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.pickFolderBtn.setOnClickListener { pickFolderLauncher.launch(null) }

        binding.natSwitch.setOnCheckedChangeListener { _, v ->
            Prefs.setNatEnabled(requireContext(), v)
            appendLog("NAT ${if (v) "enabled" else "disabled"}")
            if (boundService?.isRunning() == true) boundService?.toggleNat(v)
        }
        binding.torSwitch.setOnCheckedChangeListener { _, v ->
            Prefs.setTorEnabled(requireContext(), v)
            appendLog("Tor ${if (v) "enabled" else "disabled"}")
            if (boundService?.isRunning() == true) boundService?.toggleTor(v)
        }
        binding.ngrokSwitch.setOnCheckedChangeListener { _, v ->
            Prefs.setNgrokEnabled(requireContext(), v)
            appendLog("Ngrok ${if (v) "enabled" else "disabled"}")
            if (boundService?.isRunning() == true) boundService?.toggleNgrok(v)
        }
        binding.cloudflaredSwitch.setOnCheckedChangeListener { _, v ->
            Prefs.setCloudflaredEnabled(requireContext(), v)
            appendLog("Cloudflare ${if (v) "enabled" else "disabled"}")
            if (boundService?.isRunning() == true) boundService?.toggleCloudflare(v)
        }

        binding.toggleBtn.setOnClickListener { checkAndToggle() }
        binding.localUrlText.setOnClickListener { copyToClipboard(binding.localUrlText.text.toString(), "Local URL") }
        binding.publicUrlText.setOnClickListener { copyToClipboard(binding.publicUrlText.text.toString(), "Public URL") }
        binding.onionUrlText.setOnClickListener { copyToClipboard(binding.onionUrlText.text.toString(), "Tor URL") }
        binding.ngrokUrlText.setOnClickListener { copyToClipboard(binding.ngrokUrlText.text.toString(), "Ngrok URL") }
        binding.cloudflaredUrlText.setOnClickListener { copyToClipboard(binding.cloudflaredUrlText.text.toString(), "Cloudflare URL") }

        binding.copyLocalBtn.setOnClickListener { openInBrowser(binding.localUrlText.text.toString()) }
        binding.copyPublicBtn.setOnClickListener { openInBrowser(binding.publicUrlText.text.toString()) }
        binding.copyOnionBtn.setOnClickListener { openInTorBrowser(binding.onionUrlText.text.toString()) }
        binding.copyNgrokBtn.setOnClickListener { openInBrowser(binding.ngrokUrlText.text.toString()) }
        binding.copyCloudflaredBtn.setOnClickListener { openInBrowser(binding.cloudflaredUrlText.text.toString()) }
        // settingsBtn removed per spec — navigation now via bottom bar
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
                if (p in 1024..65535) Prefs.setPort(requireContext(), p)
            }
        }

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.damn.app.SERVER_STATUS")
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(statusReceiver, filter)
        }
        try { ctx.bindService(Intent(ctx, ServerService::class.java), svcConn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
        refreshUi()
    }

    override fun onStop() {
        try { requireContext().unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        boundService?.clearLogListener()
        if (isBound) try { requireContext().unbindService(svcConn) } catch (_: Exception) {}
        isBound = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.portInput.setText(Prefs.getPort(requireContext()).toString())
        refreshUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun handlePickedUri(uri: Uri, isFile: Boolean) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {}
        val name = FileUtils.getDisplayName(requireContext(), uri)
        var actualIsFile = isFile
        Prefs.setHostUri(requireContext(), uri, name, actualIsFile)
        appendLog("selected ${if (actualIsFile) "file" else "folder"}: $name ($uri)")
        refreshUi()
    }

    private fun checkAndToggle() {
        val running = boundService?.isRunning() == true || ServerService.instance?.isRunning() == true
        if (running) { doStop(); return }
        val uri = Prefs.getHostUri(requireContext())
        if (uri == null) {
            Toast.makeText(requireContext(), getString(R.string.error_no_path), Toast.LENGTH_SHORT).show()
            return
        }
        val port = binding.portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(requireContext(), getString(R.string.error_invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setPort(requireContext(), port)
        Prefs.setNatEnabled(requireContext(), binding.natSwitch.isChecked)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        doToggle()
    }

    private fun doToggle() {
        val ctx = requireContext()
        val port = Prefs.getPort(ctx)
        val nat = Prefs.isNatEnabled(ctx)
        ServerService.start(ctx, port, nat)
        try { DashboardMetrics.start(ctx.applicationContext) } catch (_: Exception) {}
        binding.root.postDelayed({
            try { ctx.bindService(Intent(ctx, ServerService::class.java), svcConn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
            refreshUi()
        }, 600)
        appendLog("starting server on port $port (NAT=$nat)...")
        refreshUi()
    }

    private fun doStop() {
        ServerService.stop(requireContext())
        appendLog("stopping server...")
        binding.root.postDelayed({ refreshUi() }, 500)
    }

    private fun refreshUi() {
        if (_binding == null) return
        val ctx = requireContext()
        val svc = boundService ?: ServerService.instance
        val running = svc?.isRunning() == true
        val port = svc?.getPort() ?: Prefs.getPort(ctx)

        // START = play, STOP = stop — both resized 18dp and themed, no tint to preserve DAMN colors
        binding.toggleBtn.text = if (running) "STOP" else "START"
        binding.toggleBtn.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        binding.toggleBtn.iconTint = null
        binding.toggleBtn.backgroundTintList = ContextCompat.getColorStateList(ctx, if (running) android.R.color.holo_red_dark else R.color.damn_accent)

        // Keep all rows visible — globes grey until active
        if (running) {
            val localIp = FileUtils.getLocalIp(ctx)
            binding.localUrlText.text = "http://$localIp:$port/"
            binding.localUrlText.setTextColor(ContextCompat.getColor(ctx, R.color.damn_accent))
            binding.copyLocalBtn.imageTintList = ContextCompat.getColorStateList(ctx, R.color.damn_accent)

            val ext = svc?.getExternalIp()
            val extV6 = svc?.getExternalIpV6()
            if (extV6 != null) {
                binding.publicUrlText.text = "http://[$extV6]:$port/"
            } else {
                binding.publicUrlText.text = if (ext != null) "http://$ext:$port/" else if (Prefs.isNatEnabled(ctx)) "NAT: mapping..." else "NAT disabled"
            }
            binding.publicUrlText.setTextColor(ContextCompat.getColor(ctx, R.color.damn_accent))
            binding.copyPublicBtn.imageTintList = ContextCompat.getColorStateList(ctx, R.color.damn_accent)

            val onion = Prefs.getOnionAddress(ctx)
            binding.onionRow.visibility = View.VISIBLE
            if (Prefs.isTorEnabled(ctx)) {
                binding.onionUrlText.text = if (onion.isNotEmpty()) onion else "Tor: connecting..."
                val torActive = onion.isNotEmpty()
                binding.onionUrlText.setTextColor(ContextCompat.getColor(ctx, if (torActive) R.color.damn_accent else R.color.damn_success))
                binding.copyOnionBtn.imageTintList = ContextCompat.getColorStateList(ctx, if (torActive) R.color.damn_accent else R.color.damn_success)
                // grey if not yet active, but keep visible
                if (!torActive) binding.copyOnionBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            } else {
                binding.onionUrlText.text = "—"
                binding.onionUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                binding.copyOnionBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            }

            val ngrok = Prefs.getNgrokAddress(ctx)
            binding.ngrokRow.visibility = View.VISIBLE
            if (Prefs.isNgrokEnabled(ctx)) {
                binding.ngrokUrlText.text = if (ngrok.isNotEmpty()) ngrok else "Ngrok: starting..."
                val ngrokActive = ngrok.isNotEmpty()
                binding.ngrokUrlText.setTextColor(ContextCompat.getColor(ctx, if (ngrokActive) R.color.damn_accent else android.R.color.darker_gray))
                binding.copyNgrokBtn.imageTintList = ContextCompat.getColorStateList(ctx, if (ngrokActive) R.color.damn_accent else android.R.color.darker_gray)
            } else {
                binding.ngrokUrlText.text = "—"
                binding.ngrokUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                binding.copyNgrokBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            }

            val cf = Prefs.getCloudflaredAddress(ctx)
            binding.cloudflaredRow.visibility = View.VISIBLE
            if (Prefs.isCloudflaredEnabled(ctx)) {
                binding.cloudflaredUrlText.text = if (cf.isNotEmpty()) cf else "Cloudflare: starting..."
                val cfActive = cf.isNotEmpty()
                binding.cloudflaredUrlText.setTextColor(ContextCompat.getColor(ctx, if (cfActive) R.color.damn_accent else android.R.color.darker_gray))
                binding.copyCloudflaredBtn.imageTintList = ContextCompat.getColorStateList(ctx, if (cfActive) R.color.damn_accent else android.R.color.darker_gray)
            } else {
                binding.cloudflaredUrlText.text = "—"
                binding.cloudflaredUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                binding.copyCloudflaredBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            }
        } else {
            binding.localUrlText.text = "—"
            binding.localUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            binding.publicUrlText.text = "—"
            binding.publicUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            binding.copyLocalBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            binding.copyPublicBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)

            // keep rows visible but grey
            binding.onionRow.visibility = View.VISIBLE
            binding.ngrokRow.visibility = View.VISIBLE
            binding.cloudflaredRow.visibility = View.VISIBLE
            binding.onionUrlText.text = "—"
            binding.ngrokUrlText.text = "—"
            binding.cloudflaredUrlText.text = "—"
            binding.onionUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            binding.ngrokUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            binding.cloudflaredUrlText.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            binding.copyOnionBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            binding.copyNgrokBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
            binding.copyCloudflaredBtn.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
        }
        if (!binding.natSwitch.isPressed) binding.natSwitch.isChecked = Prefs.isNatEnabled(ctx)
        if (!binding.torSwitch.isPressed) binding.torSwitch.isChecked = Prefs.isTorEnabled(ctx)
        if (!binding.ngrokSwitch.isPressed) binding.ngrokSwitch.isChecked = Prefs.isNgrokEnabled(ctx)
        if (!binding.cloudflaredSwitch.isPressed) binding.cloudflaredSwitch.isChecked = Prefs.isCloudflaredEnabled(ctx)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val win = requireActivity().window
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        if (isFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun appendLog(msg: String) {
        if (_binding == null) return
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $msg\n"
        binding.logText.append(line)
        binding.logText.post {
            (binding.logText.parent as? android.widget.ScrollView)?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        if (text == "—" || text.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun isUrl(text: String) = text.startsWith("http://") || text.startsWith("https://")

    private fun openInBrowser(url: String) {
        if (!isUrl(url)) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { Toast.makeText(requireContext(), "No browser found", Toast.LENGTH_SHORT).show() }
    }

    private fun openInTorBrowser(url: String) {
        if (!isUrl(url)) return
        for (pkg in listOf("org.torproject.torbrowser", "org.torproject.torbrowser_alpha")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)); return } catch (_: Exception) {}
        }
        Toast.makeText(requireContext(), "Tor Browser not installed — URL copied instead", Toast.LENGTH_LONG).show()
        copyToClipboard(url, "Tor URL")
    }
}

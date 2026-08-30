package com.damn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.damn.app.databinding.FragmentSettingsBinding
import com.damn.app.util.Prefs
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val importCfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCloudflared(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCollapsibles()
        loadPrefs()
        binding.saveBtn.setOnClickListener { saveSettings() }
        binding.copyTorAddressBtn.setOnClickListener {
            val addr = binding.torOnionAddressText.text.toString()
            if (addr != "Not connected") {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Onion Address", addr))
                Toast.makeText(requireContext(), "Onion address copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.importCfBtn.setOnClickListener { importCfLauncher.launch(arrayOf("*/*")) }
    }

    private fun setupCollapsibles() {
        fun toggle(content: View, chevron: View) {
            val isVisible = content.visibility == View.VISIBLE
            if (isVisible) {
                content.visibility = View.GONE
                chevron.animate().rotation(0f).setDuration(200).start()
            } else {
                content.visibility = View.VISIBLE
                chevron.animate().rotation(180f).setDuration(200).start()
            }
        }
        // All collapsed by default as required
        binding.headerAppearance.setOnClickListener { toggle(binding.contentAppearance, binding.chevronAppearance) }
        binding.headerSystem.setOnClickListener { toggle(binding.contentSystem, binding.chevronSystem) }
        binding.headerSecurity.setOnClickListener { toggle(binding.contentSecurity, binding.chevronSecurity) }
        binding.headerTor.setOnClickListener { toggle(binding.contentTor, binding.chevronTor) }
        binding.headerNgrok.setOnClickListener { toggle(binding.contentNgrok, binding.chevronNgrok) }
        binding.headerCloudflare.setOnClickListener { toggle(binding.contentCloudflare, binding.chevronCloudflare) }
    }

    private fun loadPrefs() {
        val ctx = requireContext()
        when (Prefs.getTheme(ctx)) {
            Prefs.THEME_LIGHT -> binding.themeLight.isChecked = true
            Prefs.THEME_DARK -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
        binding.bootSwitch.isChecked = Prefs.isStartAtBoot(ctx)
        binding.passwordSwitch.isChecked = Prefs.isPasswordEnabled(ctx)
        binding.passwordInput.setText(Prefs.getPassword(ctx))
        binding.shutdownSwitch.isChecked = Prefs.isShutdownOnDisconnect(ctx)
        binding.portraitSwitch.isChecked = Prefs.isForcePortrait(ctx)
        binding.soundSwitch.isChecked = Prefs.isSoundAlertsEnabled(ctx)
        binding.dnsInput.setText(Prefs.getCustomDns(ctx))

        binding.torLocalPortInput.setText(Prefs.getPort(ctx).toString())
        binding.torOnionPortInput.setText(Prefs.getOnionPort(ctx).toString())
        val onion = Prefs.getOnionAddress(ctx)
        binding.torOnionAddressText.text = if (onion.isNotEmpty()) onion else "Not connected"

        binding.ngrokLocalPortInput.setText(Prefs.getPort(ctx).toString())
        binding.ngrokTokenInput.setText(Prefs.getNgrokToken(ctx))
        binding.ngrokDomainInput.setText(Prefs.getNgrokDomain(ctx))
        val ngrokAddr = Prefs.getNgrokAddress(ctx)
        binding.ngrokStatusText.text = if (ngrokAddr.isNotEmpty()) "Ngrok active: $ngrokAddr" else "Ngrok not active"

        binding.cfLocalPortInput.setText(Prefs.getPort(ctx).toString())
        binding.cfTokenInput.setText(Prefs.getCloudflaredToken(ctx))
        val cfAddr = Prefs.getCloudflaredAddress(ctx)
        binding.cfStatusText.text = if (cfAddr.isNotEmpty()) "Cloudflare active: $cfAddr" else "Cloudflare not active — leave token blank for free quick tunnel"
        val hasBin = File(requireContext().filesDir, "bin/cloudflared").exists() || File(requireContext().applicationInfo.nativeLibraryDir, "libcloudflared.so").exists()
        if (!hasBin) binding.cfStatusText.text = "Cloudflare: no binary — tap Import (needs Termux: cp \$PREFIX/bin/cloudflared /sdcard/Download/… then pick it)"
    }

    private fun importCloudflared(uri: Uri) {
        try { requireContext().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        try {
            val dst = File(requireContext().filesDir, "bin/cloudflared")
            dst.parentFile?.mkdirs()
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            }
            dst.setExecutable(true)
            Toast.makeText(requireContext(), "cloudflared imported: ${dst.length() / 1024 / 1024} MB", Toast.LENGTH_LONG).show()
            binding.cfStatusText.text = "Imported — restart server with CF enabled"
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSettings() {
        val ctx = requireContext()
        val selectedTheme = when {
            binding.themeLight.isChecked -> Prefs.THEME_LIGHT
            binding.themeDark.isChecked -> Prefs.THEME_DARK
            else -> Prefs.THEME_SYSTEM
        }
        Prefs.setTheme(ctx, selectedTheme)
        Prefs.setStartAtBoot(ctx, binding.bootSwitch.isChecked)
        Prefs.setPasswordEnabled(ctx, binding.passwordSwitch.isChecked)
        Prefs.setPassword(ctx, binding.passwordInput.text.toString())
        Prefs.setShutdownOnDisconnect(ctx, binding.shutdownSwitch.isChecked)
        Prefs.setForcePortrait(ctx, binding.portraitSwitch.isChecked)
        Prefs.setSoundAlertsEnabled(ctx, binding.soundSwitch.isChecked)
        Prefs.setCustomDns(ctx, binding.dnsInput.text.toString())

        // Determine which section expanded last? Just use visible port inputs — prioritize active? For simplicity, if any of Tor/Ngrok/CF sections visible and edited, sync port accordingly. But we keep simple: check expanded states or just use general port unchanged.
        // If Tor port visible and different, update; if Ngrok visible, use that; else keep existing
        var newPort: Int? = null
        if (binding.contentTor.visibility == View.VISIBLE) newPort = binding.torLocalPortInput.text.toString().toIntOrNull()
        if (binding.contentNgrok.visibility == View.VISIBLE) {
            val np = binding.ngrokLocalPortInput.text.toString().toIntOrNull()
            if (np != null) newPort = np
        }
        if (binding.contentCloudflare.visibility == View.VISIBLE) {
            val cp = binding.cfLocalPortInput.text.toString().toIntOrNull()
            // cloudflare port not directly used as alternative, ignore unless others not set
            if (newPort == null && cp != null) newPort = cp
        }
        if (newPort != null && newPort in 1024..65535) Prefs.setPort(ctx, newPort)

        val onionPort = binding.torOnionPortInput.text.toString().toIntOrNull() ?: 80
        Prefs.setOnionPort(ctx, onionPort)
        Prefs.setNgrokToken(ctx, binding.ngrokTokenInput.text.toString().trim())
        Prefs.setNgrokDomain(ctx, binding.ngrokDomainInput.text.toString().removePrefix("https://").removePrefix("http://").trimEnd('/'))
        Prefs.setCloudflaredToken(ctx, binding.cfTokenInput.text.toString())
        Prefs.applyTheme(ctx)
        Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

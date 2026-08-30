package com.damn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.damn.app.databinding.ActivitySettingsBinding
import com.damn.app.util.Prefs
import com.google.android.material.tabs.TabLayout
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val importCfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCloudflared(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        initGeneralTab()
        initTorTab()
        initNgrokTab()
        initCloudflaredTab()

        binding.saveBtnGeneral.setOnClickListener { saveSettings(); finish() }
        binding.saveBtnTor.setOnClickListener { saveSettings(); finish() }
        binding.saveBtnNgrok.setOnClickListener { saveSettings(); finish() }
        binding.saveBtnCloudflared.setOnClickListener { saveSettings(); finish() }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.tabGeneral.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                binding.tabTor.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
                binding.tabNgrok.visibility = if (tab?.position == 2) View.VISIBLE else View.GONE
                binding.tabCloudflared.visibility = if (tab?.position == 3) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun initGeneralTab() {
        when (Prefs.getTheme(this)) {
            Prefs.THEME_LIGHT -> binding.themeLight.isChecked = true
            Prefs.THEME_DARK -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
        binding.bootSwitch.isChecked = Prefs.isStartAtBoot(this)
        binding.passwordSwitch.isChecked = Prefs.isPasswordEnabled(this)
        binding.passwordInput.setText(Prefs.getPassword(this))
        binding.shutdownSwitch.isChecked = Prefs.isShutdownOnDisconnect(this)
        binding.portraitSwitch.isChecked = Prefs.isForcePortrait(this)
        binding.dnsInput.setText(Prefs.getCustomDns(this))
    }

    private fun initTorTab() {
        binding.torLocalPortInput.setText(Prefs.getPort(this).toString())
        binding.torOnionPortInput.setText(Prefs.getOnionPort(this).toString())
        
        val onion = Prefs.getOnionAddress(this)
        binding.torOnionAddressText.text = if (onion.isNotEmpty()) onion else "Not connected"
        
        binding.copyTorAddressBtn.setOnClickListener {
            val addr = binding.torOnionAddressText.text.toString()
            if (addr != "Not connected") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Onion Address", addr))
                Toast.makeText(this, "Onion address copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initNgrokTab() {
        binding.ngrokLocalPortInput.setText(Prefs.getPort(this).toString())
        binding.ngrokTokenInput.setText(Prefs.getNgrokToken(this))
        binding.ngrokDomainInput.setText(Prefs.getNgrokDomain(this))
        val ngrokAddr = Prefs.getNgrokAddress(this)
        binding.ngrokStatusText.text = if (ngrokAddr.isNotEmpty()) "Ngrok active: $ngrokAddr" else "Ngrok not active"
    }

    private fun initCloudflaredTab() {
        binding.cfLocalPortInput.setText(Prefs.getPort(this).toString())
        binding.cfTokenInput.setText(Prefs.getCloudflaredToken(this))
        val cfAddr = Prefs.getCloudflaredAddress(this)
        binding.cfStatusText.text = if (cfAddr.isNotEmpty()) "Cloudflare active: $cfAddr" else "Cloudflare not active — leave token blank for free quick tunnel"
        // Check if binary is present
        val hasBin = File(filesDir, "bin/cloudflared").exists() || File(applicationInfo.nativeLibraryDir, "libcloudflared.so").exists()
        if (!hasBin) binding.cfStatusText.text = "Cloudflare: no binary — tap Import (needs Termux: cp \$PREFIX/bin/cloudflared /sdcard/Download/… then pick it)"
        binding.importCfBtn.setOnClickListener { importCfLauncher.launch(arrayOf("*/*")) }
    }

    private fun importCloudflared(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        try {
            val dst = File(filesDir, "bin/cloudflared")
            dst.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            }
            dst.setExecutable(true)
            Toast.makeText(this, "cloudflared imported: ${dst.length() / 1024 / 1024} MB", Toast.LENGTH_LONG).show()
            binding.cfStatusText.text = "Imported — restart server with CF enabled"
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSettings() {
        // Theme
        val selectedTheme = when {
            binding.themeLight.isChecked -> Prefs.THEME_LIGHT
            binding.themeDark.isChecked -> Prefs.THEME_DARK
            else -> Prefs.THEME_SYSTEM
        }
        Prefs.setTheme(this, selectedTheme)
        
        // General
        Prefs.setStartAtBoot(this, binding.bootSwitch.isChecked)
        Prefs.setPasswordEnabled(this, binding.passwordSwitch.isChecked)
        Prefs.setPassword(this, binding.passwordInput.text.toString())
        Prefs.setShutdownOnDisconnect(this, binding.shutdownSwitch.isChecked)
        Prefs.setForcePortrait(this, binding.portraitSwitch.isChecked)
        Prefs.setCustomDns(this, binding.dnsInput.text.toString())
        
        // Port Sync (check which tab is active to prioritize, but generally we sync all)
        val activeTab = binding.tabLayout.selectedTabPosition
        val newPort = when (activeTab) {
            1 -> binding.torLocalPortInput.text.toString().toIntOrNull()
            2 -> binding.ngrokLocalPortInput.text.toString().toIntOrNull()
            else -> Prefs.getPort(this)
        } ?: 8080
        Prefs.setPort(this, newPort)

        // Tor specific
        val onionPort = binding.torOnionPortInput.text.toString().toIntOrNull() ?: 80
        Prefs.setOnionPort(this, onionPort)
        
        // Ngrok specific
        Prefs.setNgrokToken(this, binding.ngrokTokenInput.text.toString().trim())
        Prefs.setNgrokDomain(
            this,
            binding.ngrokDomainInput.text.toString()
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
        )

        // Cloudflared specific
        Prefs.setCloudflaredToken(this, binding.cfTokenInput.text.toString())

        Prefs.applyTheme(this)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }
}

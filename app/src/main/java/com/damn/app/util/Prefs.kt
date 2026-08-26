package com.damn.app.util

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "damn_prefs"
    private const val KEY_PATH_URI = "host_uri"
    private const val KEY_PATH_LABEL = "host_label"
    private const val KEY_IS_FILE = "is_file"
    private const val KEY_PORT = "port"
    private const val KEY_NAT = "nat_enabled"
    private const val KEY_BOOT = "start_at_boot"
    private const val KEY_WAS_RUNNING = "was_running"
    private const val KEY_THEME = "theme"
    private const val KEY_PASS_ENABLED = "pass_enabled"
    private const val KEY_PASS_VALUE = "pass_value"
    private const val KEY_SHUTDOWN_DISCONNECT = "shutdown_disconnect"
    private const val KEY_TOR_ENABLED = "tor_enabled"
    private const val KEY_NGROK_ENABLED = "ngrok_enabled"
    private const val KEY_ONION_PORT = "onion_port"
    private const val KEY_NGROK_TOKEN = "ngrok_token"
    private const val KEY_NGROK_DOMAIN = "ngrok_domain"
    private const val KEY_ONION_ADDRESS = "onion_address"
    private const val KEY_NGROK_ADDRESS = "ngrok_address"
    private const val KEY_ONION_PRIVATE_KEY = "onion_private_key"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var hostUri: String?
        get() = null
        set(_) {}

    fun getHostUri(ctx: Context): Uri? {
        val s = prefs(ctx).getString(KEY_PATH_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    fun setHostUri(ctx: Context, uri: Uri?, label: String, isFile: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_PATH_URI, uri?.toString())
            .putString(KEY_PATH_LABEL, label)
            .putBoolean(KEY_IS_FILE, isFile)
            .apply()
    }

    fun getHostLabel(ctx: Context): String =
        prefs(ctx).getString(KEY_PATH_LABEL, "") ?: ""

    fun isHostFile(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IS_FILE, false)

    fun getPort(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, 8080)
    fun setPort(ctx: Context, port: Int) { prefs(ctx).edit().putInt(KEY_PORT, port).apply() }

    fun isNatEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NAT, true)
    fun setNatEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_NAT, v).apply() }

    fun isStartAtBoot(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BOOT, false)
    fun setStartAtBoot(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_BOOT, v).apply() }

    fun wasRunning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WAS_RUNNING, false)
    fun setWasRunning(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_WAS_RUNNING, v).apply() }

    fun getTheme(ctx: Context): Int = prefs(ctx).getInt(KEY_THEME, THEME_SYSTEM)
    fun setTheme(ctx: Context, v: Int) { prefs(ctx).edit().putInt(KEY_THEME, v).apply() }

    fun hasHost(ctx: Context): Boolean = getHostUri(ctx) != null

    fun isPasswordEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PASS_ENABLED, false)
    fun setPasswordEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_PASS_ENABLED, v).apply() }

    fun getPassword(ctx: Context): String = prefs(ctx).getString(KEY_PASS_VALUE, "") ?: ""
    fun setPassword(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_PASS_VALUE, v).apply() }

    fun isShutdownOnDisconnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHUTDOWN_DISCONNECT, false)
    fun setShutdownOnDisconnect(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_SHUTDOWN_DISCONNECT, v).apply() }

    fun isTorEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TOR_ENABLED, false)
    fun setTorEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_TOR_ENABLED, v).apply() }

    fun isNgrokEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NGROK_ENABLED, false)
    fun setNgrokEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_NGROK_ENABLED, v).apply() }

    fun getOnionPort(ctx: Context): Int = prefs(ctx).getInt(KEY_ONION_PORT, 80)
    fun setOnionPort(ctx: Context, port: Int) { prefs(ctx).edit().putInt(KEY_ONION_PORT, port).apply() }

    fun getNgrokToken(ctx: Context): String = prefs(ctx).getString(KEY_NGROK_TOKEN, "") ?: ""
    fun setNgrokToken(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_NGROK_TOKEN, v).apply() }

    fun getNgrokDomain(ctx: Context): String = prefs(ctx).getString(KEY_NGROK_DOMAIN, "") ?: ""
    fun setNgrokDomain(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_NGROK_DOMAIN, v.trim()).apply() }

    fun getOnionAddress(ctx: Context): String = prefs(ctx).getString(KEY_ONION_ADDRESS, "") ?: ""
    fun setOnionAddress(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_ONION_ADDRESS, v).apply() }

    fun getOnionPrivateKey(ctx: Context): String = prefs(ctx).getString(KEY_ONION_PRIVATE_KEY, "") ?: ""
    fun setOnionPrivateKey(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_ONION_PRIVATE_KEY, v).apply() }

    fun getNgrokAddress(ctx: Context): String = prefs(ctx).getString(KEY_NGROK_ADDRESS, "") ?: ""
    fun setNgrokAddress(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_NGROK_ADDRESS, v).apply() }

    fun applyTheme(ctx: Context) {
        val theme = getTheme(ctx)
        val mode = when (theme) {
            THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }
}

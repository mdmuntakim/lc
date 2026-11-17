package dev.lc.console

import android.content.Context
import android.content.SharedPreferences

/**
 * Small typed wrapper around the app-private SharedPreferences.
 * Everything here lives inside the app sandbox and is removed on uninstall
 * (allowBackup is disabled in the manifest).
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("lc", Context.MODE_PRIVATE)

    /** The user accepted the first-run disclosure; the service may only start after this. */
    var disclosed: Boolean
        get() = sp.getBoolean("disclosed", false)
        set(v) = sp.edit().putBoolean("disclosed", v).apply()

    /** Opt-in boot start, off by default. */
    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    /** Advanced mode removes the read-only allowlist. Off by default. */
    var advanced: Boolean
        get() = sp.getBoolean("advanced", false)
        set(v) = sp.edit().putBoolean("advanced", v).apply()

    /** LAN exposure. Off by default; always TLS + token when on. */
    var lanEnabled: Boolean
        get() = sp.getBoolean("lanEnabled", false)
        set(v) = sp.edit().putBoolean("lanEnabled", v).apply()

    /** Loopback (or LAN) listen port. */
    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Default command timeout in seconds. */
    var commandTimeoutSec: Int
        get() = sp.getInt("commandTimeoutSec", DEFAULT_TIMEOUT_SEC)
        set(v) = sp.edit().putInt("commandTimeoutSec", v).apply()

    companion object {
        const val DEFAULT_PORT = 6767
        const val DEFAULT_TIMEOUT_SEC = 30
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MIN_TIMEOUT_SEC = 5
        const val MAX_TIMEOUT_SEC = 300
    }
}

package dev.lc.console

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var status: TextView
    private lateinit var tokenView: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var rotate: Button
    private lateinit var bootSwitch: Switch
    private lateinit var advancedSwitch: Switch
    private lateinit var lanSwitch: Switch
    private lateinit var lanInfo: TextView
    private lateinit var portInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var apply: Button
    private lateinit var viewAudit: Button
    private lateinit var clearAudit: Button

    private val stateListener = { refreshStatus() }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The foreground notification is best-effort; start regardless, the
            // service itself still runs visibly as a foreground service.
            doStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        tokenView = findViewById(R.id.token)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        rotate = findViewById(R.id.rotate)
        bootSwitch = findViewById(R.id.bootSwitch)
        advancedSwitch = findViewById(R.id.advancedSwitch)
        lanSwitch = findViewById(R.id.lanSwitch)
        lanInfo = findViewById(R.id.lanInfo)
        portInput = findViewById(R.id.port)
        timeoutInput = findViewById(R.id.timeout)
        apply = findViewById(R.id.apply)
        viewAudit = findViewById(R.id.viewAudit)
        clearAudit = findViewById(R.id.clearAudit)

        wire()
        refreshSettings()
        maybeShowDisclosure()
    }

    override fun onResume() {
        super.onResume()
        ConsoleState.addListener(stateListener)
        refreshStatus()
        refreshSettings()
    }

    override fun onPause() {
        super.onPause()
        ConsoleState.removeListener(stateListener)
    }

    private fun wire() {
        start.setOnClickListener {
            if (!prefs.disclosed) {
                toast("Accept the disclosure first")
                maybeShowDisclosure()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                doStart()
            }
        }
        stop.setOnClickListener { ConsoleService.stop(this) }
        rotate.setOnClickListener { confirmRotate() }
        apply.setOnClickListener { applySettings() }
        viewAudit.setOnClickListener { showAudit() }
        clearAudit.setOnClickListener { confirmClearAudit() }
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableBoot() else setBoot(false)
        }
        advancedSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableAdvanced() else setAdvanced(false)
        }
        lanSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableLan() else setLan(false)
        }
    }

    private fun doStart() {
        ConsoleService.start(this)
        toast("Console starting…")
    }

    // ---- disclosure --------------------------------------------------------

    private fun maybeShowDisclosure() {
        if (prefs.disclosed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(
                "LC runs a small web console on this device so you can run " +
                    "diagnostics from the browser.\n\n" +
                    "• Commands run with LC's own Android UID — never root.\n" +
                    "• By default the console listens only on 127.0.0.1 and is " +
                    "reachable from this device alone.\n" +
                    "• LAN access is off by default; when enabled it always " +
                    "requires a pairing token and TLS with a self-signed certificate.\n" +
                    "• Every command and its result is written to a local audit " +
                    "log. Nothing leaves the device unless a command you ran " +
                    "uses the network itself.\n\n" +
                    "The console never starts by itself: you tap Start, and boot " +
                    "start is opt-in."
            )
            .setPositiveButton(R.string.disclosure_accept) { _, _ -> prefs.disclosed = true }
            .setNegativeButton(R.string.disclosure_decline) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ---- token -------------------------------------------------------------

    private fun confirmRotate() {
        AlertDialog.Builder(this)
            .setTitle("Rotate token")
            .setMessage(
                "A new pairing token replaces the current one and every open " +
                    "session is logged out. The old token stops working immediately."
            )
            .setPositiveButton("Rotate") { _, _ ->
                TokenStore.rotate(this, tokenBox)
                refreshSettings()
                toast("Token rotated — old token and sessions are invalid")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- settings ----------------------------------------------------------

    private fun confirmEnableBoot() {
        warn(
            "Start on boot",
            "After the device boots, LC will start the console service on its " +
                "own and show the persistent notification. This is off by " +
                "default because it gives the app autonomy."
        ) { setBoot(true) }
    }

    private fun confirmEnableAdvanced() {
        warn(
            "Advanced mode",
            "Advanced mode removes the read-only allowlist: any command can be " +
                "typed into the console. Commands still run as LC's app UID — " +
                "no root, no su — and the timeout (30 s default) and the 1 MiB " +
                "output cap still apply, with everything written to the audit log."
        ) { setAdvanced(true) }
    }

    private fun confirmEnableLan() {
        warn(
            "LAN mode",
            "The console will also listen on the local network over TLS with a " +
                "self-signed certificate. Your browser will show a certificate " +
                "warning — verify the details before accepting it. The pairing " +
                "token stays mandatory in every mode."
        ) { setLan(true) }
    }

    private fun warn(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Enable") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .setOnCancelListener { refreshSettings() }
            .show()
    }

    private fun setBoot(v: Boolean) {
        prefs.startOnBoot = v
        setComponentEnabled(v)
        refreshSettings()
    }

    private fun setAdvanced(v: Boolean) {
        prefs.advanced = v
        refreshSettings()
    }

    private fun setLan(v: Boolean) {
        prefs.lanEnabled = v
        refreshSettings()
        if (ConsoleState.phase == ConsoleState.Phase.RUNNING) ConsoleService.apply(this)
    }

    /** Boot receiver is registered (component enabled) only while the setting is on. */
    private fun setComponentEnabled(enable: Boolean) {
        val name = ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            name,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun applySettings() {
        val port = portInput.text.toString().toIntOrNull()
        if (port != null && (port < Prefs.MIN_PORT || port > Prefs.MAX_PORT)) {
            toast("Port must be between ${Prefs.MIN_PORT} and ${Prefs.MAX_PORT}")
            return
        }
        val timeout = timeoutInput.text.toString().toIntOrNull()
        if (timeout != null && (timeout < Prefs.MIN_TIMEOUT_SEC || timeout > Prefs.MAX_TIMEOUT_SEC)) {
            toast("Timeout must be between ${Prefs.MIN_TIMEOUT_SEC} and ${Prefs.MAX_TIMEOUT_SEC} seconds")
            return
        }
        if (port != null) prefs.port = port
        if (timeout != null) prefs.commandTimeoutSec = timeout
        if (ConsoleState.phase == ConsoleState.Phase.RUNNING) ConsoleService.apply(this)
        refreshStatus()
        toast("Settings applied")
    }

    private fun refreshSettings() {
        bootSwitch.setOnCheckedChangeListener(null)
        advancedSwitch.setOnCheckedChangeListener(null)
        lanSwitch.setOnCheckedChangeListener(null)
        bootSwitch.isChecked = prefs.startOnBoot
        advancedSwitch.isChecked = prefs.advanced
        lanSwitch.isChecked = prefs.lanEnabled
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableBoot() else setBoot(false)
        }
        advancedSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableAdvanced() else setAdvanced(false)
        }
        lanSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmEnableLan() else setLan(false)
        }
        if (portInput.text.isEmpty()) portInput.setText(prefs.port.toString())
        if (timeoutInput.text.isEmpty()) timeoutInput.setText(prefs.commandTimeoutSec.toString())
        val token = TokenStore.current(this, tokenBox)
        tokenView.text = token
        lanInfo.text = if (prefs.lanEnabled) {
            "LAN mode is ON: TLS on port ${prefs.port}, interface " +
                "${ConsoleService.lanAddressStatic() ?: "unknown"}. A browser will " +
                "show a self-signed-certificate warning; the pairing token is always required."
        } else {
            getString(R.string.lan_off_info)
        }
    }

    // ---- audit -------------------------------------------------------------

    private fun showAudit() {
        val log = AuditLog(java.io.File(filesDir, "audit.jsonl"))
        val entries = log.entries(200)
        val text = if (entries.isEmpty()) {
            "No commands recorded yet."
        } else {
            entries.joinToString("\n") { e ->
                val exit = if (e.isNull("exit")) "-" else e.opt("exit").toString()
                "${e.optString("ts")}  [${e.optString("mode")}] ${e.optString("command")}" +
                    "  → ${e.optString("status")}, exit $exit, ${e.optLong("duration_ms")} ms"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Audit log (local)")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmClearAudit() {
        AlertDialog.Builder(this)
            .setTitle("Clear audit log")
            .setMessage("Delete every recorded command entry? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                AuditLog(java.io.File(filesDir, "audit.jsonl")).clear()
                HistoryStore(java.io.File(filesDir, "history.jsonl")).clear()
                toast("Audit log and history cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- helpers -----------------------------------------------------------

    private val tokenBox = TokenBox(null)

    private fun refreshStatus() {
        val state = ConsoleState
        val text = when (state.phase) {
            ConsoleState.Phase.STOPPED ->
                "Console stopped — nothing is listening. Tap Start."
            ConsoleState.Phase.STARTING -> "Starting…"
            ConsoleState.Phase.RUNNING ->
                "Running: ${state.message}"
            ConsoleState.Phase.ERROR -> "Error: ${state.message}"
        }
        status.text = text
        start.isEnabled = state.phase == ConsoleState.Phase.STOPPED ||
            state.phase == ConsoleState.Phase.ERROR
        stop.isEnabled = state.phase == ConsoleState.Phase.RUNNING ||
            state.phase == ConsoleState.Phase.STARTING
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

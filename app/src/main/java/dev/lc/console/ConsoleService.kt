package dev.lc.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.BindException
import java.net.NetworkInterface

/**
 * Foreground service hosting the local web console.
 *
 * - Runs only when the user (or the opt-in boot receiver) starts it.
 * - Shows the persistent "LC console running" notification with a Stop action
 *   for as long as the service is alive.
 * - Binds 127.0.0.1 by default; LAN mode is opt-in and always TLS.
 * - The foreground service type is `specialUse`: a local, owner-initiated
 *   diagnostic console has no matching standard type, and unlike `dataSync`
 *   the specialUse type has no time cap. The subtype is declared in the
 *   manifest property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
 */
class ConsoleService : Service() {

    private var server: LcHttpServer? = null
    private var api: ConsoleApi? = null
    private val tokenBox = TokenBox(null)
    private lateinit var prefs: Prefs
    private lateinit var audit: AuditLog
    private lateinit var history: HistoryStore
    private lateinit var sessions: SessionManager
    private lateinit var jobs: JobManager

    private val stateListener = {
        updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audit = AuditLog(java.io.File(filesDir, "audit.jsonl"))
        history = HistoryStore(java.io.File(filesDir, "history.jsonl"))
        sessions = SessionManager()
        jobs = JobManager(audit, history, apiConfig)
        api = ConsoleApi(
            tokens = tokenBox,
            sessions = sessions,
            rateLimiter = RateLimiter(),
            authGuard = AuthGuard(),
            audit = audit,
            history = history,
            jobs = jobs,
            config = apiConfig
        )
        ConsoleState.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() on every start,
        // including the Stop action (removed again immediately in shutdown()).
        beginForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_APPLY -> {
                tokenBox.token = TokenStore.current(this, tokenBox)
                if (ConsoleState.phase == ConsoleState.Phase.RUNNING ||
                    ConsoleState.phase == ConsoleState.Phase.STARTING
                ) {
                    startServerThread()
                }
                return START_NOT_STICKY
            }
            else -> {
                // Pairing token: generated on first start, shown in the app.
                tokenBox.token = TokenStore.current(this, tokenBox)
                startServerThread()
            }
        }
        return START_NOT_STICKY
    }

    private val apiConfig = object : ConsoleApi.Config {
        override fun advanced(): Boolean = prefs.advanced
        override fun lanEnabled(): Boolean = prefs.lanEnabled
        override fun port(): Int = prefs.port
        override fun timeoutSec(): Int = prefs.commandTimeoutSec
    }

    private fun beginForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LC console", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the LC diagnostic console is running" }
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ConsoleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_console)
            .setContentTitle("LC console running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_console, "Stop", stop)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(ConsoleState.message))
    }

    private fun startServerThread() {
        ConsoleState.update(ConsoleState.Phase.STARTING, "Starting console…")
        Thread({
            try {
                server?.stop()
                val lan = prefs.lanEnabled
                val factory = if (lan) Tls.factory(this) else null
                val bind = if (lan) "0.0.0.0" else "127.0.0.1"
                val scheme = if (lan) "https" else "http"
                val s = LcHttpServer(prefs.port, bind, factory) { ex -> api!!.handle(ex) }
                s.start()
                server = s
                val msg = if (lan) {
                    "$scheme://$bind:${s.boundPort} (TLS) — LAN address: ${lanAddress() ?: "none found"}"
                } else {
                    "$scheme://127.0.0.1:${s.boundPort}"
                }
                ConsoleState.update(ConsoleState.Phase.RUNNING, msg)
            } catch (e: Exception) {
                val detail = if (e is BindException) {
                    "port ${prefs.port} is busy (${e.message ?: "address already in use"})"
                } else {
                    e.message ?: e.javaClass.simpleName
                }
                ConsoleState.update(ConsoleState.Phase.ERROR, "Console failed: $detail")
                stopSelf()
            }
        }, "lc-server-start").start()
    }

    private fun shutdown() {
        server?.stop()
        server = null
        jobs.cancelAll()
        sessions.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        ConsoleState.update(ConsoleState.Phase.STOPPED, "Console stopped")
    }

    private fun lanAddress(): String? = lanAddressStatic()

    override fun onDestroy() {
        server?.stop()
        server = null
        jobs.cancelAll()
        ConsoleState.removeListener(stateListener)
        if (ConsoleState.phase == ConsoleState.Phase.RUNNING ||
            ConsoleState.phase == ConsoleState.Phase.STARTING
        ) {
            ConsoleState.update(ConsoleState.Phase.STOPPED, "Console stopped")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "lc-console"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.lc.console.STOP"
        const val ACTION_APPLY = "dev.lc.console.APPLY"

        /** Explicit user action (or the opt-in boot receiver) is the only entry point. */
        fun start(context: Context) {
            val intent = Intent(context, ConsoleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConsoleService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun apply(context: Context) {
            val intent = Intent(context, ConsoleService::class.java).setAction(ACTION_APPLY)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Best-effort site-local IPv4 address of this device on the LAN. */
        fun lanAddressStatic(): String? = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

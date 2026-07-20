package com.hereliesaz.mcpserved.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hereliesaz.mcpserved.R
import com.hereliesaz.mcpserved.backend.A11yBackend
import com.hereliesaz.mcpserved.backend.ControlBackend
import com.hereliesaz.mcpserved.backend.Resolver
import com.hereliesaz.mcpserved.backend.RootBackend
import com.hereliesaz.mcpserved.backend.ShizukuBackend
import com.hereliesaz.mcpserved.crypto.McpToken
import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.grant.Enforcer
import com.hereliesaz.mcpserved.grant.GrantStore
import com.hereliesaz.mcpserved.grant.SessionLog
import com.hereliesaz.mcpserved.transport.LocalServer
import com.hereliesaz.mcpserved.transport.McpBridge
import com.hereliesaz.mcpserved.transport.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service holding everything with a lifetime longer than one request:
 * the backend resolver, the grant store, the session, the wakelock, and the
 * loopback control server.
 *
 * The service runs whenever the app is armed, not only during a session. It must
 * be resident so the desktop server can reach the loopback port and open a
 * session at all — a service that only existed during sessions could never be
 * told to begin one.
 *
 * The persistent notification is not a formality imposed by the platform. It is
 * the operator's only continuous indication that something is holding authority
 * over the device, and its STOP action is the fastest revocation path that does
 * not involve pulling a battery that no longer comes out.
 */
class ControlService : Service() {

    companion object {
        private const val TAG = "ControlService"
        private const val CHANNEL_ID = "mcpserved.session"
        private const val NOTIFICATION_ID = 0x4D43

        /** Ends any live session and releases the wakelock. Leaves the service armed. */
        const val ACTION_STOP_SESSION = "com.hereliesaz.mcpserved.STOP_SESSION"

        /** Ends the session and clears every grant. The panic control. */
        const val ACTION_REVOKE_ALL = "com.hereliesaz.mcpserved.REVOKE_ALL"

        /** Stops the service entirely, closing the loopback control server. */
        const val ACTION_DISARM = "com.hereliesaz.mcpserved.DISARM"

        @Volatile
        var instance: ControlService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var resolver: Resolver
        private set
    lateinit var grants: GrantStore
        private set
    lateinit var log: SessionLog
        private set
    lateinit var enforcer: Enforcer
        private set
    lateinit var session: Session
        private set
    lateinit var pairing: Pairing
        private set
    lateinit var server: LocalServer
        private set
    lateinit var mcpServer: McpServer
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    /** Set when the screen is being held on by `svc power stayon` rather than a wakelock. */
    private var stayOnEngaged = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Construction order is preference order. Accessibility first, so that
        // observation and gestures resolve to it even on a rooted device.
        val backends: List<ControlBackend> =
            listOf(A11yBackend(), RootBackend(), ShizukuBackend())

        resolver = Resolver(backends)
        grants = GrantStore(applicationContext)
        log = SessionLog()
        enforcer = Enforcer(resolver, grants, log)
        session = Session()
        pairing = Pairing(applicationContext)

        // One dispatcher behind both front doors: the desktop bridge's sealed
        // loopback ([LocalServer]) and the device's own MCP-over-HTTP endpoint
        // ([McpServer]). Both are just transports into the same enforcement.
        val dispatcher = Dispatcher(applicationContext, this)
        server = LocalServer(pairing, dispatcher, scope)
        mcpServer = McpServer(
            McpToken(applicationContext),
            McpBridge(dispatcher) { resolver.hasRoot || resolver.hasShizuku },
        )

        setArmed(true)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        server.start()
        if (!mcpServer.startServer()) {
            // The port is taken or the bind was refused. The device's direct MCP
            // endpoint is unavailable this run; the sealed-frame server for the
            // desktop bridge is unaffected, so the service stays useful.
            Log.w(TAG, "on-device MCP endpoint failed to bind; the desktop bridge still works")
        }
        scope.launch { reaper() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> endSession()
            ACTION_REVOKE_ALL -> {
                endSession()
                scope.launch { grants.revokeAll() }
            }
            ACTION_DISARM -> {
                endSession()
                setArmed(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        endSession()
        // Guarded: onCreate may have thrown before these lateinit fields were
        // assigned, and onDestroy still runs. Touching them unguarded would raise
        // UninitializedPropertyAccessException and mask the original failure.
        if (::mcpServer.isInitialized) mcpServer.stopServer()
        if (::server.isInitialized) scope.launch { server.stop() }
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    /**
     * Records whether the service should return after a reboot.
     *
     * Arming is a deliberate act and should survive a restart. Coming back
     * armed when the user had disarmed would be the exact failure this design is
     * arranged against, so the flag is cleared on explicit disarm and nowhere else.
     */
    private fun setArmed(armed: Boolean) {
        getSharedPreferences("service", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_ARMED, armed)
            .apply()
    }

    /**
     * Opens a session and holds the screen awake for its duration.
     *
     * The screen must stay on because accessibility events cease and
     * `rootInActiveWindow` returns null once the device locks — the tree goes
     * empty and every subsequent action fails while appearing merely unlucky.
     * Holding the screen is the honest version of that constraint: visible,
     * battery-hostile, and impossible to forget is running.
     */
    fun beginSession(ttlSec: Int): Session.State {
        val state = session.begin(ttlSec)
        acquireScreenHold()
        refreshNotification()
        return state
    }

    /** Ends the session, releases the screen hold, and clears the action log. */
    fun endSession() {
        session.end()
        releaseScreenHold()
        log.clear()
        refreshNotification()
    }

    /** Extends the live session; called after every successful action. */
    fun touchSession() {
        session.touch()?.let { refreshNotification() }
    }

    /**
     * Holds the screen on.
     *
     * Prefers `svc power stayon true` when a privileged backend exists: it is the
     * mechanism the platform uses itself and survives without an app-held lock.
     * Falls back to `SCREEN_BRIGHT_WAKE_LOCK`, deprecated since API 17 and still
     * the only route available to an unprivileged app with no Activity on screen.
     * There is no supported modern equivalent; `FLAG_KEEP_SCREEN_ON` requires a
     * window this service does not have.
     */
    private fun acquireScreenHold() {
        if (resolver.hasRoot || resolver.hasShizuku) {
            scope.launch {
                if (resolver.shell("svc power stayon true").isSuccess) {
                    stayOnEngaged = true
                    return@launch
                }
                acquireWakeLock()
            }
            return
        }
        acquireWakeLock()
    }

    // acquireWakeLock and releaseScreenHold both touch the shared wakeLock field
    // and are reached from the main thread and from scope.launch on
    // Dispatchers.Default. Synchronizing them keeps a race from stranding a held
    // lock that nothing releases — the screen stays lit and the battery drains.
    @Suppress("DEPRECATION")
    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "mcpserved:session"
        ).apply {
            setReferenceCounted(false)
            // Bounded even though the reaper also releases it. A wakelock whose
            // release path is the only thing standing between the user and a dead
            // battery deserves a second, dumber guarantee.
            acquire(Session.MAX_TTL_SEC * 1000L)
        }
    }

    @Synchronized
    private fun releaseScreenHold() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (stayOnEngaged) {
            stayOnEngaged = false
            scope.launch { resolver.shell("svc power stayon false") }
        }
    }

    /**
     * Polls for session expiry and tears down when it lapses.
     *
     * Polling rather than scheduling: an alarm set for the expiry moment fires
     * late under doze, and a session that outlives its own deadline because the
     * CPU was asleep defeats the point of having one.
     */
    private suspend fun reaper() {
        while (true) {
            delay(5_000)
            if (session.state.value != null && !session.reap()) {
                releaseScreenHold()
                log.clear()
                refreshNotification()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_session),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_session_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, ControlService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val active = session.state.value
        val title = if (active == null) getString(R.string.notif_armed)
        else getString(R.string.notif_active, active.remainingSec())
        val body = if (active == null) getString(R.string.notif_armed_body)
        else getString(R.string.notif_active_body, resolver.describe().joinToString(", "))

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (active != null) {
            b.addAction(0, getString(R.string.action_stop), action(ACTION_STOP_SESSION))
        }
        b.addAction(0, getString(R.string.action_revoke_all), action(ACTION_REVOKE_ALL))
        b.addAction(0, getString(R.string.action_disarm), action(ACTION_DISARM))
        return b.build()
    }

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}

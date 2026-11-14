package dev.pocketshare.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketshare.MainActivity
import dev.pocketshare.R
import dev.pocketshare.core.model.ShareState
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.runtime.Reachability
import dev.pocketshare.runtime.ShareRuntime
import dev.pocketshare.runtime.SharingPhase
import dev.pocketshare.runtime.SharingState
import dev.pocketshare.tunnel.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SharingService : Service() {
    @Inject
    lateinit var runtime: ShareRuntime

    @Inject
    lateinit var shareRepository: ShareRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeSeen = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        ensureForeground(runtime.state.value)
        stateJob =
            scope.launch {
                runtime.state.collect(::onStateChanged)
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureForeground(runtime.state.value)
        when (intent?.action) {
            ACTION_START -> scope.launch { runtime.start() }
            ACTION_STOP -> scope.launch { runtime.stop() }
            ACTION_COPY_LINK -> copyShareLink()
            // Null action means Android restarted the service after the process was killed.
            null -> scope.launch { restoreAfterProcessDeath() }
        }
        return START_STICKY
    }

    /**
     * A named tunnel keeps the same public hostname, so resuming after a process kill preserves
     * links that were already shared. Quick tunnels get a new random address on every start, so
     * silently restarting one would publish a link the user never saw; leave that to the UI.
     */
    private suspend fun restoreAfterProcessDeath() {
        val mode =
            runCatching { settingsRepository.settings.first().tunnelMode }
                .getOrNull()
        if (mode != TunnelMode.NAMED) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        runtime.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun onStateChanged(state: SharingState) {
        if (state.isSharing) activeSeen = true
        updateNotification(state)
        if (activeSeen && (state.phase == SharingPhase.STOPPED || state.phase == SharingPhase.ERROR)) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun ensureForeground(state: SharingState) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), type)
    }

    private fun updateNotification(state: SharingState) {
        if (!activeSeen) return
        // The foreground-service notification itself does not need POST_NOTIFICATIONS, but updates do.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: SharingState): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, SharingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val copy =
            PendingIntent.getService(
                this,
                REQUEST_COPY,
                Intent(this, SharingService::class.java).setAction(ACTION_COPY_LINK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val degraded =
            state.phase == SharingPhase.RUNNING &&
                (
                    state.tunnelStatus is TunnelStatus.Degraded ||
                        state.reachability == Reachability.UNREACHABLE
                )
        val title =
            when {
                degraded -> getString(R.string.home_status_degraded_title)
                state.phase == SharingPhase.RUNNING -> getString(R.string.notification_running_title)
                state.phase == SharingPhase.ERROR -> getString(R.string.home_status_error_title)
                else -> getString(R.string.notification_starting_title)
            }
        val body = state.publicUrl ?: getString(R.string.notification_running_body)

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setOngoing(state.phase != SharingPhase.ERROR)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stop)
            .addAction(0, getString(R.string.notification_copy), copy)
            .build()
    }

    private fun copyShareLink() {
        scope.launch {
            val now = System.currentTimeMillis()
            val link =
                runCatching {
                    shareRepository.shares
                        .first()
                        .firstOrNull { it.state(now) == ShareState.ACTIVE }
                        ?.let { runtime.publicLink(it.token) }
                }.getOrNull() ?: runtime.state.value.publicUrl
            if (link.isNullOrBlank()) {
                Toast.makeText(this@SharingService, R.string.share_copy_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), link))
            Toast.makeText(this@SharingService, R.string.notification_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_running_body)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock =
            manager
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PocketShare::sharing",
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_START = "dev.pocketshare.action.START"
        const val ACTION_STOP = "dev.pocketshare.action.STOP"
        const val ACTION_COPY_LINK = "dev.pocketshare.action.COPY_LINK"

        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_COPY = 3
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 24L * 60L * 60L * 1_000L

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(
            context: Context,
            action: String,
        ) {
            val intent = Intent(context, SharingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

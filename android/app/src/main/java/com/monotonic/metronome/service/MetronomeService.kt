package com.monotonic.metronome.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.monotonic.metronome.MainActivity
import com.monotonic.metronome.MonotonicApplication
import com.monotonic.metronome.R
import com.monotonic.metronome.session.MetronomeSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service keeping audio playback, SNTP sync, room WebSocket persistence,
 * CPU wake lock, and low-latency Wi-Fi lock alive when the UI is backgrounded or
 * the screen turns off.
 */
class MetronomeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var sessionController: MetronomeSessionController
    private lateinit var notificationManager: NotificationManager
    private lateinit var lockCoordinator: ServiceLockCoordinator

    private var stateObserverJob: Job? = null
    private var isInForeground = false
    private var lastPostedModel: NotificationDisplayModel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sessionController = MonotonicApplication.from(this).sessionController
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cpuWakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        )?.apply {
            setReferenceCounted(false)
        }
        val managedWakeLock = cpuWakeLock?.let { rawLock ->
            object : ManagedLock {
                override val isHeld: Boolean
                    get() = rawLock.isHeld

                override fun acquire() {
                    rawLock.acquire()
                }

                override fun release() {
                    if (rawLock.isHeld) {
                        rawLock.release()
                    }
                }
            }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val rawWifiLock = wifiManager?.createWifiLock(wifiMode, WIFI_LOCK_TAG)?.apply {
            setReferenceCounted(false)
        }
        val managedWifiLock = rawWifiLock?.let { rawLock ->
            object : ManagedLock {
                override val isHeld: Boolean
                    get() = rawLock.isHeld

                override fun acquire() {
                    rawLock.acquire()
                }

                override fun release() {
                    if (rawLock.isHeld) {
                        rawLock.release()
                    }
                }
            }
        }

        lockCoordinator = ServiceLockCoordinator(
            wakeLock = managedWakeLock,
            wifiLock = managedWifiLock
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentState = sessionController.uiState.value
        val initialDisplay = MetronomeNotificationFormatter.format(currentState)

        // Promote to foreground immediately to satisfy Android's startForegroundService timeout
        if (!isInForeground) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(initialDisplay),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            isInForeground = true
            isActiveForeground = true
            lastPostedModel = initialDisplay
        }

        when (intent?.action) {
            ACTION_TOGGLE_PLAY_STOP -> sessionController.togglePlayStop()
            ACTION_LEAVE_ROOM -> sessionController.leaveRoom()
            ACTION_CLOSE -> sessionController.stopAndDismissFromNotification()
            ACTION_REFRESH_NOTIFICATION -> postNotification(MetronomeNotificationFormatter.format(sessionController.uiState.value))
        }

        ensureStateObserver()

        val updatedState = sessionController.uiState.value
        val resourceState = MetronomeServicePolicy.evaluate(MetronomeServicePolicy.fromUiState(updatedState))
        lockCoordinator.apply(resourceState)
        if (!resourceState.keepForegroundService) {
            stopServiceCleanly()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isActiveForeground = false
        isInForeground = false
        stateObserverJob?.cancel()
        stateObserverJob = null
        serviceScope.cancel()
        lockCoordinator.releaseAll()
        super.onDestroy()
    }

    private fun ensureStateObserver() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            sessionController.uiState
                .map { state ->
                    MetronomeServicePolicy.fromUiState(state) to MetronomeNotificationFormatter.format(state)
                }
                .distinctUntilChanged()
                .collect { (policySnapshot, displayModel) ->
                    val resourceState = MetronomeServicePolicy.evaluate(policySnapshot)
                    lockCoordinator.apply(resourceState)
                    if (!resourceState.keepForegroundService) {
                        stopServiceCleanly()
                    } else if (displayModel != lastPostedModel) {
                        postNotification(displayModel)
                    }
                }
        }
    }

    private fun stopServiceCleanly() {
        isActiveForeground = false
        isInForeground = false
        lastPostedModel = null
        stateObserverJob?.cancel()
        stateObserverJob = null
        lockCoordinator.releaseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Metronome Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps metronome timing and room sync active in the background"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun postNotification(model: NotificationDisplayModel) {
        lastPostedModel = model
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(model))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS declined by user; foreground service continues safely
        }
    }

    private fun buildNotification(model: NotificationDisplayModel): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = when (model.primaryAction) {
            NotificationPrimaryAction.PLAY -> "Play"
            NotificationPrimaryAction.STOP -> "Stop"
        }
        val togglePendingIntent = serviceActionPendingIntent(
            action = ACTION_TOGGLE_PLAY_STOP,
            requestCode = REQUEST_CODE_TOGGLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monotonic)
            .setContentTitle(model.title)
            .setContentText(model.subtitle)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, toggleLabel, togglePendingIntent)

        when (model.secondaryAction) {
            NotificationSecondaryAction.LEAVE_ROOM -> {
                builder.addAction(
                    0,
                    "Leave Room",
                    serviceActionPendingIntent(ACTION_LEAVE_ROOM, REQUEST_CODE_LEAVE)
                )
            }

            NotificationSecondaryAction.CLOSE -> {
                builder.addAction(
                    0,
                    "Close",
                    serviceActionPendingIntent(ACTION_CLOSE, REQUEST_CODE_CLOSE)
                )
            }

            null -> Unit
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MetronomeService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "monotonic_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.monotonic.metronome.action.START"
        const val ACTION_TOGGLE_PLAY_STOP = "com.monotonic.metronome.action.TOGGLE_PLAY_STOP"
        const val ACTION_LEAVE_ROOM = "com.monotonic.metronome.action.LEAVE_ROOM"
        const val ACTION_CLOSE = "com.monotonic.metronome.action.CLOSE"
        const val ACTION_REFRESH_NOTIFICATION = "com.monotonic.metronome.action.REFRESH_NOTIFICATION"

        private const val REQUEST_CODE_OPEN_APP = 100
        private const val REQUEST_CODE_TOGGLE = 101
        private const val REQUEST_CODE_LEAVE = 102
        private const val REQUEST_CODE_CLOSE = 103

        private const val WAKE_LOCK_TAG = "Monotonic::MetronomeWakeLock"
        private const val WIFI_LOCK_TAG = "Monotonic::SyncWifiLock"

        @Volatile
        var isActiveForeground: Boolean = false
            private set

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MetronomeService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
                // Safe guard if system temporarily restricts FGS launch while backgrounded
            }
        }

        fun refreshNotificationIfRunning(context: Context) {
            if (!isActiveForeground) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MetronomeService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            try {
                appCtx.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}

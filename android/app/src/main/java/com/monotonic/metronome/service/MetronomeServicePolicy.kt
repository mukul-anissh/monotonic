package com.monotonic.metronome.service

import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.TimeSignaturePreset
import kotlin.math.roundToInt

data class ServicePolicySnapshot(
    val isPlaying: Boolean,
    val isInRoom: Boolean,
    val isReconnecting: Boolean
)

data class ServiceResourceState(
    val keepForegroundService: Boolean,
    val holdWakeLock: Boolean,
    val holdWifiLock: Boolean
)

/**
 * Pure policy determining when [MetronomeService] must run in the foreground
 * and which power/Wi-Fi locks must be held.
 */
object MetronomeServicePolicy {
    fun evaluate(snapshot: ServicePolicySnapshot): ServiceResourceState {
        val roomActive = snapshot.isInRoom || snapshot.isReconnecting
        val keepForeground = snapshot.isPlaying || roomActive
        return ServiceResourceState(
            keepForegroundService = keepForeground,
            holdWakeLock = keepForeground,
            holdWifiLock = roomActive
        )
    }

    fun fromUiState(state: AppUiState): ServicePolicySnapshot {
        return ServicePolicySnapshot(
            isPlaying = state.isPlaying,
            isInRoom = state.isInRoom,
            isReconnecting = state.connectionStatus == ConnectionStatus.RECONNECTING
        )
    }
}

interface ManagedLock {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Coordinates idempotent acquire/release transitions for CPU WakeLock and
 * low-latency WiFiLock based on [ServiceResourceState].
 */
class ServiceLockCoordinator(
    private val wakeLock: ManagedLock?,
    private val wifiLock: ManagedLock?
) {
    @Synchronized
    fun apply(desired: ServiceResourceState) {
        syncLock(wakeLock, desired.holdWakeLock)
        syncLock(wifiLock, desired.holdWifiLock)
    }

    @Synchronized
    fun releaseAll() {
        syncLock(wakeLock, shouldHold = false)
        syncLock(wifiLock, shouldHold = false)
    }

    private fun syncLock(lock: ManagedLock?, shouldHold: Boolean) {
        if (lock == null) return
        try {
            if (shouldHold && !lock.isHeld) {
                lock.acquire()
            } else if (!shouldHold && lock.isHeld) {
                lock.release()
            }
        } catch (_: Exception) {
        }
    }
}

enum class NotificationPrimaryAction {
    PLAY,
    STOP
}

enum class NotificationSecondaryAction {
    LEAVE_ROOM,
    CLOSE
}

data class NotificationDisplayModel(
    val title: String,
    val subtitle: String,
    val primaryAction: NotificationPrimaryAction,
    val secondaryAction: NotificationSecondaryAction?
)

/**
 * Pure formatter for the ongoing playback/room status notification.
 */
object MetronomeNotificationFormatter {
    fun format(state: AppUiState): NotificationDisplayModel {
        val bpmInt = state.bpm.roundToInt()
        val meterLabel = TimeSignaturePreset.PRESETS
            .firstOrNull { it.beatsPerBar == state.beatsPerBar }
            ?.label
            ?: "${state.beatsPerBar}/4"
        val room = state.activeRoom

        val title = if (room != null) {
            "Room ${room.roomCode} • $bpmInt BPM"
        } else {
            "$bpmInt BPM • $meterLabel"
        }

        val subtitle = if (room != null) {
            val peersLabel = if (room.participantCount == 1) "1 peer" else "${room.participantCount} peers"
            when {
                state.connectionStatus == ConnectionStatus.RECONNECTING ->
                    "Reconnecting… (${state.reconnectAttempt.coerceAtLeast(1)}/${state.maxReconnectAttempts})"
                !state.clockSync.isSynced ->
                    "Syncing clock • $peersLabel"
                state.isPlaying ->
                    "Synced • $peersLabel"
                else ->
                    "Connected • Waiting for start"
            }
        } else {
            if (state.isPlaying) "Playing • Solo" else "Paused • Solo"
        }

        val primaryAction = if (state.isPlaying) {
            NotificationPrimaryAction.STOP
        } else {
            NotificationPrimaryAction.PLAY
        }

        val secondaryAction = when {
            room != null -> NotificationSecondaryAction.LEAVE_ROOM
            !state.isPlaying -> NotificationSecondaryAction.CLOSE
            else -> null
        }

        return NotificationDisplayModel(
            title = title,
            subtitle = subtitle,
            primaryAction = primaryAction,
            secondaryAction = secondaryAction
        )
    }
}

package com.monotonic.metronome.service

import android.media.AudioManager
import com.monotonic.metronome.audio.AudioFocusAction
import com.monotonic.metronome.audio.AudioFocusPolicy
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.sync.ClockSyncState
import com.monotonic.metronome.sync.SoloTransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeServicePolicyTest {

    private class FakeManagedLock : ManagedLock {
        override var isHeld: Boolean = false
            private set
        var acquireCalls: Int = 0
            private set
        var releaseCalls: Int = 0
            private set

        override fun acquire() {
            acquireCalls++
            isHeld = true
        }

        override fun release() {
            releaseCalls++
            isHeld = false
        }
    }

    @Test
    fun testServicePolicyEvaluationTruthTable() {
        // Idle solo: no foreground service, no locks
        assertEquals(
            ServiceResourceState(
                keepForegroundService = false,
                holdWakeLock = false,
                holdWifiLock = false
            ),
            MetronomeServicePolicy.evaluate(
                ServicePolicySnapshot(isPlaying = false, isInRoom = false, isReconnecting = false)
            )
        )

        // Playing solo: foreground service + wake lock, no WiFi lock
        assertEquals(
            ServiceResourceState(
                keepForegroundService = true,
                holdWakeLock = true,
                holdWifiLock = false
            ),
            MetronomeServicePolicy.evaluate(
                ServicePolicySnapshot(isPlaying = true, isInRoom = false, isReconnecting = false)
            )
        )

        // Connected to room while paused: foreground service + wake lock + low-latency WiFi lock
        assertEquals(
            ServiceResourceState(
                keepForegroundService = true,
                holdWakeLock = true,
                holdWifiLock = true
            ),
            MetronomeServicePolicy.evaluate(
                ServicePolicySnapshot(isPlaying = false, isInRoom = true, isReconnecting = false)
            )
        )

        // Connected to room while playing: foreground service + wake lock + WiFi lock
        assertEquals(
            ServiceResourceState(
                keepForegroundService = true,
                holdWakeLock = true,
                holdWifiLock = true
            ),
            MetronomeServicePolicy.evaluate(
                ServicePolicySnapshot(isPlaying = true, isInRoom = true, isReconnecting = false)
            )
        )

        // Reconnecting to room: foreground service + wake lock + WiFi lock
        assertEquals(
            ServiceResourceState(
                keepForegroundService = true,
                holdWakeLock = true,
                holdWifiLock = true
            ),
            MetronomeServicePolicy.evaluate(
                ServicePolicySnapshot(isPlaying = false, isInRoom = false, isReconnecting = true)
            )
        )
    }

    @Test
    fun testLockCoordinatorTransitionsAndReleaseAll() {
        val wakeLock = FakeManagedLock()
        val wifiLock = FakeManagedLock()
        val coordinator = ServiceLockCoordinator(wakeLock = wakeLock, wifiLock = wifiLock)

        // Idle -> nothing acquired
        coordinator.apply(
            ServiceResourceState(keepForegroundService = false, holdWakeLock = false, holdWifiLock = false)
        )
        assertFalse(wakeLock.isHeld)
        assertFalse(wifiLock.isHeld)
        assertEquals(0, wakeLock.acquireCalls)
        assertEquals(0, wifiLock.acquireCalls)

        // Playing solo -> wake acquired once
        coordinator.apply(
            ServiceResourceState(keepForegroundService = true, holdWakeLock = true, holdWifiLock = false)
        )
        assertTrue(wakeLock.isHeld)
        assertFalse(wifiLock.isHeld)
        assertEquals(1, wakeLock.acquireCalls)
        assertEquals(0, wifiLock.acquireCalls)

        // Re-applying same state is idempotent
        coordinator.apply(
            ServiceResourceState(keepForegroundService = true, holdWakeLock = true, holdWifiLock = false)
        )
        assertEquals(1, wakeLock.acquireCalls)

        // Enter room -> wake stays held, wifi acquired
        coordinator.apply(
            ServiceResourceState(keepForegroundService = true, holdWakeLock = true, holdWifiLock = true)
        )
        assertTrue(wakeLock.isHeld)
        assertTrue(wifiLock.isHeld)
        assertEquals(1, wakeLock.acquireCalls)
        assertEquals(1, wifiLock.acquireCalls)

        // Leave room while stopped -> both released
        coordinator.apply(
            ServiceResourceState(keepForegroundService = false, holdWakeLock = false, holdWifiLock = false)
        )
        assertFalse(wakeLock.isHeld)
        assertFalse(wifiLock.isHeld)
        assertEquals(1, wakeLock.releaseCalls)
        assertEquals(1, wifiLock.releaseCalls)

        // Reacquire both then verify releaseAll() clears both
        coordinator.apply(
            ServiceResourceState(keepForegroundService = true, holdWakeLock = true, holdWifiLock = true)
        )
        assertTrue(wakeLock.isHeld)
        assertTrue(wifiLock.isHeld)
        coordinator.releaseAll()
        assertFalse(wakeLock.isHeld)
        assertFalse(wifiLock.isHeld)
        assertEquals(2, wakeLock.releaseCalls)
        assertEquals(2, wifiLock.releaseCalls)
    }

    @Test
    fun testNotificationFormattingAcrossStates() {
        val soloPlaying = MetronomeNotificationFormatter.format(
            AppUiState(
                soloState = SoloTransportState(isPlaying = true, bpm = 127.0, beatsPerBar = 4)
            )
        )
        assertEquals("127 BPM • 4/4", soloPlaying.title)
        assertEquals("Playing • Solo", soloPlaying.subtitle)
        assertEquals(NotificationPrimaryAction.STOP, soloPlaying.primaryAction)
        assertNull(soloPlaying.secondaryAction)

        val soloPaused = MetronomeNotificationFormatter.format(
            AppUiState(
                soloState = SoloTransportState(isPlaying = false, bpm = 120.0, beatsPerBar = 3)
            )
        )
        assertEquals("120 BPM • 3/4", soloPaused.title)
        assertEquals("Paused • Solo", soloPaused.subtitle)
        assertEquals(NotificationPrimaryAction.PLAY, soloPaused.primaryAction)
        assertEquals(NotificationSecondaryAction.CLOSE, soloPaused.secondaryAction)

        val roomSyncedPlaying = MetronomeNotificationFormatter.format(
            AppUiState(
                activeRoom = RoomSnapshot(
                    roomCode = "ABC123",
                    participantCount = 3,
                    isPlaying = true,
                    bpm = 127.0,
                    beatsPerBar = 4
                ),
                connectionStatus = ConnectionStatus.CONNECTED,
                clockSync = ClockSyncState(isSynced = true)
            )
        )
        assertEquals("Room ABC123 • 127 BPM", roomSyncedPlaying.title)
        assertEquals("Synced • 3 peers", roomSyncedPlaying.subtitle)
        assertEquals(NotificationPrimaryAction.STOP, roomSyncedPlaying.primaryAction)
        assertEquals(NotificationSecondaryAction.LEAVE_ROOM, roomSyncedPlaying.secondaryAction)

        val roomWaiting = MetronomeNotificationFormatter.format(
            AppUiState(
                activeRoom = RoomSnapshot(
                    roomCode = "ABC123",
                    participantCount = 2,
                    isPlaying = false,
                    bpm = 127.0,
                    beatsPerBar = 4
                ),
                connectionStatus = ConnectionStatus.CONNECTED,
                clockSync = ClockSyncState(isSynced = true)
            )
        )
        assertEquals("Room ABC123 • 127 BPM", roomWaiting.title)
        assertEquals("Connected • Waiting for start", roomWaiting.subtitle)
        assertEquals(NotificationPrimaryAction.PLAY, roomWaiting.primaryAction)
        assertEquals(NotificationSecondaryAction.LEAVE_ROOM, roomWaiting.secondaryAction)

        val roomSyncing = MetronomeNotificationFormatter.format(
            AppUiState(
                activeRoom = RoomSnapshot(
                    roomCode = "ABC123",
                    participantCount = 1,
                    isPlaying = false,
                    bpm = 127.0,
                    beatsPerBar = 4
                ),
                connectionStatus = ConnectionStatus.CONNECTED,
                clockSync = ClockSyncState(isSynced = false)
            )
        )
        assertEquals("Syncing clock • 1 peer", roomSyncing.subtitle)

        val roomReconnecting = MetronomeNotificationFormatter.format(
            AppUiState(
                activeRoom = RoomSnapshot(
                    roomCode = "ABC123",
                    participantCount = 3,
                    isPlaying = true,
                    bpm = 127.0,
                    beatsPerBar = 4
                ),
                connectionStatus = ConnectionStatus.RECONNECTING,
                reconnectAttempt = 2,
                maxReconnectAttempts = 5,
                clockSync = ClockSyncState(isSynced = true)
            )
        )
        assertEquals("Reconnecting… (2/5)", roomReconnecting.subtitle)
        assertEquals(NotificationSecondaryAction.LEAVE_ROOM, roomReconnecting.secondaryAction)
    }

    @Test
    fun testAudioFocusActionMapping() {
        assertEquals(
            AudioFocusAction.RESTORE_FULL_GAIN,
            AudioFocusPolicy.evaluate(AudioManager.AUDIOFOCUS_GAIN)
        )
        assertEquals(
            AudioFocusAction.DUCK_GAIN,
            AudioFocusPolicy.evaluate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        )
        assertEquals(
            AudioFocusAction.MUTE_TRANSIENT,
            AudioFocusPolicy.evaluate(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
        assertEquals(
            AudioFocusAction.STOP_PLAYBACK_PERMANENT,
            AudioFocusPolicy.evaluate(AudioManager.AUDIOFOCUS_LOSS)
        )
        assertEquals(
            AudioFocusAction.IGNORE,
            AudioFocusPolicy.evaluate(-99)
        )
    }
}

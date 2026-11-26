package com.monotonic.metronome.model

import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.sync.ClockSyncState
import com.monotonic.metronome.sync.SoloTransportState

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class AudioRouteStatus {
    WIRED_READY,
    BLUETOOTH_WARNING,
    SPEAKER_WARNING
}

enum class SyncQuality {
    OFFLINE,
    SYNCING,
    GOOD,
    FAIR,
    POOR
}

enum class ClickSoundOption(val id: Int, val label: String) {
    CLASSIC(0, "Classic"),
    DIGITAL_CLICK(1, "Digital"),
    WOODBLOCK(2, "Woodblock"),
    SOFT_TICK(3, "Soft Tick");

    companion object {
        fun fromId(id: Int): ClickSoundOption =
            entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

data class AudioRouteState(
    val status: AudioRouteStatus = AudioRouteStatus.SPEAKER_WARNING,
    val deviceLabel: String = "Phone Speaker"
)

data class TimeSignaturePreset(
    val label: String,
    val beatsPerBar: Int
) {
    companion object {
        val PRESETS = listOf(
            TimeSignaturePreset("4/4", 4),
            TimeSignaturePreset("3/4", 3),
            TimeSignaturePreset("6/8", 6),
            TimeSignaturePreset("5/4", 5),
            TimeSignaturePreset("7/8", 7),
            TimeSignaturePreset("9/8", 9),
            TimeSignaturePreset("12/8", 12),
            TimeSignaturePreset("2/4", 2)
        )
    }
}

data class RoomSnapshot(
    val roomCode: String,
    val participantCount: Int = 1,
    val isPlaying: Boolean = false,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val startServerTimeNs: Long = 0L,
    val stateVersion: Long = 0L
)

data class AudioDiagnostics(
    val hardwareLatencyMs: Double = 0.0,
    val sampleRateHz: Int = 48000,
    val isExclusiveMode: Boolean = false,
    val audioApiName: String = "Auto"
)

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val path: String = "/ws",
    val serviceName: String = name
) {
    val hostPortKey: String
        get() = "$host:$port"

    val wsUrl: String
        get() {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return "ws://$host:$port$cleanPath"
        }
}

data class AppUiState(
    val soloState: SoloTransportState = SoloTransportState(),
    val activeRoom: RoomSnapshot? = null,
    val localSubdivision: Int = 1,
    val accentLevels: List<Int> = AccentPattern.defaultPattern(4),
    val masterVolume: Float = 0.85f,
    val clickSound: ClickSoundOption = ClickSoundOption.CLASSIC,
    val visualBeatFlash: Boolean = true,
    val manualOffsetMs: Int = 0,
    val serverUrl: String = "ws://192.168.1.100:8000/ws",
    val hasExplicitSavedServer: Boolean = false,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isDiscoveringServers: Boolean = false,
    val activeServerName: String? = null,
    val isServerEditorExpanded: Boolean = false,
    val roomCodeInput: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isConnectingAction: Boolean = false,
    val roomSheetError: String? = null,
    val showRoomSheet: Boolean = false,
    val showShareQrSheet: Boolean = false,
    val showQrScanner: Boolean = false,
    val cameraPermissionDeniedHint: Boolean = false,
    val showSettings: Boolean = false,
    val snackbarMessage: String? = null,
    val clockSync: ClockSyncState = ClockSyncState(),
    val audioRoute: AudioRouteState = AudioRouteState(),
    val audioDiagnostics: AudioDiagnostics = AudioDiagnostics(),
    val localScheduledStartNs: Long = 0L
) {
    val isInRoom: Boolean
        get() = activeRoom != null

    val isPlaying: Boolean
        get() = activeRoom?.isPlaying ?: soloState.isPlaying

    val bpm: Double
        get() = activeRoom?.bpm ?: soloState.bpm

    val beatsPerBar: Int
        get() = activeRoom?.beatsPerBar ?: soloState.beatsPerBar

    val subdivision: Int
        get() = localSubdivision

    val syncQuality: SyncQuality
        get() {
            if (activeRoom == null) return SyncQuality.OFFLINE
            if (!clockSync.isSynced) return SyncQuality.SYNCING
            return when {
                clockSync.jitterMs <= 3.0 && clockSync.minRttMs <= 30.0 -> SyncQuality.GOOD
                clockSync.jitterMs <= 8.0 && clockSync.minRttMs <= 80.0 -> SyncQuality.FAIR
                else -> SyncQuality.POOR
            }
        }
}

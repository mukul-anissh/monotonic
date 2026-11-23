package com.monotonic.metronome.model

import com.monotonic.metronome.sync.ClockSyncState

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
            TimeSignaturePreset("7/8", 7)
        )
    }
}

data class RoomSnapshot(
    val roomCode: String,
    val participantCount: Int = 1,
    val isPlaying: Boolean = false,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val subdivision: Int = 1,
    val startServerTimeNs: Long = 0L,
    val stateVersion: Long = 0L
)

data class AudioDiagnostics(
    val hardwareLatencyMs: Double = 0.0,
    val sampleRateHz: Int = 48000,
    val isExclusiveMode: Boolean = false,
    val audioApiName: String = "Auto"
)

data class AppUiState(
    val serverUrl: String = "ws://192.168.1.100:8000/ws",
    val roomCodeInput: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isConnectingAction: Boolean = false,
    val errorMessage: String? = null,
    val activeRoom: RoomSnapshot? = null,
    val clockSync: ClockSyncState = ClockSyncState(),
    val manualOffsetMs: Int = 0,
    val audioRoute: AudioRouteState = AudioRouteState(),
    val audioDiagnostics: AudioDiagnostics = AudioDiagnostics(),
    val localScheduledStartNs: Long = 0L
)

package com.monotonic.metronome.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monotonic.metronome.audio.AudioFocusController
import com.monotonic.metronome.audio.AudioRouteMonitor
import com.monotonic.metronome.audio.NativeMetronomeEngine
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.AudioDiagnostics
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.model.RoomStateEvent
import com.monotonic.metronome.network.RoomWebSocketClient
import com.monotonic.metronome.sync.ClockSyncManager
import com.monotonic.metronome.sync.ClockSyncMath
import com.monotonic.metronome.sync.MonotonicClock
import com.monotonic.metronome.sync.SystemNanoTimeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    private val clock: MonotonicClock = SystemNanoTimeClock
    private val preferences = PreferencesRepository(application)
    private val nativeEngine = NativeMetronomeEngine()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    @Volatile
    private var currentVolumeScale: Float = AudioFocusController.DEFAULT_VOLUME

    private val tapTimestampsNs = ArrayDeque<Long>()
    private var diagnosticsJob: Job? = null
    private var audioStartupJob: Job? = null

    private lateinit var webSocketClient: RoomWebSocketClient

    private val clockSyncManager = ClockSyncManager(
        clock = clock,
        sendPing = { seq, t1 -> webSocketClient.sendSyncPing(seq, t1) }
    )

    private val audioRouteMonitor = AudioRouteMonitor(application) {
        if (_uiState.value.activeRoom != null) {
            viewModelScope.launch(Dispatchers.IO) {
                nativeEngine.restartStream()
                refreshAudioDiagnostics()
            }
        }
    }

    private val audioFocusController = AudioFocusController(application) { newVolume ->
        currentVolumeScale = newVolume
        pushCurrentStateToNative()
    }

    init {
        webSocketClient = RoomWebSocketClient(
            clock = clock,
            onSyncPong = { seq, t1, t2, t3, t4 ->
                clockSyncManager.onSyncPong(seq, t1, t2, t3, t4)
            },
            onRoomJoined = { event ->
                handleRoomJoined(event)
            },
            onRoomState = { event ->
                handleRoomState(event)
            },
            onServerError = { err ->
                _uiState.update {
                    it.copy(
                        isConnectingAction = false,
                        errorMessage = err.message
                    )
                }
            },
            onConnectionStatusChanged = { status, errMsg ->
                handleConnectionChanged(status, errMsg)
            }
        )

        audioRouteMonitor.start()

        viewModelScope.launch {
            val savedUrl = preferences.serverUrlFlow.first()
            val savedOffsetMs = preferences.manualOffsetMsFlow.first()
            updateStateAndPushTransport { state ->
                state.copy(
                    serverUrl = savedUrl,
                    manualOffsetMs = savedOffsetMs
                )
            }
        }

        viewModelScope.launch {
            clockSyncManager.state.collect { syncState ->
                updateStateAndPushTransport { state ->
                    state.copy(clockSync = syncState)
                }
            }
        }

        viewModelScope.launch {
            audioRouteMonitor.state.collect { routeState ->
                _uiState.update { it.copy(audioRoute = routeState) }
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, errorMessage = null) }
    }

    fun onRoomCodeInputChanged(code: String) {
        _uiState.update { it.copy(roomCodeInput = code, errorMessage = null) }
    }

    fun createRoom() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            preferences.saveServerUrl(url)
        }
        _uiState.update { it.copy(isConnectingAction = true, errorMessage = null) }
        webSocketClient.connectAndExecute(url) {
            webSocketClient.createRoom()
        }
    }

    fun joinRoom() {
        val url = _uiState.value.serverUrl.trim()
        val code = _uiState.value.roomCodeInput.trim().uppercase()
        if (url.isEmpty() || code.length != 6) return
        viewModelScope.launch {
            preferences.saveServerUrl(url)
        }
        _uiState.update { it.copy(isConnectingAction = true, errorMessage = null) }
        webSocketClient.connectAndExecute(url) {
            webSocketClient.joinRoom(code)
        }
    }

    fun leaveRoom() {
        webSocketClient.leaveRoom()
        teardownRoomSession(errorMessage = null)
        webSocketClient.disconnect()
    }

    fun togglePlayStop() {
        val room = _uiState.value.activeRoom ?: return
        if (room.isPlaying) {
            webSocketClient.stop()
        } else {
            webSocketClient.play()
        }
    }

    fun updateBpm(bpm: Double) {
        val clamped = bpm.coerceIn(30.0, 300.0)
        webSocketClient.updateTransport(bpm = clamped)
    }

    fun updateBeatsPerBar(beatsPerBar: Int) {
        val clamped = beatsPerBar.coerceIn(2, 12)
        webSocketClient.updateTransport(beatsPerBar = clamped)
    }

    fun updateSubdivision(subdivision: Int) {
        if (subdivision !in listOf(1, 2, 4)) return
        webSocketClient.updateTransport(subdivision = subdivision)
    }

    fun onTapTempo() {
        val nowNs = clock.nanoTime()
        val last = tapTimestampsNs.lastOrNull()
        if (last != null && (nowNs - last) > 2_500_000_000L) {
            tapTimestampsNs.clear()
        }
        tapTimestampsNs.addLast(nowNs)
        while (tapTimestampsNs.size > 7) {
            tapTimestampsNs.removeFirst()
        }
        if (tapTimestampsNs.size < 2) return

        var sumIntervalsNs = 0L
        var count = 0
        val list = tapTimestampsNs.toList()
        for (i in 1 until list.size) {
            val delta = list[i] - list[i - 1]
            if (delta > 150_000_000L) {
                sumIntervalsNs += delta
                count++
            }
        }
        if (count == 0) return

        val avgIntervalNs = sumIntervalsNs.toDouble() / count.toDouble()
        val bpm = (60_000_000_000.0 / avgIntervalNs).roundToInt().toDouble().coerceIn(30.0, 300.0)
        updateBpm(bpm)
    }

    fun onManualOffsetChanged(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(
            PreferencesRepository.MIN_MANUAL_OFFSET_MS,
            PreferencesRepository.MAX_MANUAL_OFFSET_MS
        )
        updateStateAndPushTransport { it.copy(manualOffsetMs = clamped) }
        viewModelScope.launch {
            preferences.saveManualOffsetMs(clamped)
        }
    }

    fun resyncNow() {
        clockSyncManager.resyncNow(viewModelScope)
    }

    fun readCurrentBeatIndex(): Int = nativeEngine.getCurrentBeatIndex()

    fun readBeatSequence(): Long = nativeEngine.getBeatSequence()

    private fun handleRoomJoined(event: RoomStateEvent) {
        val snapshot = event.toSnapshot()
        updateStateAndPushTransport { state ->
            state.copy(
                activeRoom = snapshot,
                isConnectingAction = false,
                errorMessage = null
            )
        }

        viewModelScope.launch(Dispatchers.Main) {
            audioFocusController.requestFocus()
            audioStartupJob?.cancel()
            audioStartupJob = viewModelScope.launch(Dispatchers.IO) {
                nativeEngine.startStream()
                if (_uiState.value.activeRoom == null) {
                    nativeEngine.stopStream()
                } else {
                    refreshAudioDiagnostics()
                }
            }
            clockSyncManager.startSession(viewModelScope)
            startDiagnosticsPolling()
        }
    }

    private fun handleRoomState(event: RoomStateEvent) {
        val snapshot = event.toSnapshot()
        updateStateAndPushTransport { state ->
            if (state.activeRoom == null) {
                state
            } else {
                state.copy(activeRoom = snapshot, errorMessage = null)
            }
        }
    }

    private fun handleConnectionChanged(status: ConnectionStatus, errorMsg: String?) {
        if (status == ConnectionStatus.DISCONNECTED) {
            val hadRoom = _uiState.value.activeRoom != null
            if (hadRoom) {
                teardownRoomSession(errorMessage = errorMsg ?: "Disconnected from sync server")
            } else {
                _uiState.update {
                    it.copy(
                        connectionStatus = status,
                        isConnectingAction = false,
                        errorMessage = errorMsg ?: it.errorMessage
                    )
                }
            }
        } else {
            _uiState.update { it.copy(connectionStatus = status) }
        }
    }

    private fun teardownRoomSession(errorMessage: String?) {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        audioStartupJob?.cancel()
        audioStartupJob = null
        clockSyncManager.stop(resetOffset = true)
        nativeEngine.stopStream()
        audioFocusController.abandonFocus()
        tapTimestampsNs.clear()

        updateStateAndPushTransport { state ->
            state.copy(
                activeRoom = null,
                isConnectingAction = false,
                errorMessage = errorMessage,
                localScheduledStartNs = 0L
            )
        }
    }

    private fun startDiagnosticsPolling() {
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            while (isActive && _uiState.value.activeRoom != null) {
                refreshAudioDiagnostics()
                delay(500L)
            }
        }
    }

    private fun refreshAudioDiagnostics() {
        val diag = AudioDiagnostics(
            hardwareLatencyMs = nativeEngine.getMeasuredHardwareLatencyMs(),
            sampleRateHz = nativeEngine.getSampleRate(),
            isExclusiveMode = nativeEngine.isExclusiveSharingMode(),
            audioApiName = nativeEngine.getAudioApiName()
        )
        _uiState.update { it.copy(audioDiagnostics = diag) }
    }

    private fun updateStateAndPushTransport(transform: (AppUiState) -> AppUiState) {
        lateinit var updated: AppUiState
        _uiState.update { prev ->
            val candidate = transform(prev)
            val room = candidate.activeRoom
            val sync = candidate.clockSync
            val localStartNs = if (room != null && room.isPlaying && sync.isSynced) {
                ClockSyncMath.serverToLocalTimeNs(room.startServerTimeNs, sync.offsetNs)
            } else {
                0L
            }
            updated = candidate.copy(localScheduledStartNs = localStartNs)
            updated
        }
        pushSnapshotToNative(updated)
    }

    private fun pushCurrentStateToNative() {
        pushSnapshotToNative(_uiState.value)
    }

    private fun pushSnapshotToNative(state: AppUiState) {
        val room = state.activeRoom
        val sync = state.clockSync
        val shouldPlay = room != null && room.isPlaying && sync.isSynced
        val localStartNs = if (shouldPlay) {
            ClockSyncMath.serverToLocalTimeNs(room!!.startServerTimeNs, sync.offsetNs)
        } else {
            0L
        }
        nativeEngine.updateTransport(
            isPlaying = shouldPlay,
            localStartTimeNs = localStartNs,
            bpm = room?.bpm ?: 120.0,
            beatsPerBar = room?.beatsPerBar ?: 4,
            subdivision = room?.subdivision ?: 1,
            manualOffsetNs = state.manualOffsetMs.toLong() * 1_000_000L,
            volume = currentVolumeScale,
            stateVersion = room?.stateVersion ?: 0L
        )
    }

    private fun RoomStateEvent.toSnapshot(): RoomSnapshot {
        return RoomSnapshot(
            roomCode = roomCode,
            participantCount = participantCount,
            isPlaying = isPlaying,
            bpm = bpm,
            beatsPerBar = beatsPerBar,
            subdivision = subdivision,
            startServerTimeNs = startServerTimeNs,
            stateVersion = stateVersion
        )
    }

    override fun onCleared() {
        super.onCleared()
        diagnosticsJob?.cancel()
        clockSyncManager.stop(resetOffset = true)
        audioRouteMonitor.stop()
        audioFocusController.abandonFocus()
        nativeEngine.stopStream()
        nativeEngine.destroy()
        webSocketClient.shutdown()
    }
}

package com.monotonic.metronome.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.audio.AudioFocusController
import com.monotonic.metronome.audio.AudioRouteMonitor
import com.monotonic.metronome.audio.NativeMetronomeEngine
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.AudioDiagnostics
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.model.RoomStateEvent
import com.monotonic.metronome.network.RoomWebSocketClient
import com.monotonic.metronome.sync.ClockSyncManager
import com.monotonic.metronome.sync.ClockSyncMath
import com.monotonic.metronome.sync.MonotonicClock
import com.monotonic.metronome.sync.SoloScheduler
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

    private data class ScheduleIdentity(
        val isInRoom: Boolean,
        val sourceVersion: Long,
        val shouldPlay: Boolean,
        val localSubdivision: Int
    )

    private val transportLock = Any()
    private var engineScheduleVersion: Long = 1L
    private var lastScheduleIdentity: ScheduleIdentity? = null
    private var wasEnginePlaying: Boolean = false

    @Volatile
    private var currentFocusGain: Float = AudioFocusController.GAIN_FULL

    private val tapTimestampsNs = ArrayDeque<Long>()
    private var lastRoomNudgeNs: Long = 0L
    private var lastRoomNudgeBpm: Double = 120.0
    private var diagnosticsJob: Job? = null

    private lateinit var webSocketClient: RoomWebSocketClient

    private val clockSyncManager = ClockSyncManager(
        clock = clock,
        sendPing = { seq, t1 -> webSocketClient.sendSyncPing(seq, t1) }
    )

    private val audioRouteMonitor = AudioRouteMonitor(application) {
        viewModelScope.launch(Dispatchers.IO) {
            nativeEngine.restartStream()
            refreshAudioDiagnostics()
        }
    }

    private val audioFocusController = AudioFocusController(application) { gain ->
        currentFocusGain = gain
        synchronized(transportLock) {
            pushSnapshotToNativeLocked(_uiState.value)
        }
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
                        roomSheetError = if (it.showRoomSheet) err.message else it.roomSheetError,
                        snackbarMessage = if (!it.showRoomSheet) err.message else it.snackbarMessage
                    )
                }
            },
            onConnectionStatusChanged = { status, errMsg ->
                handleConnectionChanged(status, errMsg)
            }
        )

        audioRouteMonitor.start()

        // Keep native C++ Oboe stream open immediately so Solo Mode plays with zero startup lag
        viewModelScope.launch(Dispatchers.IO) {
            nativeEngine.startStream()
            refreshAudioDiagnostics()
        }
        startDiagnosticsPolling()

        viewModelScope.launch {
            val prefs = preferences.preferencesFlow.first()
            val adaptedAccents = AccentPattern.deserialize(prefs.accentPatternRaw, prefs.soloBeatsPerBar)
            nativeEngine.setSoundProfile(prefs.clickSound.id)
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(adaptedAccents))

            updateStateAndPushTransport { state ->
                val updatedSolo = state.soloState.copy(
                    bpm = prefs.soloBpm,
                    beatsPerBar = prefs.soloBeatsPerBar,
                    subdivision = prefs.soloSubdivision
                )
                state.copy(
                    serverUrl = prefs.serverUrl,
                    manualOffsetMs = prefs.manualOffsetMs,
                    masterVolume = prefs.masterVolume,
                    clickSound = prefs.clickSound,
                    visualBeatFlash = prefs.visualBeatFlash,
                    localSubdivision = prefs.soloSubdivision,
                    soloState = updatedSolo,
                    accentLevels = adaptedAccents
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

    fun openRoomSheet() {
        _uiState.update { it.copy(showRoomSheet = true, roomSheetError = null) }
    }

    fun dismissRoomSheet() {
        _uiState.update {
            it.copy(showRoomSheet = false, isConnectingAction = false, roomSheetError = null)
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(showSettings = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, roomSheetError = null) }
        viewModelScope.launch {
            preferences.saveServerUrl(url)
        }
    }

    fun onRoomCodeInputChanged(code: String) {
        _uiState.update { it.copy(roomCodeInput = code, roomSheetError = null) }
    }

    fun createRoom() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            preferences.saveServerUrl(url)
        }
        _uiState.update { it.copy(isConnectingAction = true, roomSheetError = null) }
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
        _uiState.update { it.copy(isConnectingAction = true, roomSheetError = null) }
        webSocketClient.connectAndExecute(url) {
            webSocketClient.joinRoom(code)
        }
    }

    fun leaveRoom() {
        if (!_uiState.value.isInRoom) return
        webSocketClient.leaveRoom()
        exitRoomToSoloMode(snackbarMessage = "Left room — Solo Mode")
        webSocketClient.disconnect()
    }

    fun togglePlayStop() {
        val state = _uiState.value
        val room = state.activeRoom
        if (room != null) {
            if (room.isPlaying) {
                webSocketClient.stop()
            } else {
                webSocketClient.play()
            }
        } else {
            val nowNs = clock.nanoTime()
            updateStateAndPushTransport { prev ->
                val nextSolo = if (prev.soloState.isPlaying) {
                    SoloScheduler.stop(prev.soloState)
                } else {
                    SoloScheduler.play(prev.soloState, nowNs)
                }
                prev.copy(soloState = nextSolo)
            }
        }
    }

    fun updateBpm(bpm: Double) {
        val clamped = (bpm.coerceIn(SoloScheduler.MIN_BPM, SoloScheduler.MAX_BPM) * 100.0)
            .roundToInt() / 100.0
        val state = _uiState.value
        if (state.isInRoom) {
            lastRoomNudgeNs = clock.nanoTime()
            lastRoomNudgeBpm = clamped
            webSocketClient.updateTransport(bpm = clamped)
        } else {
            val nowNs = clock.nanoTime()
            var savedBpm = clamped
            var savedBpb = state.beatsPerBar
            var savedSub = state.subdivision
            updateStateAndPushTransport { prev ->
                val nextSolo = SoloScheduler.updateTransport(
                    state = prev.soloState,
                    nowNs = nowNs,
                    bpm = clamped
                )
                savedBpm = nextSolo.bpm
                savedBpb = nextSolo.beatsPerBar
                savedSub = nextSolo.subdivision
                prev.copy(soloState = nextSolo)
            }
            viewModelScope.launch {
                preferences.saveSoloTransport(savedBpm, savedBpb, savedSub)
            }
        }
    }

    fun nudgeBpm(delta: Double) {
        val state = _uiState.value
        val nowNs = clock.nanoTime()
        val baseBpm = if (state.isInRoom && (nowNs - lastRoomNudgeNs) < 600_000_000L) {
            lastRoomNudgeBpm
        } else {
            state.bpm
        }
        val target = (baseBpm + delta).roundToInt().toDouble()
            .coerceIn(SoloScheduler.MIN_BPM, SoloScheduler.MAX_BPM)
        updateBpm(target)
    }

    fun updateBeatsPerBar(beatsPerBar: Int) {
        val clamped = beatsPerBar.coerceIn(
            SoloScheduler.MIN_BEATS_PER_BAR,
            SoloScheduler.MAX_BEATS_PER_BAR
        )
        val state = _uiState.value
        if (state.isInRoom) {
            webSocketClient.updateTransport(beatsPerBar = clamped)
        } else {
            val nowNs = clock.nanoTime()
            var adaptedAccents: List<Int> = emptyList()
            var savedBpm = state.bpm
            var savedSub = state.subdivision
            updateStateAndPushTransport { prev ->
                val nextSolo = SoloScheduler.updateTransport(
                    state = prev.soloState,
                    nowNs = nowNs,
                    beatsPerBar = clamped
                )
                adaptedAccents = AccentPattern.adaptPattern(prev.accentLevels, nextSolo.beatsPerBar)
                savedBpm = nextSolo.bpm
                savedSub = nextSolo.subdivision
                prev.copy(soloState = nextSolo, accentLevels = adaptedAccents)
            }
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(adaptedAccents))
            viewModelScope.launch {
                preferences.saveAccentPattern(adaptedAccents)
                preferences.saveSoloTransport(savedBpm, clamped, savedSub)
            }
        }
    }

    fun updateSubdivision(subdivision: Int) {
        if (subdivision !in SoloScheduler.VALID_SUBDIVISIONS) return
        val nowNs = clock.nanoTime()
        updateStateAndPushTransport { prev ->
            if (prev.isInRoom) {
                prev.copy(
                    localSubdivision = subdivision,
                    soloState = prev.soloState.copy(subdivision = subdivision)
                )
            } else {
                val nextSolo = SoloScheduler.updateTransport(
                    state = prev.soloState,
                    nowNs = nowNs,
                    subdivision = subdivision
                )
                prev.copy(
                    localSubdivision = subdivision,
                    soloState = nextSolo
                )
            }
        }
        viewModelScope.launch {
            preferences.saveSubdivision(subdivision)
        }
    }

    fun onTapTempo() {
        val nowNs = clock.nanoTime()
        val last = tapTimestampsNs.lastOrNull()
        if (last != null && (nowNs - last) > 3_200_000_000L) {
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
            if (delta >= 100_000_000L) {
                sumIntervalsNs += delta
                count++
            }
        }
        if (count == 0) return

        val avgIntervalNs = sumIntervalsNs.toDouble() / count.toDouble()
        val bpm = (60_000_000_000.0 / avgIntervalNs).roundToInt().toDouble()
            .coerceIn(SoloScheduler.MIN_BPM, SoloScheduler.MAX_BPM)
        updateBpm(bpm)
    }

    fun cycleBeatAccent(beatIndex: Int) {
        var nextAccents: List<Int> = emptyList()
        _uiState.update { prev ->
            if (beatIndex < 0 || beatIndex >= prev.beatsPerBar) {
                nextAccents = prev.accentLevels
                return@update prev
            }
            val currentList = AccentPattern.adaptPattern(prev.accentLevels, prev.beatsPerBar)
            val mutable = currentList.toMutableList()
            mutable[beatIndex] = AccentPattern.cycleLevel(mutable[beatIndex])
            nextAccents = mutable
            prev.copy(accentLevels = nextAccents)
        }
        if (nextAccents.isNotEmpty()) {
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(nextAccents))
            viewModelScope.launch {
                preferences.saveAccentPattern(nextAccents)
            }
        }
    }

    fun onMasterVolumeChanged(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        updateStateAndPushTransport { it.copy(masterVolume = clamped) }
        viewModelScope.launch {
            preferences.saveMasterVolume(clamped)
        }
    }

    fun onClickSoundSelected(option: ClickSoundOption) {
        nativeEngine.setSoundProfile(option.id)
        _uiState.update { it.copy(clickSound = option) }
        viewModelScope.launch {
            preferences.saveClickSound(option)
        }
    }

    fun onVisualBeatFlashChanged(enabled: Boolean) {
        _uiState.update { it.copy(visualBeatFlash = enabled) }
        viewModelScope.launch {
            preferences.saveVisualBeatFlash(enabled)
        }
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
        if (_uiState.value.isInRoom) {
            clockSyncManager.resyncNow(viewModelScope)
        }
    }

    fun readCurrentBeatIndex(): Int = nativeEngine.getCurrentBeatIndex()

    fun readBeatSequence(): Long = nativeEngine.getBeatSequence()

    private fun handleRoomJoined(event: RoomStateEvent) {
        val snapshot = event.toSnapshot()
        var adaptedAccents: List<Int> = emptyList()

        updateStateAndPushTransport { state ->
            val stoppedSolo = SoloScheduler.stop(state.soloState)
            adaptedAccents = AccentPattern.adaptPattern(state.accentLevels, snapshot.beatsPerBar)
            state.copy(
                soloState = stoppedSolo,
                activeRoom = snapshot,
                accentLevels = adaptedAccents,
                showRoomSheet = false,
                isConnectingAction = false,
                roomSheetError = null,
                snackbarMessage = "Connected to room ${snapshot.roomCode}"
            )
        }
        nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(adaptedAccents))

        viewModelScope.launch {
            preferences.saveAccentPattern(adaptedAccents)
            clockSyncManager.startSession(viewModelScope)
        }
    }

    private fun handleRoomState(event: RoomStateEvent) {
        val snapshot = event.toSnapshot()
        var adaptedAccents: List<Int>? = null

        updateStateAndPushTransport { state ->
            val currentRoom = state.activeRoom ?: return@updateStateAndPushTransport state
            if (currentRoom.roomCode != snapshot.roomCode) {
                return@updateStateAndPushTransport state
            }
            val nextAccents = if (state.accentLevels.size != snapshot.beatsPerBar) {
                AccentPattern.adaptPattern(state.accentLevels, snapshot.beatsPerBar).also {
                    adaptedAccents = it
                }
            } else {
                state.accentLevels
            }
            state.copy(
                activeRoom = snapshot,
                accentLevels = nextAccents,
                roomSheetError = null
            )
        }

        adaptedAccents?.let { updated ->
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(updated))
            viewModelScope.launch {
                preferences.saveAccentPattern(updated)
            }
        }
    }

    private fun handleConnectionChanged(status: ConnectionStatus, errorMsg: String?) {
        if (status == ConnectionStatus.DISCONNECTED) {
            val wasInRoom = _uiState.value.isInRoom
            if (wasInRoom) {
                val msg = if (!errorMsg.isNullOrBlank()) {
                    "Connection lost ($errorMsg) — returned to Solo Mode"
                } else {
                    "Server disconnected — returned to Solo Mode"
                }
                exitRoomToSoloMode(snackbarMessage = msg)
            } else {
                _uiState.update {
                    it.copy(
                        connectionStatus = status,
                        isConnectingAction = false,
                        roomSheetError = errorMsg ?: it.roomSheetError
                    )
                }
            }
        } else {
            _uiState.update { it.copy(connectionStatus = status) }
        }
    }

    private fun exitRoomToSoloMode(snackbarMessage: String) {
        clockSyncManager.stop(resetOffset = true)
        tapTimestampsNs.clear()

        updateStateAndPushTransport { state ->
            val prevRoom = state.activeRoom
            val nextSolo = state.soloState.copy(
                isPlaying = false,
                bpm = prevRoom?.bpm ?: state.soloState.bpm,
                beatsPerBar = prevRoom?.beatsPerBar ?: state.soloState.beatsPerBar,
                subdivision = state.localSubdivision,
                localStartTimeNs = 0L,
                stateVersion = state.soloState.stateVersion + 1L
            )
            state.copy(
                soloState = nextSolo,
                activeRoom = null,
                isConnectingAction = false,
                roomSheetError = null,
                snackbarMessage = snackbarMessage,
                localScheduledStartNs = 0L
            )
        }
    }

    private fun startDiagnosticsPolling() {
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            while (isActive) {
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
        synchronized(transportLock) {
            lateinit var updated: AppUiState
            _uiState.update { prev ->
                val candidate = transform(prev)
                val localStartNs = computeLocalStartTimeNs(candidate)
                updated = candidate.copy(localScheduledStartNs = localStartNs)
                updated
            }
            pushSnapshotToNativeLocked(updated)
        }
    }

    private fun computeLocalStartTimeNs(state: AppUiState): Long {
        val room = state.activeRoom
        return if (room != null) {
            if (room.isPlaying && state.clockSync.isSynced) {
                ClockSyncMath.serverToLocalTimeNs(room.startServerTimeNs, state.clockSync.offsetNs)
            } else {
                0L
            }
        } else {
            if (state.soloState.isPlaying) state.soloState.localStartTimeNs else 0L
        }
    }

    private fun pushSnapshotToNativeLocked(state: AppUiState) {
        val room = state.activeRoom
        val isInRoom = room != null
        val shouldPlay = if (isInRoom) {
            room!!.isPlaying && state.clockSync.isSynced
        } else {
            state.soloState.isPlaying
        }

        if (shouldPlay && !wasEnginePlaying) {
            audioFocusController.requestFocus()
        } else if (!shouldPlay && wasEnginePlaying) {
            audioFocusController.abandonFocus()
        }
        wasEnginePlaying = shouldPlay

        val sourceVersion = if (isInRoom) room!!.stateVersion else state.soloState.stateVersion
        val identity = ScheduleIdentity(
            isInRoom = isInRoom,
            sourceVersion = sourceVersion,
            shouldPlay = shouldPlay,
            localSubdivision = state.localSubdivision
        )
        if (lastScheduleIdentity != identity) {
            lastScheduleIdentity = identity
            engineScheduleVersion += 1L
        }

        val localStartNs = computeLocalStartTimeNs(state)
        val effectiveVolume = (state.masterVolume * currentFocusGain).coerceIn(0f, 1f)

        nativeEngine.updateTransport(
            isPlaying = shouldPlay,
            localStartTimeNs = localStartNs,
            bpm = state.bpm,
            beatsPerBar = state.beatsPerBar,
            subdivision = state.subdivision,
            manualOffsetNs = state.manualOffsetMs.toLong() * 1_000_000L,
            volume = effectiveVolume,
            stateVersion = engineScheduleVersion
        )
    }

    private fun RoomStateEvent.toSnapshot(): RoomSnapshot {
        return RoomSnapshot(
            roomCode = roomCode,
            participantCount = participantCount,
            isPlaying = isPlaying,
            bpm = bpm,
            beatsPerBar = beatsPerBar,
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

package com.monotonic.metronome.session

import android.app.Application
import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.audio.AudioFocusController
import com.monotonic.metronome.audio.AudioRouteMonitor
import com.monotonic.metronome.audio.NativeMetronomeEngine
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.AudioDiagnostics
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.DiscoveredServer
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.model.RoomStateEvent
import com.monotonic.metronome.model.ServerErrorEvent
import com.monotonic.metronome.network.ReconnectTarget
import com.monotonic.metronome.network.RoomReconnectController
import com.monotonic.metronome.network.RoomWebSocketClient
import com.monotonic.metronome.network.ServerSelectionPolicy
import com.monotonic.metronome.network.ServerUrlNormalizer
import com.monotonic.metronome.service.MetronomeService
import com.monotonic.metronome.service.MetronomeServicePolicy
import com.monotonic.metronome.sync.ClockSyncManager
import com.monotonic.metronome.sync.ClockSyncMath
import com.monotonic.metronome.sync.MonotonicClock
import com.monotonic.metronome.sync.SoloScheduler
import com.monotonic.metronome.sync.SystemNanoTimeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Application-scoped owner for the native Oboe metronome engine, [ClockSyncManager],
 * room WebSocket client, [AudioRouteMonitor], [AudioFocusController], and automatic
 * room reconnect logic so active playback and rooms survive Activity backgrounding.
 */
class MetronomeSessionController(application: Application) {

    private val appContext = application.applicationContext
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val clock: MonotonicClock = SystemNanoTimeClock
    private val preferences = PreferencesRepository(appContext)
    private val nativeEngine = NativeMetronomeEngine()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    @Volatile
    private var userManuallyPickedInSession: Boolean = false

    @Volatile
    private var activeRoomUrl: String? = null

    @Volatile
    private var pendingRoomUrl: String? = null

    private val notifPermLock = Any()

    @Volatile
    private var hasRequestedNotifPermission: Boolean = false

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

    private val reconnectController = RoomReconnectController(
        scope = sessionScope,
        onStateChanged = { rc ->
            var latestState: AppUiState? = null
            _uiState.update { prev ->
                val nextStatus = when {
                    rc.isReconnecting -> ConnectionStatus.RECONNECTING
                    prev.connectionStatus == ConnectionStatus.RECONNECTING -> ConnectionStatus.DISCONNECTED
                    else -> prev.connectionStatus
                }
                prev.copy(
                    connectionStatus = nextStatus,
                    reconnectAttempt = rc.attempt,
                    maxReconnectAttempts = rc.maxAttempts
                ).also { latestState = it }
            }
            latestState?.let { syncForegroundService(it) }
        },
        onAttemptConnect = { target ->
            pendingRoomUrl = target.serverUrl
            webSocketClient.connectAndExecute(target.serverUrl) {
                webSocketClient.joinRoom(target.roomCode)
            }
        },
        onReconnectExhausted = { lastError ->
            activeRoomUrl = null
            webSocketClient.disconnect()
            val msg = if (!lastError.isNullOrBlank()) {
                "Connection lost ($lastError) — returned to Solo Mode"
            } else {
                "Server disconnected — returned to Solo Mode"
            }
            exitRoomToSoloMode(snackbarMessage = msg)
        }
    )

    private val audioRouteMonitor = AudioRouteMonitor(appContext) {
        sessionScope.launch(Dispatchers.IO) {
            nativeEngine.restartStream()
            refreshAudioDiagnostics()
        }
    }

    private val audioFocusController = AudioFocusController(
        context = appContext,
        onFocusGainChanged = { gain ->
            currentFocusGain = gain
            synchronized(transportLock) {
                pushSnapshotToNativeLocked(_uiState.value)
            }
        },
        onPermanentFocusLoss = {
            handlePermanentAudioFocusLoss()
        }
    )

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
                handleServerError(err)
            },
            onConnectionStatusChanged = { status, errMsg, isUnexpected ->
                handleConnectionChanged(status, errMsg, isUnexpected)
            }
        )

        audioRouteMonitor.start()

        // Keep native C++ Oboe stream open immediately so Solo Mode plays with zero startup lag
        sessionScope.launch(Dispatchers.IO) {
            nativeEngine.startStream()
            refreshAudioDiagnostics()
        }

        sessionScope.launch {
            val prefs = preferences.preferencesFlow.first()
            synchronized(notifPermLock) {
                if (prefs.hasRequestedNotificationPermission) {
                    hasRequestedNotifPermission = true
                }
            }
            val adaptedAccents = AccentPattern.deserialize(prefs.accentPatternRaw, prefs.soloBeatsPerBar)
            nativeEngine.setSoundProfile(prefs.clickSound.id)
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(adaptedAccents))

            updateStateAndPushTransport { state ->
                val updatedSolo = state.soloState.copy(
                    bpm = prefs.soloBpm,
                    beatsPerBar = prefs.soloBeatsPerBar,
                    subdivision = prefs.soloSubdivision
                )
                val normalizedSavedUrl = ServerUrlNormalizer.normalize(prefs.serverUrl) ?: prefs.serverUrl
                state.copy(
                    serverUrl = normalizedSavedUrl,
                    hasExplicitSavedServer = prefs.hasSavedServerUrl,
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

        sessionScope.launch {
            clockSyncManager.state.collect { syncState ->
                updateStateAndPushTransport { state ->
                    state.copy(clockSync = syncState)
                }
            }
        }

        sessionScope.launch {
            audioRouteMonitor.state.collect { routeState ->
                _uiState.update { it.copy(audioRoute = routeState) }
            }
        }
    }

    fun onUiForegroundChanged(isForeground: Boolean) {
        if (isForeground) {
            sessionScope.launch(Dispatchers.IO) {
                nativeEngine.startStream()
                refreshAudioDiagnostics()
            }
            startDiagnosticsPolling()
        } else {
            diagnosticsJob?.cancel()
            diagnosticsJob = null
        }
    }

    fun consumeShouldRequestNotificationPermission(): Boolean {
        synchronized(notifPermLock) {
            if (hasRequestedNotifPermission) return false
            hasRequestedNotifPermission = true
        }
        sessionScope.launch {
            preferences.saveHasRequestedNotificationPermission(true)
        }
        return true
    }

    fun updateDiscoveredServers(discovered: List<DiscoveredServer>) {
        var autoSelectedUrl: String? = null
        _uiState.update { state ->
            val outcome = ServerSelectionPolicy.evaluate(
                discovered = discovered,
                currentServerUrl = state.serverUrl,
                hasExplicitSavedServer = state.hasExplicitSavedServer,
                isEditorExpanded = state.isServerEditorExpanded,
                userManuallyPickedInSession = userManuallyPickedInSession,
                isInRoom = state.isInRoom
            )
            if (outcome.autoSelected) {
                autoSelectedUrl = outcome.serverUrl
            }
            state.copy(
                discoveredServers = discovered,
                serverUrl = outcome.serverUrl,
                activeServerName = outcome.activeServerName
            )
        }
        autoSelectedUrl?.let { selected ->
            sessionScope.launch {
                preferences.saveServerUrl(selected)
            }
        }
    }

    fun setDiscoveringServers(scanning: Boolean) {
        _uiState.update { it.copy(isDiscoveringServers = scanning) }
    }

    fun openRoomSheetState() {
        userManuallyPickedInSession = false
        _uiState.update {
            it.copy(
                showRoomSheet = true,
                isServerEditorExpanded = false,
                roomSheetError = null,
                cameraPermissionDeniedHint = false
            )
        }
    }

    fun dismissRoomSheetState() {
        _uiState.update {
            it.copy(
                showRoomSheet = false,
                isConnectingAction = false,
                isServerEditorExpanded = false,
                roomSheetError = null,
                cameraPermissionDeniedHint = false
            )
        }
    }

    fun toggleServerEditorExpanded() {
        _uiState.update { it.copy(isServerEditorExpanded = !it.isServerEditorExpanded) }
    }

    fun selectDiscoveredServer(server: DiscoveredServer) {
        userManuallyPickedInSession = true
        _uiState.update {
            it.copy(
                serverUrl = server.wsUrl,
                activeServerName = server.name,
                hasExplicitSavedServer = true,
                isServerEditorExpanded = false,
                roomSheetError = null
            )
        }
        sessionScope.launch {
            preferences.saveServerUrl(server.wsUrl)
        }
    }

    fun applyManualServerAddress(rawInput: String): String? {
        val normalized = ServerUrlNormalizer.normalize(rawInput)
            ?: return "Enter a valid IP, host:port, or domain"
        userManuallyPickedInSession = true
        val matchedName = _uiState.value.discoveredServers
            .firstOrNull { it.wsUrl.equals(normalized, ignoreCase = true) }
            ?.name
        _uiState.update {
            it.copy(
                serverUrl = normalized,
                activeServerName = matchedName,
                hasExplicitSavedServer = true,
                isServerEditorExpanded = false,
                roomSheetError = null
            )
        }
        sessionScope.launch {
            preferences.saveServerUrl(normalized)
        }
        return null
    }

    fun openShareQrSheet(): Boolean {
        if (!_uiState.value.isInRoom) return false
        _uiState.update {
            it.copy(
                showRoomSheet = false,
                showShareQrSheet = true,
                isServerEditorExpanded = false
            )
        }
        return true
    }

    fun dismissShareQrSheet() {
        _uiState.update { it.copy(showShareQrSheet = false) }
    }

    fun openQrScanner() {
        _uiState.update {
            it.copy(
                showQrScanner = true,
                cameraPermissionDeniedHint = false,
                roomSheetError = null
            )
        }
    }

    fun closeQrScanner(): Boolean {
        _uiState.update { it.copy(showQrScanner = false) }
        return _uiState.value.showRoomSheet
    }

    fun onCameraPermissionDenied() {
        _uiState.update { it.copy(cameraPermissionDeniedHint = true) }
    }

    fun dismissCameraPermissionDeniedHint() {
        _uiState.update { it.copy(cameraPermissionDeniedHint = false) }
    }

    fun onQrRoomInviteScanned(serverUrl: String, roomCode: String) {
        val normalized = ServerUrlNormalizer.normalize(serverUrl) ?: return
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.length != 6) return
        userManuallyPickedInSession = true
        reconnectController.cancel()
        pendingRoomUrl = normalized
        val matchedName = _uiState.value.discoveredServers
            .firstOrNull { it.wsUrl.equals(normalized, ignoreCase = true) }
            ?.name
        _uiState.update {
            it.copy(
                showQrScanner = false,
                serverUrl = normalized,
                activeServerName = matchedName,
                hasExplicitSavedServer = true,
                roomCodeInput = cleanCode,
                isServerEditorExpanded = false,
                isConnectingAction = true,
                roomSheetError = null,
                cameraPermissionDeniedHint = false
            )
        }
        sessionScope.launch {
            preferences.saveServerUrl(normalized)
        }
        webSocketClient.connectAndExecute(normalized) {
            webSocketClient.joinRoom(cleanCode)
        }
    }

    fun onQrServerUrlScanned(serverUrl: String): Boolean {
        val normalized = ServerUrlNormalizer.normalize(serverUrl) ?: return false
        userManuallyPickedInSession = true
        val matchedName = _uiState.value.discoveredServers
            .firstOrNull { it.wsUrl.equals(normalized, ignoreCase = true) }
            ?.name
        _uiState.update {
            it.copy(
                showQrScanner = false,
                serverUrl = normalized,
                activeServerName = matchedName,
                hasExplicitSavedServer = true,
                isServerEditorExpanded = false,
                roomSheetError = null,
                snackbarMessage = "Server set to ${ServerUrlNormalizer.extractHostDisplay(normalized)}"
            )
        }
        sessionScope.launch {
            preferences.saveServerUrl(normalized)
        }
        return _uiState.value.showRoomSheet
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

    fun onRoomCodeInputChanged(code: String) {
        _uiState.update { it.copy(roomCodeInput = code, roomSheetError = null) }
    }

    fun createRoom() {
        val normalized = ServerUrlNormalizer.normalize(_uiState.value.serverUrl)
        if (normalized == null) {
            _uiState.update { it.copy(roomSheetError = "Invalid server address") }
            return
        }
        reconnectController.cancel()
        pendingRoomUrl = normalized
        sessionScope.launch {
            preferences.saveServerUrl(normalized)
        }
        _uiState.update {
            it.copy(
                serverUrl = normalized,
                isConnectingAction = true,
                roomSheetError = null
            )
        }
        webSocketClient.connectAndExecute(normalized) {
            webSocketClient.createRoom()
        }
    }

    fun joinRoom() {
        val normalized = ServerUrlNormalizer.normalize(_uiState.value.serverUrl)
        if (normalized == null) {
            _uiState.update { it.copy(roomSheetError = "Invalid server address") }
            return
        }
        val code = _uiState.value.roomCodeInput.trim().uppercase()
        if (code.length != 6) return
        reconnectController.cancel()
        pendingRoomUrl = normalized
        sessionScope.launch {
            preferences.saveServerUrl(normalized)
        }
        _uiState.update {
            it.copy(
                serverUrl = normalized,
                isConnectingAction = true,
                roomSheetError = null
            )
        }
        webSocketClient.connectAndExecute(normalized) {
            webSocketClient.joinRoom(code)
        }
    }

    fun leaveRoom() {
        reconnectController.cancel()
        activeRoomUrl = null
        pendingRoomUrl = null
        if (!_uiState.value.isInRoom) return
        webSocketClient.leaveRoom()
        exitRoomToSoloMode(snackbarMessage = "Left room — Solo Mode")
        webSocketClient.disconnect()
    }

    fun stopAndDismissFromNotification() {
        if (_uiState.value.isInRoom || reconnectController.isReconnecting) {
            leaveRoom()
        } else if (_uiState.value.soloState.isPlaying) {
            updateStateAndPushTransport { prev ->
                prev.copy(soloState = SoloScheduler.stop(prev.soloState))
            }
        }
    }

    fun togglePlayStop() {
        val state = _uiState.value
        val room = state.activeRoom
        if (room != null) {
            if (state.isFocusPausedInRoom) {
                updateStateAndPushTransport { it.copy(isFocusPausedInRoom = false) }
                if (!room.isPlaying && state.connectionStatus == ConnectionStatus.CONNECTED) {
                    webSocketClient.play()
                }
            } else if (room.isPlaying) {
                if (state.connectionStatus == ConnectionStatus.CONNECTED) {
                    webSocketClient.stop()
                } else {
                    updateStateAndPushTransport { it.copy(isFocusPausedInRoom = true) }
                }
            } else if (state.connectionStatus == ConnectionStatus.CONNECTED) {
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
            sessionScope.launch {
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
            sessionScope.launch {
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
        sessionScope.launch {
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
            sessionScope.launch {
                preferences.saveAccentPattern(nextAccents)
            }
        }
    }

    fun onMasterVolumeChanged(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        updateStateAndPushTransport { it.copy(masterVolume = clamped) }
        sessionScope.launch {
            preferences.saveMasterVolume(clamped)
        }
    }

    fun onClickSoundSelected(option: ClickSoundOption) {
        nativeEngine.setSoundProfile(option.id)
        _uiState.update { it.copy(clickSound = option) }
        sessionScope.launch {
            preferences.saveClickSound(option)
        }
    }

    fun onVisualBeatFlashChanged(enabled: Boolean) {
        _uiState.update { it.copy(visualBeatFlash = enabled) }
        sessionScope.launch {
            preferences.saveVisualBeatFlash(enabled)
        }
    }

    fun onManualOffsetChanged(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(
            PreferencesRepository.MIN_MANUAL_OFFSET_MS,
            PreferencesRepository.MAX_MANUAL_OFFSET_MS
        )
        updateStateAndPushTransport { it.copy(manualOffsetMs = clamped) }
        sessionScope.launch {
            preferences.saveManualOffsetMs(clamped)
        }
    }

    fun resyncNow() {
        if (_uiState.value.isInRoom) {
            clockSyncManager.resyncNow(sessionScope)
        }
    }

    fun readCurrentBeatIndex(): Int = nativeEngine.getCurrentBeatIndex()

    fun readBeatSequence(): Long = nativeEngine.getBeatSequence()

    private fun handlePermanentAudioFocusLoss() {
        currentFocusGain = AudioFocusController.GAIN_FULL
        updateStateAndPushTransport { prev ->
            if (prev.isInRoom) {
                prev.copy(isFocusPausedInRoom = true)
            } else {
                prev.copy(soloState = SoloScheduler.stop(prev.soloState))
            }
        }
    }

    private fun handleRoomJoined(event: RoomStateEvent) {
        val wasReconnecting = reconnectController.isReconnecting
        if (wasReconnecting) {
            reconnectController.onAttemptSucceeded()
        }
        activeRoomUrl = pendingRoomUrl ?: activeRoomUrl ?: _uiState.value.serverUrl

        val snapshot = event.toSnapshot()
        var adaptedAccents: List<Int> = emptyList()

        updateStateAndPushTransport { state ->
            val stoppedSolo = SoloScheduler.stop(state.soloState)
            adaptedAccents = AccentPattern.adaptPattern(state.accentLevels, snapshot.beatsPerBar)
            state.copy(
                soloState = stoppedSolo,
                activeRoom = snapshot,
                connectionStatus = ConnectionStatus.CONNECTED,
                reconnectAttempt = 0,
                isFocusPausedInRoom = if (wasReconnecting && snapshot.isPlaying) {
                    state.isFocusPausedInRoom
                } else {
                    false
                },
                accentLevels = adaptedAccents,
                showRoomSheet = false,
                showQrScanner = false,
                isServerEditorExpanded = false,
                cameraPermissionDeniedHint = false,
                isConnectingAction = false,
                roomSheetError = null,
                snackbarMessage = if (wasReconnecting) {
                    "Reconnected to room ${snapshot.roomCode}"
                } else {
                    "Connected to room ${snapshot.roomCode}"
                }
            )
        }
        nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(adaptedAccents))

        sessionScope.launch {
            preferences.saveAccentPattern(adaptedAccents)
            if (wasReconnecting) {
                clockSyncManager.resyncNow(sessionScope)
            } else {
                clockSyncManager.startSession(sessionScope)
            }
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
                isFocusPausedInRoom = if (!snapshot.isPlaying) false else state.isFocusPausedInRoom,
                accentLevels = nextAccents,
                roomSheetError = null
            )
        }

        adaptedAccents?.let { updated ->
            nativeEngine.setAccentBitmask(AccentPattern.packToBitmask(updated))
            sessionScope.launch {
                preferences.saveAccentPattern(updated)
            }
        }
    }

    private fun handleServerError(err: ServerErrorEvent) {
        if (reconnectController.isReconnecting) {
            if (err.code == "room_not_found") {
                val roomCode = _uiState.value.activeRoom?.roomCode
                reconnectController.cancel()
                activeRoomUrl = null
                webSocketClient.disconnect()
                val msg = if (!roomCode.isNullOrBlank()) {
                    "Room $roomCode ended — returned to Solo Mode"
                } else {
                    "Room ended — returned to Solo Mode"
                }
                exitRoomToSoloMode(snackbarMessage = msg)
            } else {
                reconnectController.onAttemptFailed(err.message)
            }
            return
        }

        _uiState.update {
            it.copy(
                isConnectingAction = false,
                roomSheetError = if (it.showRoomSheet) err.message else it.roomSheetError,
                snackbarMessage = if (!it.showRoomSheet) err.message else it.snackbarMessage
            )
        }
    }

    private fun handleConnectionChanged(
        status: ConnectionStatus,
        errorMsg: String?,
        isUnexpected: Boolean
    ) {
        if (reconnectController.isReconnecting) {
            if (status == ConnectionStatus.DISCONNECTED && isUnexpected) {
                reconnectController.onAttemptFailed(errorMsg)
            }
            return
        }

        if (status == ConnectionStatus.DISCONNECTED) {
            val currentState = _uiState.value
            val currentRoom = currentState.activeRoom
            if (isUnexpected && currentRoom != null) {
                clockSyncManager.stop(resetOffset = false)
                val targetUrl = activeRoomUrl ?: currentState.serverUrl
                reconnectController.startIfIdle(
                    target = ReconnectTarget(serverUrl = targetUrl, roomCode = currentRoom.roomCode),
                    initialError = errorMsg
                )
            } else if (currentRoom == null) {
                _uiState.update {
                    it.copy(
                        connectionStatus = status,
                        reconnectAttempt = 0,
                        isConnectingAction = false,
                        roomSheetError = errorMsg ?: it.roomSheetError
                    )
                }
            } else {
                _uiState.update { it.copy(connectionStatus = status, reconnectAttempt = 0) }
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
                connectionStatus = ConnectionStatus.DISCONNECTED,
                reconnectAttempt = 0,
                isFocusPausedInRoom = false,
                showShareQrSheet = false,
                isConnectingAction = false,
                roomSheetError = null,
                snackbarMessage = snackbarMessage,
                localScheduledStartNs = 0L
            )
        }
    }

    private fun startDiagnosticsPolling() {
        diagnosticsJob?.cancel()
        diagnosticsJob = sessionScope.launch {
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
        lateinit var updated: AppUiState
        synchronized(transportLock) {
            _uiState.update { prev ->
                val candidate = transform(prev)
                val localStartNs = computeLocalStartTimeNs(candidate)
                updated = candidate.copy(localScheduledStartNs = localStartNs)
                updated
            }
            pushSnapshotToNativeLocked(updated)
        }
        syncForegroundService(updated)
    }

    private fun syncForegroundService(state: AppUiState) {
        val desired = MetronomeServicePolicy.evaluate(MetronomeServicePolicy.fromUiState(state))
        if (desired.keepForegroundService && !MetronomeService.isActiveForeground) {
            MetronomeService.start(appContext)
        }
    }

    private fun computeLocalStartTimeNs(state: AppUiState): Long {
        val room = state.activeRoom
        return if (room != null) {
            if (room.isPlaying && !state.isFocusPausedInRoom && state.clockSync.isSynced) {
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
            room!!.isPlaying && !state.isFocusPausedInRoom && state.clockSync.isSynced
        } else {
            state.soloState.isPlaying
        }

        if (shouldPlay && !wasEnginePlaying) {
            sessionScope.launch(Dispatchers.IO) {
                nativeEngine.startStream()
            }
            audioFocusController.requestFocus()
        } else if (!shouldPlay && wasEnginePlaying) {
            audioFocusController.abandonFocus()
            currentFocusGain = AudioFocusController.GAIN_FULL
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
}

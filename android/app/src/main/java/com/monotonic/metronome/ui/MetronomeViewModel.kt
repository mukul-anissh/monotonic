package com.monotonic.metronome.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monotonic.metronome.MonotonicApplication
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.model.DiscoveredServer
import com.monotonic.metronome.network.ServerDiscoveryManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing ViewModel bridging Activity lifecycle and foreground LAN mDNS scanning
 * to the Application-scoped [com.monotonic.metronome.session.MetronomeSessionController].
 */
class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionController = MonotonicApplication.from(application).sessionController
    private val serverDiscoveryManager = ServerDiscoveryManager(application)

    val uiState: StateFlow<AppUiState> = sessionController.uiState

    init {
        viewModelScope.launch {
            serverDiscoveryManager.servers.collect { discovered ->
                sessionController.updateDiscoveredServers(discovered)
            }
        }

        viewModelScope.launch {
            serverDiscoveryManager.isScanning.collect { scanning ->
                sessionController.setDiscoveringServers(scanning)
            }
        }
    }

    fun openRoomSheet() {
        sessionController.openRoomSheetState()
        serverDiscoveryManager.startDiscovery()
    }

    fun dismissRoomSheet() {
        serverDiscoveryManager.stopDiscovery()
        sessionController.dismissRoomSheetState()
    }

    fun onAppBackgrounded() {
        sessionController.onUiForegroundChanged(false)
        serverDiscoveryManager.stopDiscovery()
    }

    fun onAppForegrounded() {
        sessionController.onUiForegroundChanged(true)
        val state = uiState.value
        if (state.showRoomSheet && !state.showQrScanner) {
            serverDiscoveryManager.startDiscovery()
        }
    }

    fun rescanLocalServers() {
        serverDiscoveryManager.rescan()
    }

    fun toggleServerEditorExpanded() = sessionController.toggleServerEditorExpanded()

    fun selectDiscoveredServer(server: DiscoveredServer) =
        sessionController.selectDiscoveredServer(server)

    fun applyManualServerAddress(rawInput: String): String? =
        sessionController.applyManualServerAddress(rawInput)

    fun openShareQrSheet() {
        if (sessionController.openShareQrSheet()) {
            serverDiscoveryManager.stopDiscovery()
        }
    }

    fun dismissShareQrSheet() = sessionController.dismissShareQrSheet()

    fun openQrScanner() {
        serverDiscoveryManager.stopDiscovery()
        sessionController.openQrScanner()
    }

    fun closeQrScanner() {
        if (sessionController.closeQrScanner()) {
            serverDiscoveryManager.startDiscovery()
        }
    }

    fun onCameraPermissionDenied() = sessionController.onCameraPermissionDenied()

    fun dismissCameraPermissionDeniedHint() = sessionController.dismissCameraPermissionDeniedHint()

    fun onQrRoomInviteScanned(serverUrl: String, roomCode: String) =
        sessionController.onQrRoomInviteScanned(serverUrl, roomCode)

    fun onQrServerUrlScanned(serverUrl: String) {
        if (sessionController.onQrServerUrlScanned(serverUrl)) {
            serverDiscoveryManager.startDiscovery()
        }
    }

    fun openSettings() = sessionController.openSettings()

    fun closeSettings() = sessionController.closeSettings()

    fun consumeSnackbar() = sessionController.consumeSnackbar()

    fun showSnackbar(message: String) = sessionController.showSnackbar(message)

    fun onRoomCodeInputChanged(code: String) = sessionController.onRoomCodeInputChanged(code)

    fun createRoom() = sessionController.createRoom()

    fun joinRoom() = sessionController.joinRoom()

    fun leaveRoom() = sessionController.leaveRoom()

    fun togglePlayStop() = sessionController.togglePlayStop()

    fun updateBpm(bpm: Double) = sessionController.updateBpm(bpm)

    fun nudgeBpm(delta: Double) = sessionController.nudgeBpm(delta)

    fun updateBeatsPerBar(beatsPerBar: Int) = sessionController.updateBeatsPerBar(beatsPerBar)

    fun updateSubdivision(subdivision: Int) = sessionController.updateSubdivision(subdivision)

    fun onTapTempo() = sessionController.onTapTempo()

    fun cycleBeatAccent(beatIndex: Int) = sessionController.cycleBeatAccent(beatIndex)

    fun onMasterVolumeChanged(volume: Float) = sessionController.onMasterVolumeChanged(volume)

    fun onClickSoundSelected(option: ClickSoundOption) =
        sessionController.onClickSoundSelected(option)

    fun onVisualBeatFlashChanged(enabled: Boolean) =
        sessionController.onVisualBeatFlashChanged(enabled)

    fun onManualOffsetChanged(offsetMs: Int) = sessionController.onManualOffsetChanged(offsetMs)

    fun resyncNow() = sessionController.resyncNow()

    fun readCurrentBeatIndex(): Int = sessionController.readCurrentBeatIndex()

    fun readBeatSequence(): Long = sessionController.readBeatSequence()

    fun consumeShouldRequestNotificationPermission(): Boolean =
        sessionController.consumeShouldRequestNotificationPermission()

    override fun onCleared() {
        super.onCleared()
        serverDiscoveryManager.destroy()
    }
}

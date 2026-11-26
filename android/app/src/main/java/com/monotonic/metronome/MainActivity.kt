package com.monotonic.metronome

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monotonic.metronome.ui.MetronomeViewModel
import com.monotonic.metronome.ui.screens.MetronomeScreen
import com.monotonic.metronome.ui.screens.SettingsScreen
import com.monotonic.metronome.ui.theme.DarkBackground
import com.monotonic.metronome.ui.theme.MonotonicTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            MonotonicTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                BackHandler(enabled = uiState.showSettings) {
                    viewModel.closeSettings()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    if (uiState.showSettings) {
                        SettingsScreen(
                            uiState = uiState,
                            onBack = viewModel::closeSettings,
                            onMasterVolumeChanged = viewModel::onMasterVolumeChanged,
                            onClickSoundSelected = viewModel::onClickSoundSelected,
                            onVisualBeatFlashChanged = viewModel::onVisualBeatFlashChanged,
                            onManualOffsetChanged = viewModel::onManualOffsetChanged,
                            onResyncNow = viewModel::resyncNow,
                            onApplyManualServerAddress = viewModel::applyManualServerAddress,
                            onLeaveRoom = viewModel::leaveRoom,
                            onOpenRoomSheetFromSettings = {
                                viewModel.closeSettings()
                                viewModel.openRoomSheet()
                            }
                        )
                    } else {
                        MetronomeScreen(
                            uiState = uiState,
                            readCurrentBeatIndex = viewModel::readCurrentBeatIndex,
                            readBeatSequence = viewModel::readBeatSequence,
                            onTogglePlayStop = viewModel::togglePlayStop,
                            onUpdateBpm = viewModel::updateBpm,
                            onNudgeBpm = viewModel::nudgeBpm,
                            onTapTempo = viewModel::onTapTempo,
                            onUpdateBeatsPerBar = viewModel::updateBeatsPerBar,
                            onUpdateSubdivision = viewModel::updateSubdivision,
                            onCycleBeatAccent = viewModel::cycleBeatAccent,
                            onOpenRoomSheet = viewModel::openRoomSheet,
                            onDismissRoomSheet = viewModel::dismissRoomSheet,
                            onOpenSettings = viewModel::openSettings,
                            onToggleServerEditor = viewModel::toggleServerEditorExpanded,
                            onRescanServers = viewModel::rescanLocalServers,
                            onSelectDiscoveredServer = viewModel::selectDiscoveredServer,
                            onApplyManualServerAddress = viewModel::applyManualServerAddress,
                            onRoomCodeChanged = viewModel::onRoomCodeInputChanged,
                            onCreateRoom = viewModel::createRoom,
                            onJoinRoom = viewModel::joinRoom,
                            onOpenShareQr = viewModel::openShareQrSheet,
                            onDismissShareQr = viewModel::dismissShareQrSheet,
                            onOpenQrScanner = viewModel::openQrScanner,
                            onCloseQrScanner = viewModel::closeQrScanner,
                            onCameraPermissionDenied = viewModel::onCameraPermissionDenied,
                            onDismissCameraPermissionHint = viewModel::dismissCameraPermissionDeniedHint,
                            onQrRoomInviteScanned = viewModel::onQrRoomInviteScanned,
                            onQrServerUrlScanned = viewModel::onQrServerUrlScanned,
                            onLeaveRoom = viewModel::leaveRoom,
                            onShowSnackbar = viewModel::showSnackbar,
                            onConsumeSnackbar = viewModel::consumeSnackbar
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        viewModel.onAppBackgrounded()
        super.onStop()
    }
}

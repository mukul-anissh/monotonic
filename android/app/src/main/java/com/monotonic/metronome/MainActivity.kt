package com.monotonic.metronome

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monotonic.metronome.service.MetronomeService
import com.monotonic.metronome.ui.MetronomeViewModel
import com.monotonic.metronome.ui.screens.MetronomeScreen
import com.monotonic.metronome.ui.screens.SettingsScreen
import com.monotonic.metronome.ui.theme.DarkBackground
import com.monotonic.metronome.ui.theme.MonotonicTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MetronomeService.refreshNotificationIfRunning(this)
        }
    }

    private fun maybeRequestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission && viewModel.consumeShouldRequestNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                            onTogglePlayStop = {
                                if (!uiState.isPlaying) {
                                    maybeRequestNotificationPermissionOnce()
                                }
                                viewModel.togglePlayStop()
                            },
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
                            onCreateRoom = {
                                maybeRequestNotificationPermissionOnce()
                                viewModel.createRoom()
                            },
                            onJoinRoom = {
                                maybeRequestNotificationPermissionOnce()
                                viewModel.joinRoom()
                            },
                            onOpenShareQr = viewModel::openShareQrSheet,
                            onDismissShareQr = viewModel::dismissShareQrSheet,
                            onOpenQrScanner = viewModel::openQrScanner,
                            onCloseQrScanner = viewModel::closeQrScanner,
                            onCameraPermissionDenied = viewModel::onCameraPermissionDenied,
                            onDismissCameraPermissionHint = viewModel::dismissCameraPermissionDeniedHint,
                            onQrRoomInviteScanned = { serverUrl, roomCode ->
                                maybeRequestNotificationPermissionOnce()
                                viewModel.onQrRoomInviteScanned(serverUrl, roomCode)
                            },
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

package com.monotonic.metronome

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import com.monotonic.metronome.ui.screens.LobbyScreen
import com.monotonic.metronome.ui.screens.RoomScreen
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
                val activeRoom = uiState.activeRoom

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    if (activeRoom == null) {
                        LobbyScreen(
                            uiState = uiState,
                            onServerUrlChanged = viewModel::onServerUrlChanged,
                            onRoomCodeChanged = viewModel::onRoomCodeInputChanged,
                            onCreateRoom = viewModel::createRoom,
                            onJoinRoom = viewModel::joinRoom
                        )
                    } else {
                        RoomScreen(
                            uiState = uiState,
                            room = activeRoom,
                            readCurrentBeatIndex = viewModel::readCurrentBeatIndex,
                            readBeatSequence = viewModel::readBeatSequence,
                            onTogglePlayStop = viewModel::togglePlayStop,
                            onUpdateBpm = viewModel::updateBpm,
                            onTapTempo = viewModel::onTapTempo,
                            onUpdateBeatsPerBar = viewModel::updateBeatsPerBar,
                            onUpdateSubdivision = viewModel::updateSubdivision,
                            onManualOffsetChanged = viewModel::onManualOffsetChanged,
                            onResyncNow = viewModel::resyncNow,
                            onLeaveRoom = viewModel::leaveRoom
                        )
                    }
                }
            }
        }
    }
}

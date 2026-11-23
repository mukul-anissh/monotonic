package com.monotonic.metronome.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.model.TimeSignaturePreset
import com.monotonic.metronome.ui.components.AudioRouteBanner
import com.monotonic.metronome.ui.components.BeatIndicator
import com.monotonic.metronome.ui.components.DiagnosticsCard
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.DarkBackground
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurface
import com.monotonic.metronome.ui.theme.DarkSurfaceVariant
import com.monotonic.metronome.ui.theme.ErrorRose
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import com.monotonic.metronome.ui.theme.WarningAmber
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(
    uiState: AppUiState,
    room: RoomSnapshot,
    readCurrentBeatIndex: () -> Int,
    readBeatSequence: () -> Long,
    onTogglePlayStop: () -> Unit,
    onUpdateBpm: (Double) -> Unit,
    onTapTempo: () -> Unit,
    onUpdateBeatsPerBar: (Int) -> Unit,
    onUpdateSubdivision: (Int) -> Unit,
    onManualOffsetChanged: (Int) -> Unit,
    onResyncNow: () -> Unit,
    onLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copiedFeedback by remember { mutableStateOf(false) }

    var sliderBpm by remember(room.bpm) { mutableFloatStateOf(room.bpm.toFloat()) }
    var isDraggingBpm by remember { mutableStateOf(false) }

    var leadInRemainingMs by remember { mutableLongStateOf(0L) }
    val effectiveLocalStartNs = uiState.localScheduledStartNs +
        (uiState.manualOffsetMs.toLong() * 1_000_000L)

    LaunchedEffect(room.isPlaying, uiState.clockSync.isSynced, effectiveLocalStartNs) {
        if (!room.isPlaying || !uiState.clockSync.isSynced || uiState.localScheduledStartNs <= 0L) {
            leadInRemainingMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val remainingNs = effectiveLocalStartNs - System.nanoTime()
                leadInRemainingMs = if (remainingNs > 0L) remainingNs / 1_000_000L else 0L
            }
            if (leadInRemainingMs <= 0L) break
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header: Room Code, Participant Count, Connection Status, Leave Button
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusDotColor = when (uiState.connectionStatus) {
                        ConnectionStatus.CONNECTED -> AccentEmerald
                        ConnectionStatus.CONNECTING,
                        ConnectionStatus.RECONNECTING -> WarningAmber
                        ConnectionStatus.DISCONNECTED -> ErrorRose
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = room.roomCode,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AccentAmber
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(room.roomCode))
                                    copiedFeedback = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy room code",
                                    tint = if (copiedFeedback) AccentEmerald else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Group,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${room.participantCount} connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onLeaveRoom,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                        contentDescription = "Leave room",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Leave")
                }
            }
        }

        // Wired Earphone Guard Banner
        AudioRouteBanner(routeState = uiState.audioRoute)

        if (uiState.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ErrorRose.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ErrorRose.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRose,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Visual Beat Indicator
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BeatIndicator(
                    beatsPerBar = room.beatsPerBar,
                    isPlaying = room.isPlaying && uiState.clockSync.isSynced,
                    readCurrentBeatIndex = readCurrentBeatIndex,
                    readBeatSequence = readBeatSequence
                )
            }
        }

        // Primary Transport Play / Stop Button with 1.5s Lead-In Countdown
        val isSyncingClock = !uiState.clockSync.isSynced
        val buttonContainerColor = if (room.isPlaying) ErrorRose else AccentEmerald
        Button(
            onClick = onTogglePlayStop,
            enabled = !isSyncingClock,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonContainerColor,
                contentColor = if (room.isPlaying) Color.White else DarkBackground
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Icon(
                imageVector = if (room.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            val labelText = when {
                isSyncingClock -> "Syncing clock..."
                room.isPlaying && leadInRemainingMs > 0L -> String.format(
                    Locale.US,
                    "Syncing start... %.1fs",
                    leadInRemainingMs / 1000.0
                )
                room.isPlaying -> "STOP"
                else -> "PLAY"
            }
            Text(
                text = labelText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Tempo & Meter Controls
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val displayedBpm = if (isDraggingBpm) sliderBpm.roundToInt() else room.bpm.roundToInt()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { onUpdateBpm((room.bpm - 1.0).coerceIn(30.0, 300.0)) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DarkBorder),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease BPM", tint = TextPrimary)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$displayedBpm",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "BPM",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentAmber,
                            letterSpacing = 2.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { onUpdateBpm((room.bpm + 1.0).coerceIn(30.0, 300.0)) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DarkBorder),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase BPM", tint = TextPrimary)
                    }
                }

                Slider(
                    value = if (isDraggingBpm) sliderBpm else room.bpm.toFloat(),
                    onValueChange = {
                        isDraggingBpm = true
                        sliderBpm = it
                    },
                    onValueChangeFinished = {
                        isDraggingBpm = false
                        onUpdateBpm(sliderBpm.roundToInt().toDouble().coerceIn(30.0, 300.0))
                    },
                    valueRange = 30f..300f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentAmber,
                        activeTrackColor = AccentAmber,
                        inactiveTrackColor = DarkSurfaceVariant
                    )
                )

                OutlinedButton(
                    onClick = onTapTempo,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TAP TEMPO", fontWeight = FontWeight.SemiBold)
                }

                // Time Signature Chips
                Text(
                    text = "Time Signature",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TimeSignaturePreset.PRESETS.forEach { preset ->
                        val selected = (room.beatsPerBar == preset.beatsPerBar)
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateBeatsPerBar(preset.beatsPerBar) },
                            label = {
                                Text(
                                    text = preset.label,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentAmber.copy(alpha = 0.22f),
                                selectedLabelColor = AccentAmber,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = DarkBorder,
                                selectedBorderColor = AccentAmber
                            )
                        )
                    }
                }

                // Subdivision Chips
                Text(
                    text = "Subdivision",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1 to "1x Quarter", 2 to "2x Eighth", 4 to "4x 16th").forEach { (sub, label) ->
                        val selected = (room.subdivision == sub)
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateSubdivision(sub) },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentEmerald.copy(alpha = 0.2f),
                                selectedLabelColor = AccentEmerald,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = DarkBorder,
                                selectedBorderColor = AccentEmerald
                            )
                        )
                    }
                }
            }
        }

        // Manual Calibration Offset (-50ms to +50ms in 1ms steps)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Manual Device Calibration",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Compensate OEM audio driver skew (-50ms to +50ms)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    TextButton(
                        onClick = { onManualOffsetChanged(0) },
                        enabled = uiState.manualOffsetMs != 0,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+d ms", uiState.manualOffsetMs),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = AccentAmber
                        )
                    }
                }

                Slider(
                    value = uiState.manualOffsetMs.toFloat(),
                    onValueChange = { onManualOffsetChanged(it.roundToInt()) },
                    valueRange = PreferencesRepository.MIN_MANUAL_OFFSET_MS.toFloat()..
                        PreferencesRepository.MAX_MANUAL_OFFSET_MS.toFloat(),
                    steps = 99,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentAmber,
                        activeTrackColor = AccentAmber,
                        inactiveTrackColor = DarkSurfaceVariant
                    )
                )
            }
        }

        // Collapsible Sync Diagnostics Card
        DiagnosticsCard(
            clockSync = uiState.clockSync,
            audioDiagnostics = uiState.audioDiagnostics,
            onResyncNow = onResyncNow
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

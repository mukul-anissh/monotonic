package com.monotonic.metronome.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.AudioRouteStatus
import com.monotonic.metronome.model.SyncQuality
import com.monotonic.metronome.model.TimeSignaturePreset
import com.monotonic.metronome.sync.SoloScheduler
import com.monotonic.metronome.ui.components.AudioRouteBanner
import com.monotonic.metronome.ui.components.BeatVisualizer
import com.monotonic.metronome.ui.components.BpmInputDialog
import com.monotonic.metronome.ui.components.CustomMeterDialog
import com.monotonic.metronome.ui.components.RoomConnectionSheet
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MetronomeScreen(
    uiState: AppUiState,
    readCurrentBeatIndex: () -> Int,
    readBeatSequence: () -> Long,
    onTogglePlayStop: () -> Unit,
    onUpdateBpm: (Double) -> Unit,
    onNudgeBpm: (Double) -> Unit,
    onTapTempo: () -> Unit,
    onUpdateBeatsPerBar: (Int) -> Unit,
    onUpdateSubdivision: (Int) -> Unit,
    onCycleBeatAccent: (Int) -> Unit,
    onOpenRoomSheet: () -> Unit,
    onDismissRoomSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onRoomCodeChanged: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onConsumeSnackbar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val keepAwake = uiState.isPlaying || uiState.isInRoom
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose {
            view.keepScreenOn = false
        }
    }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onConsumeSnackbar()
        }
    }

    var showBpmDialog by remember { mutableStateOf(false) }
    var showCustomMeterDialog by remember { mutableStateOf(false) }

    var sliderBpm by remember(uiState.bpm) { mutableFloatStateOf(uiState.bpm.toFloat()) }
    var isDraggingBpm by remember { mutableStateOf(false) }

    var leadInRemainingMs by remember { mutableLongStateOf(0L) }
    val effectiveLocalStartNs = uiState.localScheduledStartNs +
        (uiState.manualOffsetMs.toLong() * 1_000_000L)

    LaunchedEffect(uiState.isInRoom, uiState.isPlaying, uiState.clockSync.isSynced, effectiveLocalStartNs) {
        if (!uiState.isInRoom || !uiState.isPlaying || !uiState.clockSync.isSynced ||
            uiState.localScheduledStartNs <= 0L
        ) {
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            StageTopBar(
                uiState = uiState,
                onOpenRoomSheet = onOpenRoomSheet,
                onOpenSettings = onOpenSettings,
                onCopyRoomCode = { code ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Room Code", code))
                    onShowSnackbar("Room code $code copied")
                }
            )
        },
        bottomBar = {
            TransportDock(
                isInRoom = uiState.isInRoom,
                isPlaying = uiState.isPlaying,
                isClockSynced = uiState.clockSync.isSynced,
                leadInRemainingMs = leadInRemainingMs,
                onTogglePlayStop = onTogglePlayStop
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Context-aware audio route banner: shown only in Room Mode without wired earphones
            if (uiState.isInRoom && uiState.audioRoute.status != AudioRouteStatus.WIRED_READY) {
                AudioRouteBanner(routeState = uiState.audioRoute)
            }

            // Centerpiece: Interactive local per-beat accent visualizer
            BeatVisualizer(
                beatsPerBar = uiState.beatsPerBar,
                accentLevels = uiState.accentLevels,
                isPlaying = uiState.isPlaying && (!uiState.isInRoom || uiState.clockSync.isSynced),
                visualBeatFlash = uiState.visualBeatFlash,
                readCurrentBeatIndex = readCurrentBeatIndex,
                readBeatSequence = readBeatSequence,
                onCycleBeatAccent = onCycleBeatAccent
            )

            // Tempo Hub
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val displayedBpm = if (isDraggingBpm) {
                        sliderBpm.roundToInt()
                    } else {
                        uiState.bpm.roundToInt()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RepeatingStepperButton(
                            delta = -1.0,
                            contentDesc = "Decrease BPM",
                            icon = Icons.Rounded.Remove,
                            onNudge = onNudgeBpm
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkSurfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showBpmDialog = true }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "$displayedBpm",
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontSize = 54.sp,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = TextPrimary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "BPM",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AccentAmber,
                                        letterSpacing = 2.sp
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit BPM numerically",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        RepeatingStepperButton(
                            delta = 1.0,
                            contentDesc = "Increase BPM",
                            icon = Icons.Rounded.Add,
                            onNudge = onNudgeBpm
                        )
                    }

                    Slider(
                        value = if (isDraggingBpm) sliderBpm else uiState.bpm.toFloat(),
                        onValueChange = {
                            isDraggingBpm = true
                            sliderBpm = it
                        },
                        onValueChangeFinished = {
                            isDraggingBpm = false
                            onUpdateBpm(
                                sliderBpm.roundToInt().toDouble().coerceIn(
                                    SoloScheduler.MIN_BPM,
                                    SoloScheduler.MAX_BPM
                                )
                            )
                        },
                        valueRange = SoloScheduler.MIN_BPM.toFloat()..SoloScheduler.MAX_BPM.toFloat(),
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
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TAP TEMPO", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Meter & Subdivision Strip
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val presetMatch = TimeSignaturePreset.PRESETS.any {
                        it.beatsPerBar == uiState.beatsPerBar
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "METER",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "${uiState.beatsPerBar} beats / bar",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentAmber
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TimeSignaturePreset.PRESETS.forEach { preset ->
                            val selected = uiState.beatsPerBar == preset.beatsPerBar
                            SegmentedPill(
                                text = preset.label,
                                selected = selected,
                                activeColor = AccentAmber,
                                onClick = { onUpdateBeatsPerBar(preset.beatsPerBar) }
                            )
                        }
                        SegmentedPill(
                            text = if (presetMatch) "Custom" else "${uiState.beatsPerBar}/4",
                            selected = !presetMatch,
                            activeColor = AccentAmber,
                            onClick = { showCustomMeterDialog = true }
                        )
                    }

                    Text(
                        text = "SUBDIVISION",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            1 to "1x",
                            2 to "2x",
                            3 to "3x Triplet",
                            4 to "4x"
                        ).forEach { (sub, label) ->
                            val selected = uiState.subdivision == sub
                            SegmentedPill(
                                text = label,
                                selected = selected,
                                activeColor = AccentEmerald,
                                onClick = { onUpdateSubdivision(sub) },
                                modifier = Modifier.weight(if (sub == 3) 1.45f else 1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBpmDialog) {
        BpmInputDialog(
            currentBpm = uiState.bpm,
            onDismiss = { showBpmDialog = false },
            onConfirm = { newBpm ->
                showBpmDialog = false
                onUpdateBpm(newBpm)
            }
        )
    }

    if (showCustomMeterDialog) {
        CustomMeterDialog(
            currentBeatsPerBar = uiState.beatsPerBar,
            onDismiss = { showCustomMeterDialog = false },
            onSelectBeatsPerBar = { count ->
                showCustomMeterDialog = false
                onUpdateBeatsPerBar(count)
            }
        )
    }

    if (uiState.showRoomSheet) {
        RoomConnectionSheet(
            serverUrl = uiState.serverUrl,
            roomCodeInput = uiState.roomCodeInput,
            isConnecting = uiState.isConnectingAction,
            errorMessage = uiState.roomSheetError,
            onServerUrlChanged = onServerUrlChanged,
            onRoomCodeChanged = onRoomCodeChanged,
            onCreateRoom = onCreateRoom,
            onJoinRoom = onJoinRoom,
            onDismiss = onDismissRoomSheet
        )
    }
}

@Composable
private fun StageTopBar(
    uiState: AppUiState,
    onOpenRoomSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyRoomCode: (String) -> Unit
) {
    val room = uiState.activeRoom
    Surface(
        color = DarkBackground,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (room == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AccentEmerald)
                            )
                            Text(
                                text = "Solo",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = AccentAmber.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpenRoomSheet)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = AccentAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Sync Room",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = AccentAmber
                            )
                        }
                    }
                }
            } else {
                val dotColor = when (uiState.syncQuality) {
                    SyncQuality.GOOD -> AccentEmerald
                    SyncQuality.FAIR,
                    SyncQuality.SYNCING -> WarningAmber
                    SyncQuality.POOR,
                    SyncQuality.OFFLINE -> ErrorRose
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onCopyRoomCode(room.roomCode) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = room.roomCode,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.5.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AccentAmber
                            )
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy room code",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Group,
                                contentDescription = "Participants",
                                tint = TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${room.participantCount}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = TextPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun TransportDock(
    isInRoom: Boolean,
    isPlaying: Boolean,
    isClockSynced: Boolean,
    leadInRemainingMs: Long,
    onTogglePlayStop: () -> Unit
) {
    val waitingForSync = isInRoom && !isClockSynced
    val containerColor = when {
        waitingForSync -> DarkSurfaceVariant
        isPlaying -> ErrorRose
        else -> AccentEmerald
    }
    val contentColor = when {
        waitingForSync -> TextSecondary
        isPlaying -> Color.White
        else -> DarkBackground
    }

    val labelText = when {
        waitingForSync -> "Syncing clock..."
        isInRoom && isPlaying && leadInRemainingMs > 0L -> String.format(
            Locale.US,
            "Syncing... %.1fs",
            leadInRemainingMs / 1000.0
        )
        isPlaying -> "STOP"
        else -> "PLAY"
    }

    Surface(
        color = DarkBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = onTogglePlayStop,
                enabled = !waitingForSync,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                    disabledContainerColor = DarkSurfaceVariant,
                    disabledContentColor = TextSecondary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = labelText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun RepeatingStepperButton(
    delta: Double,
    contentDesc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onNudge: (Double) -> Unit
) {
    val currentOnNudge by rememberUpdatedState(onNudge)
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            }
            .pointerInput(delta) {
                detectTapGestures(
                    onPress = {
                        currentOnNudge(delta)
                        val repeatJob = scope.launch {
                            delay(360L)
                            while (isActive) {
                                currentOnNudge(delta)
                                delay(90L)
                            }
                        }
                        tryAwaitRelease()
                        repeatJob.cancel()
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SegmentedPill(
    text: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) activeColor.copy(alpha = 0.2f) else DarkSurfaceVariant,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) activeColor else DarkBorder
        ),
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) activeColor else TextSecondary,
                maxLines = 1
            )
        }
    }
}

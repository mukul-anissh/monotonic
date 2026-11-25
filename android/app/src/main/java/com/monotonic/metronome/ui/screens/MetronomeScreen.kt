package com.monotonic.metronome.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.AudioRouteStatus
import com.monotonic.metronome.model.SyncQuality
import com.monotonic.metronome.model.TimeSignaturePreset
import com.monotonic.metronome.ui.components.AudioRouteBanner
import com.monotonic.metronome.ui.components.BeatVisualizer
import com.monotonic.metronome.ui.components.BpmInputDialog
import com.monotonic.metronome.ui.components.MeterSelectorSheet
import com.monotonic.metronome.ui.components.RoomConnectionSheet
import com.monotonic.metronome.ui.components.SubdivisionDots
import com.monotonic.metronome.ui.components.SubdivisionSelectorSheet
import com.monotonic.metronome.ui.components.TempoRuler
import com.monotonic.metronome.ui.theme.ContrastInk
import com.monotonic.metronome.ui.theme.ContrastSurface
import com.monotonic.metronome.ui.theme.ObsidianCanvas
import com.monotonic.metronome.ui.theme.StatusAmber
import com.monotonic.metronome.ui.theme.StatusCoral
import com.monotonic.metronome.ui.theme.StatusEmerald
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceTonal
import com.monotonic.metronome.ui.theme.SurfaceTonalElevated
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    onLeaveRoom: () -> Unit,
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
    var showMeterSheet by remember { mutableStateOf(false) }
    var showSubdivisionSheet by remember { mutableStateOf(false) }

    var previewBpm by remember { mutableStateOf<Int?>(null) }
    var awaitingCommit by remember { mutableStateOf(false) }

    LaunchedEffect(awaitingCommit, previewBpm, uiState.bpm) {
        if (awaitingCommit && previewBpm != null) {
            if (uiState.bpm.roundToInt() == previewBpm) {
                previewBpm = null
                awaitingCommit = false
            } else {
                delay(450L)
                previewBpm = null
                awaitingCommit = false
            }
        }
    }

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
        containerColor = ObsidianCanvas,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SurfaceTonalHigh,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        topBar = {
            StageTopBar(
                uiState = uiState,
                onOpenRoomSheet = onOpenRoomSheet,
                onOpenSettings = onOpenSettings
            )
        },
        bottomBar = {
            PerformanceDock(
                isInRoom = uiState.isInRoom,
                isPlaying = uiState.isPlaying,
                isClockSynced = uiState.clockSync.isSynced,
                leadInRemainingMs = leadInRemainingMs,
                onTapTempo = onTapTempo,
                onTogglePlayStop = onTogglePlayStop
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val minViewportHeight = maxHeight
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minViewportHeight)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Upper Zone: Optional Route Pill + Borderless Animated Beat Strip
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isInRoom && uiState.audioRoute.status != AudioRouteStatus.WIRED_READY) {
                        AudioRouteBanner(routeState = uiState.audioRoute)
                    }

                    BeatVisualizer(
                        beatsPerBar = uiState.beatsPerBar,
                        accentLevels = uiState.accentLevels,
                        isPlaying = uiState.isPlaying && (!uiState.isInRoom || uiState.clockSync.isSynced),
                        visualBeatFlash = uiState.visualBeatFlash,
                        readCurrentBeatIndex = readCurrentBeatIndex,
                        readBeatSequence = readBeatSequence,
                        onCycleBeatAccent = onCycleBeatAccent
                    )
                }

                // Middle Zone: Monumental Tempo Centerpiece + Precision Horizontal Ruler
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val displayedBpm = previewBpm ?: uiState.bpm.roundToInt()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        CircularTempoStepperButton(
                            delta = -1.0,
                            contentDesc = "Decrease BPM",
                            icon = Icons.Rounded.Remove,
                            onNudge = {
                                previewBpm = null
                                awaitingCommit = false
                                onNudgeBpm(it)
                            }
                        )

                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showBpmDialog = true }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$displayedBpm",
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = 88.sp,
                                    lineHeight = 90.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (-3).sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = TextPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "BPM",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 3.sp,
                                color = TextSecondary
                            )
                        }

                        CircularTempoStepperButton(
                            delta = 1.0,
                            contentDesc = "Increase BPM",
                            icon = Icons.Rounded.Add,
                            onNudge = {
                                previewBpm = null
                                awaitingCommit = false
                                onNudgeBpm(it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TempoRuler(
                        bpm = uiState.bpm,
                        onBpmPreview = { bpmVal ->
                            awaitingCommit = false
                            previewBpm = bpmVal
                        },
                        onBpmCommit = { bpmVal ->
                            awaitingCommit = true
                            onUpdateBpm(bpmVal)
                        }
                    )
                }

                // Lower Zone: Compact Parameter Pills Row (Meter & Local Subdivision)
                ParameterPillsRow(
                    beatsPerBar = uiState.beatsPerBar,
                    subdivision = uiState.subdivision,
                    onOpenMeterSheet = { showMeterSheet = true },
                    onOpenSubdivisionSheet = { showSubdivisionSheet = true }
                )
            }
        }
    }

    if (showBpmDialog) {
        BpmInputDialog(
            currentBpm = uiState.bpm,
            onDismiss = { showBpmDialog = false },
            onConfirm = { newBpm ->
                showBpmDialog = false
                previewBpm = null
                awaitingCommit = false
                onUpdateBpm(newBpm)
            }
        )
    }

    if (showMeterSheet) {
        MeterSelectorSheet(
            currentBeatsPerBar = uiState.beatsPerBar,
            onSelectBeatsPerBar = onUpdateBeatsPerBar,
            onDismiss = { showMeterSheet = false }
        )
    }

    if (showSubdivisionSheet) {
        SubdivisionSelectorSheet(
            currentSubdivision = uiState.subdivision,
            onSelectSubdivision = onUpdateSubdivision,
            onDismiss = { showSubdivisionSheet = false }
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
            onDismiss = onDismissRoomSheet,
            activeRoom = uiState.activeRoom,
            onCopyRoomCode = { code ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Room Code", code))
                onShowSnackbar("Room code $code copied")
            },
            onLeaveRoom = onLeaveRoom
        )
    }
}

@Composable
private fun StageTopBar(
    uiState: AppUiState,
    onOpenRoomSheet: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val room = uiState.activeRoom
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = SurfaceTonal,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onOpenRoomSheet)
        ) {
            if (room == null) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StatusEmerald)
                    )
                    Text(
                        text = "Solo",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Open sync room",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            } else {
                val dotColor = when (uiState.syncQuality) {
                    SyncQuality.GOOD -> StatusEmerald
                    SyncQuality.FAIR,
                    SyncQuality.SYNCING -> StatusAmber
                    SyncQuality.POOR,
                    SyncQuality.OFFLINE -> StatusCoral
                }
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = room.roomCode,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        color = StudioAmber
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Group,
                            contentDescription = "Peers",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${room.participantCount}",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(SurfaceTonal)
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ParameterPillsRow(
    beatsPerBar: Int,
    subdivision: Int,
    onOpenMeterSheet: () -> Unit,
    onOpenSubdivisionSheet: () -> Unit
) {
    val meterLabel = remember(beatsPerBar) {
        TimeSignaturePreset.PRESETS.firstOrNull { it.beatsPerBar == beatsPerBar }?.label
            ?: "$beatsPerBar/4"
    }
    val subName = when (subdivision) {
        1 -> "Quarter"
        2 -> "Eighth"
        3 -> "Triplet"
        4 -> "Sixteenth"
        else -> "Quarter"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceTonal,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenMeterSheet)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "METER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = meterLabel,
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = "$beatsPerBar beats",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceTonal,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenSubdivisionSheet)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SUBDIVISION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = TextSecondary
                    )
                    SubdivisionDots(
                        count = subdivision,
                        isSelected = subdivision > 1
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "${subdivision}x",
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = subName,
                        fontSize = 12.sp,
                        color = if (subdivision > 1) StudioAmber else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceDock(
    isInRoom: Boolean,
    isPlaying: Boolean,
    isClockSynced: Boolean,
    leadInRemainingMs: Long,
    onTapTempo: () -> Unit,
    onTogglePlayStop: () -> Unit
) {
    val view = LocalView.current
    val waitingForClockSync = isInRoom && !isClockSynced
    val isLeadIn = isInRoom && isPlaying && isClockSynced && leadInRemainingMs > 0L

    val targetContainerColor = when {
        waitingForClockSync -> SurfaceTonalHigh
        isLeadIn -> StudioAmber.copy(alpha = 0.22f)
        isPlaying -> StudioAmber
        else -> ContrastSurface
    }
    val targetContentColor = when {
        waitingForClockSync -> TextSecondary
        isLeadIn -> StudioAmber
        else -> ContrastInk
    }

    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = 180),
        label = "playButtonBg"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(durationMillis = 180),
        label = "playButtonInk"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onTapTempo()
            },
            shape = RoundedCornerShape(30.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceTonalElevated,
                contentColor = TextPrimary
            ),
            modifier = Modifier
                .weight(0.35f)
                .height(60.dp)
        ) {
            Text(
                text = "TAP",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp
            )
        }

        Button(
            onClick = onTogglePlayStop,
            enabled = !waitingForClockSync,
            shape = RoundedCornerShape(30.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = SurfaceTonalHigh,
                disabledContentColor = TextSecondary
            ),
            modifier = Modifier
                .weight(0.65f)
                .height(60.dp)
        ) {
            when {
                waitingForClockSync -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SYNCING",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                }

                isLeadIn -> {
                    val progress = (1f - (leadInRemainingMs / 1500f)).coerceIn(0.05f, 1f)
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = StudioAmber,
                        trackColor = StudioAmber.copy(alpha = 0.25f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SYNCING",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                }

                isPlaying -> {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STOP",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                }

                else -> {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PLAY",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CircularTempoStepperButton(
    delta: Double,
    contentDesc: String,
    icon: ImageVector,
    onNudge: (Double) -> Unit
) {
    val view = LocalView.current
    val currentOnNudge by rememberUpdatedState(onNudge)
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "stepperPressScale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(SurfaceTonalHigh)
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            }
            .pointerInput(delta) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        currentOnNudge(delta)
                        val repeatJob = scope.launch {
                            delay(360L)
                            while (isActive) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentOnNudge(delta)
                                delay(80L)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeatJob.cancel()
                            isPressed = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}

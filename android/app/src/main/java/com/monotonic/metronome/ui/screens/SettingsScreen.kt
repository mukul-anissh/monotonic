package com.monotonic.metronome.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.ui.components.PrecisionSlider
import com.monotonic.metronome.ui.theme.ContrastInk
import com.monotonic.metronome.ui.theme.HairlineDivider
import com.monotonic.metronome.ui.theme.ObsidianCanvas
import com.monotonic.metronome.ui.theme.StatusAmber
import com.monotonic.metronome.ui.theme.StatusCoral
import com.monotonic.metronome.ui.theme.StatusEmerald
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceGrouped
import com.monotonic.metronome.ui.theme.SurfaceTonal
import com.monotonic.metronome.ui.theme.SurfaceTonalElevated
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    uiState: AppUiState,
    onBack: () -> Unit,
    onMasterVolumeChanged: (Float) -> Unit,
    onClickSoundSelected: (ClickSoundOption) -> Unit,
    onVisualBeatFlashChanged: (Boolean) -> Unit,
    onManualOffsetChanged: (Int) -> Unit,
    onResyncNow: () -> Unit,
    onApplyManualServerAddress: (String) -> String?,
    onLeaveRoom: () -> Unit,
    onOpenRoomSheetFromSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var serverDraft by remember(uiState.serverUrl) { mutableStateOf(uiState.serverUrl) }
    var serverDraftError by remember(uiState.serverUrl) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isIgnoringBatteryOptimizations by remember(context) {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        )
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations =
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianCanvas,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceTonal)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to metronome",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 1. SOUND
            SettingsSection(title = "SOUND") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Click Sound",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ObsidianCanvas,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ClickSoundOption.entries.forEach { option ->
                                val selected = uiState.clickSound == option
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) StudioAmber.copy(alpha = 0.20f) else Color.Transparent
                                        )
                                        .clickable { onClickSoundSelected(option) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) StudioAmber else TextSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDivider()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Master Volume",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${(uiState.masterVolume * 100f).roundToInt()}%",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioAmber
                        )
                    }

                    PrecisionSlider(
                        value = uiState.masterVolume,
                        onValueChange = onMasterVolumeChanged,
                        valueRange = 0f..1f
                    )
                }

                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Visual Beat Flash",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Glow and pulse beat bars on each beat",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = uiState.visualBeatFlash,
                        onCheckedChange = onVisualBeatFlashChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ContrastInk,
                            checkedTrackColor = StudioAmber,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = SurfaceTonalHigh,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            // 2. CALIBRATION
            SettingsSection(title = "CALIBRATION") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Manual Timing Offset",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Fine-tune audio hardware skew (-50 to +50 ms)",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%+d ms", uiState.manualOffsetMs),
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.manualOffsetMs != 0) StudioAmber else TextSecondary
                        )
                    }

                    PrecisionSlider(
                        value = uiState.manualOffsetMs.toFloat(),
                        onValueChange = { onManualOffsetChanged(it.roundToInt()) },
                        valueRange = PreferencesRepository.MIN_MANUAL_OFFSET_MS.toFloat()..
                            PreferencesRepository.MAX_MANUAL_OFFSET_MS.toFloat(),
                        bipolarCenter = 0f,
                        snapToIntegers = true
                    )

                    Button(
                        onClick = { onManualOffsetChanged(0) },
                        enabled = uiState.manualOffsetMs != 0,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceTonalHigh,
                            contentColor = TextPrimary,
                            disabledContainerColor = SurfaceTonal,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Reset to 0 ms",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. DIAGNOSTICS
            SettingsSection(title = "DIAGNOSTICS") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isInRoom) {
                        DiagnosticRow(
                            label = "Min / Median RTT",
                            value = String.format(
                                Locale.US,
                                "%.2f ms / %.2f ms",
                                uiState.clockSync.minRttMs,
                                uiState.clockSync.medianRttMs
                            )
                        )
                        DiagnosticRow(
                            label = "Clock Offset",
                            value = String.format(Locale.US, "%+.3f ms", uiState.clockSync.offsetMs)
                        )
                        DiagnosticRow(
                            label = "Sync Jitter (σ)",
                            value = String.format(Locale.US, "%.3f ms", uiState.clockSync.jitterMs)
                        )
                    } else {
                        DiagnosticRow(
                            label = "Network Clock Sync",
                            value = "Solo Mode",
                            valueColor = TextSecondary
                        )
                    }
                }

                SettingsDivider()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DiagnosticRow(
                        label = "Oboe Hardware Latency",
                        value = String.format(
                            Locale.US,
                            "%.2f ms",
                            uiState.audioDiagnostics.hardwareLatencyMs
                        )
                    )
                    DiagnosticRow(
                        label = "Active Sample Rate",
                        value = "${uiState.audioDiagnostics.sampleRateHz} Hz"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Oboe Sharing Mode",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.audioDiagnostics.isExclusiveMode) {
                                            StatusEmerald
                                        } else {
                                            StatusAmber
                                        }
                                    )
                            )
                            Text(
                                text = if (uiState.audioDiagnostics.isExclusiveMode) {
                                    "Exclusive (${uiState.audioDiagnostics.audioApiName})"
                                } else {
                                    "Shared (${uiState.audioDiagnostics.audioApiName})"
                                },
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                    }

                    if (uiState.isInRoom) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Button(
                            onClick = onResyncNow,
                            enabled = !uiState.clockSync.isSyncing,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceTonalHigh,
                                contentColor = StudioAmber,
                                disabledContainerColor = SurfaceTonal,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.clockSync.isSyncing) "Syncing..." else "Resync Now",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 4. ROOM
            SettingsSection(title = "ROOM") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = serverDraft,
                                onValueChange = { next ->
                                    serverDraft = next
                                    serverDraftError = null
                                },
                                label = { Text("Server Address") },
                                placeholder = { Text("192.168.1.5:6969 or domain") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        serverDraftError = onApplyManualServerAddress(serverDraft)
                                    }
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SurfaceTonalElevated,
                                    unfocusedContainerColor = SurfaceTonalElevated,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = StudioAmber,
                                    focusedLabelColor = StudioAmber,
                                    unfocusedLabelColor = TextSecondary,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedPlaceholderColor = TextMuted,
                                    unfocusedPlaceholderColor = TextMuted
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioAmber,
                                modifier = Modifier
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        serverDraftError = onApplyManualServerAddress(serverDraft)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Apply",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ContrastInk
                                    )
                                }
                            }
                        }

                        serverDraftError?.let { errorText ->
                            Text(
                                text = errorText,
                                fontSize = 11.sp,
                                color = StatusCoral,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }

                    if (uiState.isInRoom) {
                        Button(
                            onClick = {
                                onLeaveRoom()
                                onBack()
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusCoral.copy(alpha = 0.14f),
                                contentColor = StatusCoral
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Leave Room (${uiState.activeRoom?.roomCode})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Button(
                            onClick = onOpenRoomSheetFromSettings,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceTonalHigh,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = StudioAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Create or Join Sync Room",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (!isIgnoringBatteryOptimizations) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceGrouped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            val batteryIntent =
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            try {
                                context.startActivity(batteryIntent)
                            } catch (_: Exception) {
                                val fallbackIntent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                try {
                                    context.startActivity(fallbackIntent)
                                } catch (_: Exception) {
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Background Reliability",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Allow unrestricted battery for screen off sync",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceGrouped,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = HairlineDivider
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

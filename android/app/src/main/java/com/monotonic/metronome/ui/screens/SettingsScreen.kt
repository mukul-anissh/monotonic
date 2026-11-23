package com.monotonic.metronome.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monotonic.metronome.data.PreferencesRepository
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.AccentCyan
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.DarkBackground
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurface
import com.monotonic.metronome.ui.theme.DarkSurfaceVariant
import com.monotonic.metronome.ui.theme.ErrorRose
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
    onServerUrlChanged: (String) -> Unit,
    onLeaveRoom: () -> Unit,
    onOpenRoomSheetFromSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            Surface(
                color = DarkBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to metronome",
                            tint = TextPrimary
                        )
                    }
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Audio & Sound
            SettingsCard(title = "Audio & Sound") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Master Volume",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "${(uiState.masterVolume * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = AccentAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Slider(
                    value = uiState.masterVolume,
                    onValueChange = onMasterVolumeChanged,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentAmber,
                        activeTrackColor = AccentAmber,
                        inactiveTrackColor = DarkSurfaceVariant
                    )
                )

                Text(
                    text = "Click Sound",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClickSoundOption.entries.forEach { option ->
                        val selected = uiState.clickSound == option
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) AccentAmber.copy(alpha = 0.2f) else DarkSurfaceVariant,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) AccentAmber else DarkBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onClickSoundSelected(option) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) AccentAmber else TextSecondary
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = DarkBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Visual Beat Flash",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Pulse visualizer frame edge on each beat",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = uiState.visualBeatFlash,
                        onCheckedChange = onVisualBeatFlashChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBackground,
                            checkedTrackColor = AccentEmerald
                        )
                    )
                }
            }

            // 2. Hardware Calibration
            SettingsCard(title = "Hardware Calibration") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manual Timing Offset",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Override OEM audio driver skew (-50ms to +50ms)",
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
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
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

                OutlinedButton(
                    onClick = { onManualOffsetChanged(0) },
                    enabled = uiState.manualOffsetMs != 0,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset to 0 ms")
                }
            }

            // 3. Sync Diagnostics
            SettingsCard(title = "Sync Diagnostics") {
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
                        value = "Offline / Solo Mode",
                        valueColor = TextSecondary
                    )
                }

                HorizontalDivider(color = DarkBorder)

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
                DiagnosticRow(
                    label = "Oboe Sharing Mode",
                    value = if (uiState.audioDiagnostics.isExclusiveMode) {
                        "Exclusive (${uiState.audioDiagnostics.audioApiName})"
                    } else {
                        "Shared (${uiState.audioDiagnostics.audioApiName})"
                    },
                    valueColor = if (uiState.audioDiagnostics.isExclusiveMode) {
                        AccentEmerald
                    } else {
                        AccentCyan
                    }
                )

                if (uiState.isInRoom) {
                    OutlinedButton(
                        onClick = onResyncNow,
                        enabled = !uiState.clockSync.isSyncing,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.clockSync.isSyncing) "Syncing..." else "Resync Now",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 4. Room & Server
            SettingsCard(title = "Room & Server") {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChanged,
                    label = { Text("Server WebSocket URL") },
                    placeholder = { Text("ws://192.168.1.100:8000/ws") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAmber,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (uiState.isInRoom) {
                    OutlinedButton(
                        onClick = {
                            onLeaveRoom()
                            onBack()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ErrorRose.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRose),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Leave Room (${uiState.activeRoom?.roomCode})",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenRoomSheetFromSettings,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create or Join Sync Room", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AccentAmber
            )
            content()
        }
    }
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
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

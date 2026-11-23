package com.monotonic.metronome.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monotonic.metronome.model.AudioDiagnostics
import com.monotonic.metronome.sync.ClockSyncState
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.AccentCyan
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurface
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun DiagnosticsCard(
    clockSync: ClockSyncState,
    audioDiagnostics: AudioDiagnostics,
    onResyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Sync Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    val summary = if (clockSync.isSynced) {
                        String.format(
                            Locale.US,
                            "RTT %.1f ms  •  Jitter %.2f ms  •  HW %.1f ms",
                            clockSync.minRttMs,
                            clockSync.jitterMs,
                            audioDiagnostics.hardwareLatencyMs
                        )
                    } else if (clockSync.isSyncing) {
                        "Synchronizing monotonic clocks..."
                    } else {
                        "Waiting for initial clock burst"
                    }
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (clockSync.isSynced) AccentEmerald else AccentAmber
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse diagnostics" else "Expand diagnostics",
                    tint = TextSecondary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = DarkBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    DiagnosticRow(
                        label = "Min / Median RTT",
                        value = String.format(
                            Locale.US,
                            "%.2f ms / %.2f ms",
                            clockSync.minRttMs,
                            clockSync.medianRttMs
                        )
                    )
                    DiagnosticRow(
                        label = "Clock Offset",
                        value = String.format(Locale.US, "%+.3f ms", clockSync.offsetMs)
                    )
                    DiagnosticRow(
                        label = "Estimated Sync Jitter (σ)",
                        value = String.format(Locale.US, "%.3f ms", clockSync.jitterMs)
                    )
                    DiagnosticRow(
                        label = "Oboe Hardware Latency",
                        value = String.format(
                            Locale.US,
                            "%.2f ms",
                            audioDiagnostics.hardwareLatencyMs
                        )
                    )
                    DiagnosticRow(
                        label = "Active Sample Rate",
                        value = "${audioDiagnostics.sampleRateHz} Hz"
                    )
                    DiagnosticRow(
                        label = "Oboe Sharing Mode",
                        value = if (audioDiagnostics.isExclusiveMode) {
                            "Exclusive (${audioDiagnostics.audioApiName})"
                        } else {
                            "Shared (${audioDiagnostics.audioApiName})"
                        },
                        valueColor = if (audioDiagnostics.isExclusiveMode) {
                            AccentEmerald
                        } else {
                            AccentCyan
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onResyncNow,
                        enabled = !clockSync.isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (clockSync.isSyncing) "Syncing..." else "Resync Now",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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

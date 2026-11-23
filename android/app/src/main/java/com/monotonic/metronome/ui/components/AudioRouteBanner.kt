package com.monotonic.metronome.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monotonic.metronome.model.AudioRouteState
import com.monotonic.metronome.model.AudioRouteStatus
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.ErrorRose
import com.monotonic.metronome.ui.theme.TextSecondary
import com.monotonic.metronome.ui.theme.WarningAmber

@Composable
fun AudioRouteBanner(
    routeState: AudioRouteState,
    modifier: Modifier = Modifier
) {
    val isWarning = routeState.status != AudioRouteStatus.WIRED_READY
    val borderColor = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> AccentEmerald.copy(alpha = 0.4f)
        AudioRouteStatus.BLUETOOTH_WARNING -> ErrorRose.copy(alpha = 0.7f)
        AudioRouteStatus.SPEAKER_WARNING -> WarningAmber.copy(alpha = 0.6f)
    }
    val containerColor = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> AccentEmerald.copy(alpha = 0.08f)
        AudioRouteStatus.BLUETOOTH_WARNING -> ErrorRose.copy(alpha = 0.14f)
        AudioRouteStatus.SPEAKER_WARNING -> WarningAmber.copy(alpha = 0.12f)
    }
    val iconColor = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> AccentEmerald
        AudioRouteStatus.BLUETOOTH_WARNING -> ErrorRose
        AudioRouteStatus.SPEAKER_WARNING -> WarningAmber
    }
    val icon = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> Icons.Rounded.Headphones
        AudioRouteStatus.BLUETOOTH_WARNING -> Icons.Rounded.BluetoothAudio
        AudioRouteStatus.SPEAKER_WARNING -> Icons.AutoMirrored.Rounded.VolumeUp
    }

    val title = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> "Wired Audio Active (${routeState.deviceLabel})"
        AudioRouteStatus.BLUETOOTH_WARNING -> "Bluetooth Audio Detected (${routeState.deviceLabel})"
        AudioRouteStatus.SPEAKER_WARNING -> "Phone Speaker Active"
    }

    val subtitle = when (routeState.status) {
        AudioRouteStatus.WIRED_READY -> "Low-latency wired path ready for <10ms ensemble sync."
        AudioRouteStatus.BLUETOOTH_WARNING ->
            "Bluetooth codecs add 100–250ms of unpredictable buffer delay. Plug in 3.5mm or USB-C earphones."
        AudioRouteStatus.SPEAKER_WARNING ->
            "Plug in 3.5mm or USB-C wired earphones to avoid acoustic bleed and speaker latency."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isWarning) iconColor else AccentEmerald
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

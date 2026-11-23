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
import com.monotonic.metronome.ui.theme.ErrorRose
import com.monotonic.metronome.ui.theme.TextSecondary
import com.monotonic.metronome.ui.theme.WarningAmber

/**
 * Compact warning banner displayed only while synced to a room when wired
 * earphones (3.5mm or USB-C) are not connected.
 */
@Composable
fun AudioRouteBanner(
    routeState: AudioRouteState,
    modifier: Modifier = Modifier
) {
    if (routeState.status == AudioRouteStatus.WIRED_READY) return

    val isBluetooth = routeState.status == AudioRouteStatus.BLUETOOTH_WARNING
    val accentColor = if (isBluetooth) ErrorRose else WarningAmber
    val icon = if (isBluetooth) {
        Icons.Rounded.BluetoothAudio
    } else {
        Icons.AutoMirrored.Rounded.VolumeUp
    }
    val title = if (isBluetooth) {
        "Bluetooth Active (${routeState.deviceLabel})"
    } else {
        "Phone Speaker Active"
    }
    val message = if (isBluetooth) {
        "Plug in wired 3.5mm/USB-C earphones to avoid 100–250ms wireless audio delay."
    } else {
        "Plug in wired 3.5mm/USB-C earphones for sub-10ms ensemble sync."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

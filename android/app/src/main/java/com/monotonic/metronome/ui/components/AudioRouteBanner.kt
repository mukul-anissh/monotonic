package com.monotonic.metronome.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.model.AudioRouteState
import com.monotonic.metronome.model.AudioRouteStatus
import com.monotonic.metronome.ui.theme.StatusAmber
import com.monotonic.metronome.ui.theme.StatusCoral
import com.monotonic.metronome.ui.theme.SurfaceTonal
import com.monotonic.metronome.ui.theme.TextSecondary

/**
 * Slim borderless tonal pill displayed beneath the top bar only when synced to a room
 * without wired earphones (3.5mm or USB-C).
 */
@Composable
fun AudioRouteBanner(
    routeState: AudioRouteState,
    modifier: Modifier = Modifier
) {
    if (routeState.status == AudioRouteStatus.WIRED_READY) return

    val isBluetooth = routeState.status == AudioRouteStatus.BLUETOOTH_WARNING
    val dotAccent = if (isBluetooth) StatusCoral else StatusAmber
    val icon = if (isBluetooth) {
        Icons.Rounded.BluetoothAudio
    } else {
        Icons.AutoMirrored.Rounded.VolumeUp
    }
    val text = if (isBluetooth) {
        "Bluetooth (${routeState.deviceLabel}) · Plug in wired earphones"
    } else {
        "Phone speaker active · Use wired earphones for sync"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = SurfaceTonal
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = dotAccent,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

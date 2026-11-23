package com.monotonic.metronome.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurfaceVariant
import com.monotonic.metronome.ui.theme.TextSecondary

/**
 * VSYNC-synced beat pad indicator polling lock-free atomic state directly from
 * the C++ Oboe engine so visual beat flashes track actual audio synthesis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeatIndicator(
    beatsPerBar: Int,
    isPlaying: Boolean,
    readCurrentBeatIndex: () -> Int,
    readBeatSequence: () -> Long,
    modifier: Modifier = Modifier
) {
    var activeBeat by remember { mutableIntStateOf(-1) }
    var pulseStrength by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, beatsPerBar) {
        if (!isPlaying) {
            activeBeat = -1
            pulseStrength = 0f
            return@LaunchedEffect
        }

        var lastSeq = readBeatSequence()
        var lastTriggerFrameNs = 0L

        while (true) {
            withFrameNanos { frameTimeNs ->
                val beat = readCurrentBeatIndex()
                val seq = readBeatSequence()
                if (seq != lastSeq && beat >= 0) {
                    lastSeq = seq
                    lastTriggerFrameNs = frameTimeNs
                }
                activeBeat = beat
                pulseStrength = if (lastTriggerFrameNs > 0L && beat >= 0) {
                    val elapsedMs = (frameTimeNs - lastTriggerFrameNs) / 1_000_000f
                    (1f - (elapsedMs / 180f)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    val padSize = when {
        beatsPerBar <= 4 -> 56.dp
        beatsPerBar <= 6 -> 48.dp
        beatsPerBar <= 8 -> 42.dp
        else -> 36.dp
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 6
    ) {
        for (i in 0 until beatsPerBar) {
            val isAccent = (i == 0)
            val isCurrent = isPlaying && (activeBeat == i)
            val glowColor = if (isAccent) AccentAmber else AccentEmerald
            val scale = if (isCurrent) 1f + (0.18f * pulseStrength) else 1f
            val fillAlpha = if (isCurrent) 0.35f + (0.65f * pulseStrength) else 0f

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(padSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(
                        elevation = if (isCurrent && pulseStrength > 0.05f) 14.dp else 0.dp,
                        shape = CircleShape,
                        ambientColor = glowColor,
                        spotColor = glowColor
                    )
                    .clip(CircleShape)
                    .background(
                        color = if (isCurrent) {
                            glowColor.copy(alpha = fillAlpha)
                        } else {
                            DarkSurfaceVariant
                        }
                    )
                    .border(
                        width = if (isCurrent) 2.5.dp else 1.5.dp,
                        color = when {
                            isCurrent -> glowColor
                            isAccent -> AccentAmber.copy(alpha = 0.45f)
                            else -> DarkBorder
                        },
                        shape = CircleShape
                    )
            ) {
                Text(
                    text = "${i + 1}",
                    fontSize = if (beatsPerBar > 8) 14.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) Color(0xFF0A0C10) else TextSecondary
                )
            }
        }
    }
}

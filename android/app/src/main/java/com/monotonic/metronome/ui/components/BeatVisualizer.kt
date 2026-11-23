package com.monotonic.metronome.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.AccentEmerald
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurfaceVariant
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary

/**
 * Interactive local per-beat accent visualizer synced directly to the C++ Oboe
 * engine's lock-free atomic beat index and sequence counter via Compose VSYNC frames.
 *
 * Tapping any beat block cycles local audio state: Accent (2) -> Normal (1) -> Mute (0).
 */
@Composable
fun BeatVisualizer(
    beatsPerBar: Int,
    accentLevels: List<Int>,
    isPlaying: Boolean,
    visualBeatFlash: Boolean,
    readCurrentBeatIndex: () -> Int,
    readBeatSequence: () -> Long,
    onCycleBeatAccent: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
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
                    (1f - (elapsedMs / 170f)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    val adaptedLevels = remember(accentLevels, beatsPerBar) {
        AccentPattern.adaptPattern(accentLevels, beatsPerBar)
    }

    val flashColor = remember(activeBeat, adaptedLevels) {
        val level = adaptedLevels.getOrElse(activeBeat) { AccentPattern.ACCENT_NORMAL }
        when (level) {
            AccentPattern.ACCENT_HIGH -> AccentAmber
            AccentPattern.ACCENT_NORMAL -> AccentEmerald
            else -> TextSecondary
        }
    }

    val containerBorderColor = if (visualBeatFlash && isPlaying && activeBeat >= 0 && pulseStrength > 0.05f) {
        flashColor.copy(alpha = 0.25f + 0.55f * pulseStrength)
    } else {
        DarkBorder
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF13161E))
            .border(1.5.dp, containerBorderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val maxRowSize = if (beatsPerBar > 8) (beatsPerBar + 1) / 2 else beatsPerBar
        val rows = remember(beatsPerBar, maxRowSize) {
            (0 until beatsPerBar).chunked(maxRowSize)
        }
        val rowHeight = if (rows.size > 1) 78.dp else 112.dp

        rows.forEach { rowIndices ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(if (beatsPerBar > 8) 6.dp else 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                rowIndices.forEach { beatIdx ->
                    val level = adaptedLevels.getOrElse(beatIdx) { AccentPattern.ACCENT_NORMAL }
                    val isCurrent = isPlaying && activeBeat == beatIdx
                    val targetHeight: Dp = when (level) {
                        AccentPattern.ACCENT_HIGH -> rowHeight
                        AccentPattern.ACCENT_NORMAL -> rowHeight * 0.68f
                        else -> rowHeight * 0.34f
                    }
                    val animatedHeight by animateDpAsState(
                        targetValue = targetHeight,
                        animationSpec = tween(durationMillis = 120),
                        label = "beatBlockHeight"
                    )

                    val baseColor = when (level) {
                        AccentPattern.ACCENT_HIGH -> AccentAmber
                        AccentPattern.ACCENT_NORMAL -> AccentEmerald
                        else -> TextSecondary
                    }

                    val activeGlowAlpha = if (level == AccentPattern.ACCENT_MUTE) {
                        0.18f + 0.22f * pulseStrength
                    } else {
                        0.42f + 0.58f * pulseStrength
                    }

                    val fillColor = when {
                        isCurrent -> baseColor.copy(alpha = activeGlowAlpha)
                        level == AccentPattern.ACCENT_HIGH -> AccentAmber.copy(alpha = 0.22f)
                        level == AccentPattern.ACCENT_NORMAL -> AccentEmerald.copy(alpha = 0.14f)
                        else -> DarkSurfaceVariant.copy(alpha = 0.55f)
                    }

                    val borderColor = when {
                        isCurrent -> baseColor
                        level == AccentPattern.ACCENT_HIGH -> AccentAmber.copy(alpha = 0.65f)
                        level == AccentPattern.ACCENT_NORMAL -> AccentEmerald.copy(alpha = 0.45f)
                        else -> DarkBorder
                    }

                    val scale = if (isCurrent && level != AccentPattern.ACCENT_MUTE) {
                        1f + 0.06f * pulseStrength
                    } else {
                        1f
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(animatedHeight)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .shadow(
                                elevation = if (isCurrent && level != AccentPattern.ACCENT_MUTE && pulseStrength > 0.1f) 12.dp else 0.dp,
                                shape = RoundedCornerShape(12.dp),
                                ambientColor = baseColor,
                                spotColor = baseColor
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(fillColor)
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = borderColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCycleBeatAccent(beatIdx)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${beatIdx + 1}",
                            fontSize = if (beatsPerBar > 10) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isCurrent && level != AccentPattern.ACCENT_MUTE -> Color(0xFF0A0C10)
                                isCurrent -> TextPrimary
                                level == AccentPattern.ACCENT_MUTE -> TextSecondary.copy(alpha = 0.45f)
                                else -> TextPrimary.copy(alpha = 0.9f)
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap any beat to cycle: Accent • Normal • Mute",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "Local only",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}

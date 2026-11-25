package com.monotonic.metronome.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.ui.theme.BeatMuteSurface
import com.monotonic.metronome.ui.theme.BeatNormalSurface
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextSecondary

/**
 * Borderless animated per-beat accent strip rendered directly on the dark canvas,
 * synced to the C++ Oboe engine's lock-free atomic beat index and sequence counter.
 *
 * Tapping any beat cycles local state: Normal (1) -> Accent (2) -> Mute (0) -> Normal (1).
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
    val view = LocalView.current
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

    val maxRowSize = if (beatsPerBar > 8) (beatsPerBar + 1) / 2 else beatsPerBar
    val rows = remember(beatsPerBar, maxRowSize) {
        (0 until beatsPerBar).chunked(maxRowSize)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rows.forEach { rowIndices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (beatsPerBar > 8) 6.dp else 10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    rowIndices.forEach { beatIdx ->
                        val level = adaptedLevels.getOrElse(beatIdx) { AccentPattern.ACCENT_NORMAL }
                        val isCurrent = isPlaying && activeBeat == beatIdx
                        val activePulse = if (isCurrent) {
                            if (visualBeatFlash) pulseStrength else 0.7f
                        } else {
                            0f
                        }

                        val targetHeight: Dp = when (level) {
                            AccentPattern.ACCENT_HIGH -> 56.dp
                            AccentPattern.ACCENT_NORMAL -> 36.dp
                            else -> 14.dp
                        }
                        val animatedHeight by animateDpAsState(
                            targetValue = targetHeight,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                            label = "beatBarHeight"
                        )

                        val targetFillColor = when {
                            isCurrent && level == AccentPattern.ACCENT_HIGH ->
                                StudioAmber.copy(alpha = 0.55f + 0.45f * activePulse)
                            isCurrent && level == AccentPattern.ACCENT_NORMAL ->
                                StudioAmber.copy(alpha = 0.42f + 0.50f * activePulse)
                            isCurrent ->
                                Color(0xFF282C38)
                            level == AccentPattern.ACCENT_HIGH ->
                                StudioAmber.copy(alpha = 0.25f)
                            level == AccentPattern.ACCENT_NORMAL ->
                                BeatNormalSurface
                            else ->
                                BeatMuteSurface
                        }
                        val animatedFillColor by animateColorAsState(
                            targetValue = targetFillColor,
                            animationSpec = tween(durationMillis = if (isCurrent) 55 else 150),
                            label = "beatBarColor"
                        )

                        val scaleX = if (isCurrent && visualBeatFlash && level != AccentPattern.ACCENT_MUTE) {
                            1f + 0.03f * pulseStrength
                        } else {
                            1f
                        }
                        val scaleY = if (isCurrent && visualBeatFlash && level != AccentPattern.ACCENT_MUTE) {
                            1f + 0.06f * pulseStrength
                        } else {
                            1f
                        }

                        val cornerRadius = if (beatsPerBar > 10) 7.dp else 10.dp

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onCycleBeatAccent(beatIdx)
                                }
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(animatedHeight)
                                        .graphicsLayer {
                                            this.scaleX = scaleX
                                            this.scaleY = scaleY
                                            transformOrigin = TransformOrigin(0.5f, 1f)
                                        }
                                        .shadow(
                                            elevation = if (isCurrent && visualBeatFlash &&
                                                level != AccentPattern.ACCENT_MUTE && pulseStrength > 0.1f
                                            ) {
                                                12.dp
                                            } else {
                                                0.dp
                                            },
                                            shape = RoundedCornerShape(cornerRadius),
                                            ambientColor = StudioAmber,
                                            spotColor = StudioAmber
                                        )
                                        .clip(RoundedCornerShape(cornerRadius))
                                        .background(animatedFillColor)
                                ) {
                                    if (level == AccentPattern.ACCENT_HIGH) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .align(Alignment.TopCenter)
                                                .background(StudioAmber)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "${beatIdx + 1}",
                                fontSize = if (beatsPerBar > 12) 10.sp else 11.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                color = when {
                                    isCurrent && level != AccentPattern.ACCENT_MUTE -> StudioAmber
                                    isCurrent -> TextSecondary
                                    level == AccentPattern.ACCENT_HIGH -> StudioAmber.copy(alpha = 0.78f)
                                    level == AccentPattern.ACCENT_NORMAL -> TextSecondary
                                    else -> TextMuted.copy(alpha = 0.55f)
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Tap beats to customize accents",
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

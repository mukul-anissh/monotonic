package com.monotonic.metronome.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.sync.SoloScheduler
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Apple camera-dial inspired horizontal draggable BPM tick ruler.
 * Renders crisp 1-BPM and 5-BPM ticks under a fixed Warm Studio Amber center needle
 * with velocity-sensitive fling, edge fading, and integer-step haptics.
 */
@Composable
fun TempoRuler(
    bpm: Double,
    onBpmPreview: (Int) -> Unit,
    onBpmCommit: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val minBpm = SoloScheduler.MIN_BPM.toInt()
    val maxBpm = SoloScheduler.MAX_BPM.toInt()

    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    val currentOnPreview by rememberUpdatedState(onBpmPreview)
    val currentOnCommit by rememberUpdatedState(onBpmCommit)

    val pxPerBpm = with(density) { 12.dp.toPx() }
    val needleZoneDp = 24.dp

    val rulerBpm = remember {
        Animatable(bpm.toFloat().coerceIn(minBpm.toFloat(), maxBpm.toFloat())).apply {
            updateBounds(minBpm.toFloat(), maxBpm.toFloat())
        }
    }

    var isUserInteracting by remember { mutableStateOf(false) }
    var gestureToken by remember { mutableIntStateOf(0) }
    var lastHapticBpm by remember { mutableIntStateOf(bpm.roundToInt().coerceIn(minBpm, maxBpm)) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(bpm) {
        if (!isUserInteracting) {
            val target = bpm.toFloat().coerceIn(minBpm.toFloat(), maxBpm.toFloat())
            if (abs(rulerBpm.value - target) > 0.05f) {
                rulerBpm.animateTo(target, animationSpec = tween(durationMillis = 150))
            } else {
                rulerBpm.snapTo(target)
            }
            lastHapticBpm = target.roundToInt().coerceIn(minBpm, maxBpm)
        }
    }

    val labelStyle = remember {
        TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum"
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "Tempo ruler" }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        gestureToken += 1
                        flingJob?.cancel()
                        isUserInteracting = true
                        velocityTracker.resetTracking()
                        lastHapticBpm = rulerBpm.value.roundToInt().coerceIn(minBpm, maxBpm)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val next = (rulerBpm.value - dragAmount / pxPerBpm)
                            .coerceIn(minBpm.toFloat(), maxBpm.toFloat())
                        scope.launch {
                            rulerBpm.snapTo(next)
                        }
                        val rounded = next.roundToInt().coerceIn(minBpm, maxBpm)
                        if (rounded != lastHapticBpm) {
                            lastHapticBpm = rounded
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnPreview(rounded)
                        }
                    },
                    onDragEnd = {
                        val token = gestureToken
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        val velocityBpmPerSec = -velocityPxPerSec / pxPerBpm
                        flingJob = scope.launch {
                            if (abs(velocityBpmPerSec) > 8f) {
                                rulerBpm.animateDecay(
                                    initialVelocity = velocityBpmPerSec,
                                    animationSpec = exponentialDecay(frictionMultiplier = 2.2f)
                                ) {
                                    val rounded = value.roundToInt().coerceIn(minBpm, maxBpm)
                                    if (rounded != lastHapticBpm) {
                                        lastHapticBpm = rounded
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        currentOnPreview(rounded)
                                    }
                                }
                            }
                            val targetInt = rulerBpm.value.roundToInt().coerceIn(minBpm, maxBpm)
                            rulerBpm.animateTo(
                                targetValue = targetInt.toFloat(),
                                animationSpec = spring(stiffness = 600f, dampingRatio = 0.85f)
                            )
                            currentOnPreview(targetInt)
                            currentOnCommit(targetInt.toDouble())
                            if (gestureToken == token) {
                                isUserInteracting = false
                            }
                        }
                    },
                    onDragCancel = {
                        val token = gestureToken
                        flingJob = scope.launch {
                            val targetInt = rulerBpm.value.roundToInt().coerceIn(minBpm, maxBpm)
                            rulerBpm.animateTo(
                                targetValue = targetInt.toFloat(),
                                animationSpec = spring(stiffness = 600f, dampingRatio = 0.85f)
                            )
                            currentOnPreview(targetInt)
                            currentOnCommit(targetInt.toDouble())
                            if (gestureToken == token) {
                                isUserInteracting = false
                            }
                        }
                    }
                )
            }
    ) {
        val centerX = size.width * 0.5f
        val halfWidth = (size.width * 0.5f).coerceAtLeast(1f)
        val currentBpm = rulerBpm.value

        val baselineY = size.height - 8.dp.toPx()
        val majorHeight = 24.dp.toPx()
        val minorHeight = 12.dp.toPx()
        val needleHeight = 34.dp.toPx()
        val needleZonePx = needleZoneDp.toPx()

        val visibleSpan = halfWidth / pxPerBpm + 2f
        val startTick = floor(currentBpm - visibleSpan).toInt().coerceAtLeast(minBpm)
        val endTick = ceil(currentBpm + visibleSpan).toInt().coerceAtMost(maxBpm)

        for (tick in startTick..endTick) {
            val x = centerX + (tick - currentBpm) * pxPerBpm
            val distFromCenter = abs(x - centerX)
            val edgeAlpha = (1f - (distFromCenter / halfWidth).pow(1.7f)).coerceIn(0f, 1f)
            if (edgeAlpha <= 0.01f) continue

            val isFiveStep = (tick % 5 == 0)
            val isTenStep = (tick % 10 == 0)

            val tickHeight = if (isFiveStep) majorHeight else minorHeight
            val strokePx = if (isFiveStep) 1.6.dp.toPx() else 1.dp.toPx()
            val tickColor = if (isFiveStep) {
                TextPrimary.copy(alpha = 0.55f * edgeAlpha)
            } else {
                TextSecondary.copy(alpha = 0.34f * edgeAlpha)
            }

            drawLine(
                color = tickColor,
                start = Offset(x, baselineY - tickHeight),
                end = Offset(x, baselineY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )

            if (isTenStep && distFromCenter > needleZonePx) {
                val needleFade = ((distFromCenter - needleZonePx) / 14.dp.toPx()).coerceIn(0f, 1f)
                val labelAlpha = edgeAlpha * needleFade * 0.72f
                if (labelAlpha > 0.04f) {
                    val layout = textMeasurer.measure(text = tick.toString(), style = labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        color = TextMuted.copy(alpha = labelAlpha),
                        topLeft = Offset(
                            x = x - layout.size.width * 0.5f,
                            y = (baselineY - majorHeight - layout.size.height - 6.dp.toPx())
                                .coerceAtLeast(0f)
                        )
                    )
                }
            }
        }

        // Fixed Warm Studio Amber center needle + subtle halo
        val needleTopY = baselineY - needleHeight
        drawLine(
            color = StudioAmber.copy(alpha = 0.22f),
            start = Offset(centerX, needleTopY),
            end = Offset(centerX, baselineY + 1.dp.toPx()),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = StudioAmber,
            start = Offset(centerX, needleTopY),
            end = Offset(centerX, baselineY + 1.dp.toPx()),
            strokeWidth = 2.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = StudioAmber,
            radius = 2.8.dp.toPx(),
            center = Offset(centerX, needleTopY - 4.dp.toPx())
        )
    }
}

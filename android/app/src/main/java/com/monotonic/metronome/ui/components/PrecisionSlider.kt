package com.monotonic.metronome.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.monotonic.metronome.ui.theme.ContrastSurface
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Minimal continuous slider with smooth track, crisp pill thumb, and Warm Studio Amber active fill.
 * Supports unipolar ranges (e.g. 0f..1f) and bipolar center-zero ranges (e.g. -50f..50f).
 */
@Composable
fun PrecisionSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    bipolarCenter: Float? = null,
    snapToIntegers: Boolean = false,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).coerceAtLeast(1e-5f)

    var isDragging by remember { mutableStateOf(false) }
    var lastSnapInt by remember { mutableIntStateOf(value.roundToInt()) }

    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.12f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "sliderThumbScale"
    )

    val thumbWidthPx = with(density) { 20.dp.toPx() }
    val thumbHeightPx = with(density) { 14.dp.toPx() }
    val trackHeightPx = with(density) { 4.dp.toPx() }
    val thumbInsetPx = thumbWidthPx * 0.5f

    fun dispatchForX(x: Float, totalWidth: Float) {
        val usable = (totalWidth - thumbInsetPx * 2f).coerceAtLeast(1f)
        val fraction = ((x - thumbInsetPx) / usable).coerceIn(0f, 1f)
        val raw = start + fraction * span
        if (snapToIntegers) {
            val snapped = raw.roundToInt().toFloat().coerceIn(start, end)
            val snappedInt = snapped.roundToInt()
            if (snappedInt != lastSnapInt) {
                lastSnapInt = snappedInt
                if (snappedInt == 0 || (bipolarCenter == null)) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            currentOnValueChange(snapped)
        } else {
            currentOnValueChange(raw.coerceIn(start, end))
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(start, end, snapToIntegers, bipolarCenter) {
                detectTapGestures(
                    onTap = { offset ->
                        lastSnapInt = value.roundToInt()
                        dispatchForX(offset.x, size.width.toFloat())
                    }
                )
            }
            .pointerInput(start, end, snapToIntegers, bipolarCenter) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        lastSnapInt = value.roundToInt()
                        dispatchForX(offset.x, size.width.toFloat())
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dispatchForX(change.position.x, size.width.toFloat())
                    }
                )
            }
    ) {
        val centerY = size.height * 0.5f
        val usableWidth = (size.width - thumbInsetPx * 2f).coerceAtLeast(1f)
        val trackLeft = thumbInsetPx
        val trackTop = centerY - trackHeightPx * 0.5f
        val trackCorner = CornerRadius(trackHeightPx * 0.5f, trackHeightPx * 0.5f)

        // Inactive track
        drawRoundRect(
            color = SurfaceTonalHigh,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(usableWidth, trackHeightPx),
            cornerRadius = trackCorner
        )

        val valueFraction = ((value - start) / span).coerceIn(0f, 1f)
        val thumbX = trackLeft + valueFraction * usableWidth

        if (bipolarCenter != null) {
            val zeroFraction = ((bipolarCenter - start) / span).coerceIn(0f, 1f)
            val zeroX = trackLeft + zeroFraction * usableWidth

            // Subtle zero reference mark
            drawRoundRect(
                color = TextMuted.copy(alpha = 0.55f),
                topLeft = Offset(zeroX - 1.dp.toPx(), centerY - 6.dp.toPx()),
                size = Size(2.dp.toPx(), 12.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            val fillLeft = min(zeroX, thumbX)
            val fillWidth = max(0f, max(zeroX, thumbX) - fillLeft)
            if (fillWidth > 0.5f) {
                drawRoundRect(
                    color = StudioAmber,
                    topLeft = Offset(fillLeft, trackTop),
                    size = Size(fillWidth, trackHeightPx),
                    cornerRadius = trackCorner
                )
            }
        } else {
            val fillWidth = (thumbX - trackLeft).coerceAtLeast(0f)
            if (fillWidth > 0.5f) {
                drawRoundRect(
                    color = StudioAmber,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(fillWidth, trackHeightPx),
                    cornerRadius = trackCorner
                )
            }
        }

        // Crisp pill thumb
        val scaledW = thumbWidthPx * thumbScale
        val scaledH = thumbHeightPx * thumbScale
        drawRoundRect(
            color = ContrastSurface,
            topLeft = Offset(thumbX - scaledW * 0.5f, centerY - scaledH * 0.5f),
            size = Size(scaledW, scaledH),
            cornerRadius = CornerRadius(scaledH * 0.5f, scaledH * 0.5f)
        )
    }
}

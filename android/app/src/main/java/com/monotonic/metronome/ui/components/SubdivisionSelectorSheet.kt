package com.monotonic.metronome.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceGrouped
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary

private data class SubdivisionOption(
    val value: Int,
    val multiplier: String,
    val title: String
)

private val SUBDIVISION_OPTIONS = listOf(
    SubdivisionOption(1, "1x", "Quarter"),
    SubdivisionOption(2, "2x", "Eighth"),
    SubdivisionOption(3, "3x", "Triplet"),
    SubdivisionOption(4, "4x", "Sixteenth")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubdivisionSelectorSheet(
    currentSubdivision: Int,
    onSelectSubdivision: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rows = SUBDIVISION_OPTIONS.chunked(2)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceGrouped,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "Subdivision",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Local to this device",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { opt ->
                            val selected = currentSubdivision == opt.value
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    StudioAmber.copy(alpha = 0.18f)
                                } else {
                                    SurfaceTonalHigh
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onSelectSubdivision(opt.value)
                                        onDismiss()
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = opt.multiplier,
                                            fontSize = 19.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) StudioAmber else TextPrimary
                                        )
                                        SubdivisionDots(
                                            count = opt.value,
                                            isSelected = selected
                                        )
                                    }
                                    Text(
                                        text = opt.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (selected) StudioAmber.copy(alpha = 0.9f) else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubdivisionDots(
    count: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isSelected) StudioAmber else TextSecondary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until count) {
            Box(
                modifier = Modifier
                    .size(if (i == 0) 6.dp else 4.5.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == 0) activeColor else activeColor.copy(alpha = 0.55f)
                    )
            )
        }
    }
}

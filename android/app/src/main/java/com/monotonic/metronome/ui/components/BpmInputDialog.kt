package com.monotonic.metronome.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.monotonic.metronome.sync.SoloScheduler
import com.monotonic.metronome.ui.theme.ContrastInk
import com.monotonic.metronome.ui.theme.ContrastSurface
import com.monotonic.metronome.ui.theme.StatusCoral
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceGrouped
import com.monotonic.metronome.ui.theme.SurfaceTonalElevated
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun BpmInputDialog(
    currentBpm: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val initialText = currentBpm.roundToInt().toString()
    var textValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(0, initialText.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    val parsed = textValue.text.toDoubleOrNull()
    val isValid = parsed != null &&
        parsed >= SoloScheduler.MIN_BPM &&
        parsed <= SoloScheduler.MAX_BPM

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = SurfaceGrouped,
        titleContentColor = TextPrimary,
        title = {
            Text(
                text = "Set Tempo",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = textValue,
                    onValueChange = { next ->
                        val filtered = next.text.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1 && filtered.length <= 6) {
                            textValue = next.copy(text = filtered)
                        }
                    },
                    label = { Text("BPM") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    isError = textValue.text.isNotEmpty() && !isValid,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (parsed != null && isValid) {
                                onConfirm(parsed)
                            }
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceTonalElevated,
                        unfocusedContainerColor = SurfaceTonalElevated,
                        errorContainerColor = SurfaceTonalElevated,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        cursorColor = StudioAmber,
                        focusedLabelColor = StudioAmber,
                        unfocusedLabelColor = TextSecondary,
                        errorLabelColor = StatusCoral,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        errorTextColor = StatusCoral
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Text(
                    text = "Allowed range: 20 to 500 BPM",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (textValue.text.isNotEmpty() && !isValid) StatusCoral else TextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsed != null && isValid) {
                        onConfirm(parsed)
                    }
                },
                enabled = isValid,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ContrastSurface,
                    contentColor = ContrastInk,
                    disabledContainerColor = SurfaceTonalHigh,
                    disabledContentColor = TextMuted
                )
            ) {
                Text("Confirm", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

package com.monotonic.metronome.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.model.AppUiState
import com.monotonic.metronome.ui.components.AudioRouteBanner
import com.monotonic.metronome.ui.theme.AccentAmber
import com.monotonic.metronome.ui.theme.DarkBorder
import com.monotonic.metronome.ui.theme.DarkSurface
import com.monotonic.metronome.ui.theme.DarkSurfaceVariant
import com.monotonic.metronome.ui.theme.ErrorRose
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary

@Composable
fun LobbyScreen(
    uiState: AppUiState,
    onServerUrlChanged: (String) -> Unit,
    onRoomCodeChanged: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val canCreate = uiState.serverUrl.isNotBlank() && !uiState.isConnectingAction
    val canJoin = uiState.serverUrl.isNotBlank() &&
        uiState.roomCodeInput.trim().length == 6 &&
        !uiState.isConnectingAction

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AccentAmber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = AccentAmber,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp)
                )
            }
            Column {
                Text(
                    text = "Monotonic",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sub-10ms ensemble clock sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        AudioRouteBanner(routeState = uiState.audioRoute)

        if (uiState.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ErrorRose.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ErrorRose.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorRose,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Sync Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )

                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChanged,
                    label = { Text("WebSocket Server URL") },
                    placeholder = { Text("ws://192.168.1.100:8000/ws") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAmber,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Point all musicians to the same local Wi-Fi laptop IP (ws://<ip>:8000/ws) or cloud URL (wss://.../ws).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = onCreateRoom,
                    enabled = canCreate,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentAmber,
                        contentColor = DarkSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    if (uiState.isConnectingAction) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = DarkSurface
                        )
                    } else {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Create Room",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = DarkBorder)
                    Text(
                        text = "OR JOIN",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = DarkBorder)
                }

                OutlinedTextField(
                    value = uiState.roomCodeInput,
                    onValueChange = { raw ->
                        val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }.take(6)
                        onRoomCodeChanged(cleaned)
                    },
                    label = { Text("6-Character Room Code") },
                    placeholder = { Text("ABC123") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (canJoin) onJoinRoom() }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAmber,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = onJoinRoom,
                    enabled = canJoin,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (canJoin) AccentAmber else DarkBorder
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Join Room",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

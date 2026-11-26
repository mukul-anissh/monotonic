package com.monotonic.metronome.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.monotonic.metronome.model.DiscoveredServer
import com.monotonic.metronome.model.RoomSnapshot
import com.monotonic.metronome.network.ServerUrlNormalizer
import com.monotonic.metronome.ui.theme.ContrastInk
import com.monotonic.metronome.ui.theme.ContrastSurface
import com.monotonic.metronome.ui.theme.HairlineDivider
import com.monotonic.metronome.ui.theme.StatusAmber
import com.monotonic.metronome.ui.theme.StatusCoral
import com.monotonic.metronome.ui.theme.StatusEmerald
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceGrouped
import com.monotonic.metronome.ui.theme.SurfaceTonal
import com.monotonic.metronome.ui.theme.SurfaceTonalElevated
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomConnectionSheet(
    serverUrl: String,
    hasExplicitSavedServer: Boolean,
    discoveredServers: List<DiscoveredServer>,
    isDiscoveringServers: Boolean,
    isServerEditorExpanded: Boolean,
    roomCodeInput: String,
    isConnecting: Boolean,
    errorMessage: String?,
    cameraPermissionDeniedHint: Boolean,
    onToggleServerEditor: () -> Unit,
    onRescanServers: () -> Unit,
    onSelectDiscoveredServer: (DiscoveredServer) -> Unit,
    onApplyManualServerAddress: (String) -> String?,
    onRoomCodeChanged: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onCameraPermissionDenied: () -> Unit,
    onDismissCameraPermissionHint: () -> Unit,
    onDismiss: () -> Unit,
    activeRoom: RoomSnapshot? = null,
    onCopyRoomCode: (String) -> Unit = {},
    onOpenShareQr: () -> Unit = {},
    onLeaveRoom: () -> Unit = {}
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isServerValid = remember(serverUrl) { ServerUrlNormalizer.normalize(serverUrl) != null }
    val canCreate = isServerValid && !isConnecting
    val canJoin = isServerValid && roomCodeInput.trim().length == 6 && !isConnecting

    val matchedDiscovered = remember(discoveredServers, serverUrl) {
        discoveredServers.firstOrNull { it.wsUrl.equals(serverUrl.trim(), ignoreCase = true) }
    }

    var manualAddressDraft by remember(isServerEditorExpanded, serverUrl) {
        val trimmed = serverUrl.trim()
        val canShorten = trimmed.endsWith("/ws") &&
            (ServerUrlNormalizer.isLanOrLocalUrl(trimmed) ||
                ServerUrlNormalizer.isRemoteSecureUrl(trimmed))
        mutableStateOf(
            if (canShorten) {
                ServerUrlNormalizer.extractHostDisplay(trimmed).ifBlank { trimmed }
            } else {
                trimmed
            }
        )
    }
    var manualAddressError by remember(isServerEditorExpanded) { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onOpenQrScanner()
        } else {
            onCameraPermissionDenied()
        }
    }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = SurfaceTonalElevated,
        unfocusedContainerColor = SurfaceTonalElevated,
        disabledContainerColor = SurfaceTonalElevated,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        cursorColor = StudioAmber,
        focusedLabelColor = StudioAmber,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedPlaceholderColor = TextMuted,
        unfocusedPlaceholderColor = TextMuted
    )

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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Sync Room",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Lock beats across phones over local Wi-Fi or cloud WebSocket.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            if (activeRoom != null) {
                ActiveRoomCard(
                    activeRoom = activeRoom,
                    onCopyRoomCode = onCopyRoomCode,
                    onOpenShareQr = onOpenShareQr,
                    onLeaveRoom = {
                        onLeaveRoom()
                        onDismiss()
                    }
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StatusCoral.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCoral,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            ServerStatusRow(
                serverUrl = serverUrl,
                hasExplicitSavedServer = hasExplicitSavedServer,
                matchedDiscovered = matchedDiscovered,
                discoveredCount = discoveredServers.size,
                isDiscovering = isDiscoveringServers,
                isExpanded = isServerEditorExpanded,
                onToggleExpand = onToggleServerEditor
            )

            if (discoveredServers.size > 1 && !isServerEditorExpanded) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(discoveredServers, key = { it.hostPortKey }) { server ->
                        val isSelected = server.wsUrl.equals(serverUrl.trim(), ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) StudioAmber.copy(alpha = 0.18f) else SurfaceTonalHigh,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onSelectDiscoveredServer(server) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) StudioAmber else StatusEmerald)
                                )
                                Text(
                                    text = server.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) StudioAmber else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isServerEditorExpanded) {
                ExpandableServerPicker(
                    serverUrl = serverUrl,
                    discoveredServers = discoveredServers,
                    isDiscovering = isDiscoveringServers,
                    manualAddressDraft = manualAddressDraft,
                    manualAddressError = manualAddressError,
                    fieldColors = fieldColors,
                    onRescanServers = onRescanServers,
                    onSelectDiscoveredServer = onSelectDiscoveredServer,
                    onManualAddressChanged = { next ->
                        manualAddressDraft = next
                        manualAddressError = null
                    },
                    onApplyManualAddress = {
                        val err = onApplyManualServerAddress(manualAddressDraft)
                        manualAddressError = err
                    }
                )
            }

            Button(
                onClick = onCreateRoom,
                enabled = canCreate,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ContrastSurface,
                    contentColor = ContrastInk,
                    disabledContainerColor = SurfaceTonalHigh,
                    disabledContentColor = TextMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = ContrastInk
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Room",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = HairlineDivider)
                Text(
                    text = "OR JOIN A ROOM",
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = HairlineDivider)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = roomCodeInput,
                    onValueChange = { raw ->
                        onRoomCodeChanged(raw.uppercase().filter { it.isLetterOrDigit() }.take(6))
                    },
                    label = { Text("6-Character Room Code") },
                    placeholder = { Text("ABC123") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 19.sp,
                        letterSpacing = 5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (canJoin) onJoinRoom() }
                    ),
                    colors = fieldColors,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceTonalHigh,
                    modifier = Modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !isConnecting) {
                            val hasCamera = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasCamera) {
                                onOpenQrScanner()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = StudioAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Scan QR",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }

            if (cameraPermissionDeniedHint) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceTonalElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Camera permission denied",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusAmber
                            )
                            Text(
                                text = "Type the 6-character code manually above to join.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = "Dismiss",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioAmber,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onDismissCameraPermissionHint)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onJoinRoom,
                enabled = canJoin,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceTonalHigh,
                    contentColor = StudioAmber,
                    disabledContainerColor = SurfaceTonal,
                    disabledContentColor = TextMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Login,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Join Room",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ActiveRoomCard(
    activeRoom: RoomSnapshot,
    onCopyRoomCode: (String) -> Unit,
    onOpenShareQr: () -> Unit,
    onLeaveRoom: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceTonalElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ACTIVE ROOM",
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = activeRoom.roomCode,
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = StudioAmber
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SurfaceTonalHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Group,
                            contentDescription = "Peers",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${activeRoom.participantCount}",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SurfaceTonalHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onCopyRoomCode(activeRoom.roomCode) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SurfaceTonalHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(onClick = onOpenShareQr)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = StudioAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share QR",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioAmber
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = StatusCoral.copy(alpha = 0.14f),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(onClick = onLeaveRoom)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = null,
                            tint = StatusCoral,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Leave",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusCoral
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusRow(
    serverUrl: String,
    hasExplicitSavedServer: Boolean,
    matchedDiscovered: DiscoveredServer?,
    discoveredCount: Int,
    isDiscovering: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isScanningWithoutSelection =
        matchedDiscovered == null &&
            isDiscovering &&
            (!hasExplicitSavedServer ||
                (discoveredCount > 1 && !ServerUrlNormalizer.isRemoteSecureUrl(serverUrl)))

    val infiniteTransition = rememberInfiniteTransition(label = "mdnsPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mdnsPulseAlpha"
    )

    val dotColor: Color
    val dotAlpha: Float
    val title: String
    val subtitle: String

    when {
        matchedDiscovered != null -> {
            dotColor = StatusEmerald
            dotAlpha = 1f
            title = matchedDiscovered.name
            subtitle = "Local Wi-Fi · ${matchedDiscovered.host}:${matchedDiscovered.port}"
        }

        isScanningWithoutSelection -> {
            dotColor = StatusAmber
            dotAlpha = pulseAlpha
            title = "Searching local Wi-Fi..."
            subtitle = if (discoveredCount > 1) {
                "$discoveredCount servers found — tap one below"
            } else {
                "Looking for Monotonic server on LAN"
            }
        }

        else -> {
            dotColor = TextSecondary
            dotAlpha = 1f
            title = "Saved Server"
            subtitle = ServerUrlNormalizer.extractHostDisplay(serverUrl).ifBlank { serverUrl }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceTonalElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = if (isExpanded) "Done" else "Change",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = StudioAmber,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ExpandableServerPicker(
    serverUrl: String,
    discoveredServers: List<DiscoveredServer>,
    isDiscovering: Boolean,
    manualAddressDraft: String,
    manualAddressError: String?,
    fieldColors: androidx.compose.material3.TextFieldColors,
    onRescanServers: () -> Unit,
    onSelectDiscoveredServer: (DiscoveredServer) -> Unit,
    onManualAddressChanged: (String) -> Unit,
    onApplyManualAddress: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceTonal,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LOCAL WI-FI SERVERS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onRescanServers)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Rescan local Wi-Fi",
                        tint = StudioAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Rescan",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioAmber
                    )
                }
            }

            if (discoveredServers.isEmpty()) {
                Text(
                    text = if (isDiscovering) {
                        "Scanning local Wi-Fi for _syncmetro._tcp servers..."
                    } else {
                        "No local servers found. Enter an IP or domain below."
                    },
                    fontSize = 12.sp,
                    color = TextMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discoveredServers.forEach { server ->
                        val isSelected = server.wsUrl.equals(serverUrl.trim(), ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                StudioAmber.copy(alpha = 0.14f)
                            } else {
                                SurfaceTonalElevated
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectDiscoveredServer(server) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(StatusEmerald)
                                    )
                                    Column {
                                        Text(
                                            text = server.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${server.host}:${server.port}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = StudioAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = HairlineDivider)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = manualAddressDraft,
                        onValueChange = onManualAddressChanged,
                        label = { Text("Server Address") },
                        placeholder = { Text("192.168.1.5:6969 or domain") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onApplyManualAddress() }
                        ),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioAmber,
                        modifier = Modifier
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onApplyManualAddress)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Apply",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ContrastInk
                            )
                        }
                    }
                }

                manualAddressError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(
                        text = errorText,
                        fontSize = 11.sp,
                        color = StatusCoral,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

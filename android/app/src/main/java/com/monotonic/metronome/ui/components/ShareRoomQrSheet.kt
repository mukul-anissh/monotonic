package com.monotonic.metronome.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monotonic.metronome.network.ServerUrlNormalizer
import com.monotonic.metronome.network.SyncRoomQrCodec
import com.monotonic.metronome.ui.theme.ContrastInk
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceGrouped
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextMuted
import com.monotonic.metronome.ui.theme.TextPrimary
import com.monotonic.metronome.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareRoomQrSheet(
    roomCode: String,
    serverUrl: String,
    onCopyRoomCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val qrPayload = remember(serverUrl, roomCode) {
        runCatching { SyncRoomQrCodec.encodeRoomInvite(serverUrl, roomCode) }.getOrNull()
    }
    val qrBitmap by produceState<Bitmap?>(initialValue = null, qrPayload) {
        value = if (qrPayload != null) {
            withContext(Dispatchers.Default) {
                QrCodeGenerator.generateQrBitmap(qrPayload, sizePx = 560)
            }
        } else {
            null
        }
    }
    val hostDisplay = remember(serverUrl) {
        ServerUrlNormalizer.extractHostDisplay(serverUrl).ifBlank { serverUrl }
    }

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
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Share Sync Room",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Scan from another phone to join on local Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                modifier = Modifier.size(220.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = qrBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR code for room $roomCode",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = ContrastInk
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = roomCode,
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = StudioAmber
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SurfaceTonalHigh,
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onCopyRoomCode(roomCode) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy Code",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }

            Text(
                text = "Server · $hostDisplay",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

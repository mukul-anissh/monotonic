package com.monotonic.metronome.ui.components

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.monotonic.metronome.network.QrParseResult
import com.monotonic.metronome.network.SyncRoomQrCodec
import com.monotonic.metronome.ui.theme.ObsidianCanvas
import com.monotonic.metronome.ui.theme.StatusAmber
import com.monotonic.metronome.ui.theme.StudioAmber
import com.monotonic.metronome.ui.theme.SurfaceTonalHigh
import com.monotonic.metronome.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerOverlay(
    onRoomInviteScanned: (serverUrl: String, roomCode: String) -> Unit,
    onServerUrlScanned: (serverUrl: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnRoomInvite by rememberUpdatedState(onRoomInviteScanned)
    val currentOnServerUrl by rememberUpdatedState(onServerUrlScanned)

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var invalidQrHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(invalidQrHint) {
        if (invalidQrHint != null) {
            delay(2200L)
            invalidQrHint = null
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        val analyzerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SyncMetroQrAnalyzer").apply { isDaemon = true }
        }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val handledSuccess = AtomicBoolean(false)

        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        var luminanceScratch: ByteArray? = null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                val provider = try {
                    cameraProviderFuture.get()
                } catch (_: Exception) {
                    cameraError = "Unable to start camera preview"
                    return@addListener
                }
                if (disposed) {
                    provider.unbindAll()
                    return@addListener
                }
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        if (disposed || handledSuccess.get()) return@setAnalyzer
                        val yPlane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                        val width = imageProxy.width
                        val height = imageProxy.height
                        if (width <= 0 || height <= 0) return@setAnalyzer

                        val yBytes = extractLuminanceBytes(yPlane, width, height, luminanceScratch)
                        luminanceScratch = yBytes

                        val source = PlanarYUVLuminanceSource(
                            yBytes,
                            width,
                            height,
                            0,
                            0,
                            width,
                            height,
                            false
                        )
                        val bitmap = BinaryBitmap(HybridBinarizer(source))
                        val decoded = try {
                            reader.decodeWithState(bitmap)
                        } catch (_: NotFoundException) {
                            null
                        } catch (_: Exception) {
                            null
                        } ?: return@setAnalyzer

                        val rawText = decoded.text ?: return@setAnalyzer
                        when (val parsed = SyncRoomQrCodec.parse(rawText)) {
                            is QrParseResult.RoomInvite -> {
                                if (handledSuccess.compareAndSet(false, true)) {
                                    mainExecutor.execute {
                                        if (!disposed) {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            currentOnRoomInvite(parsed.serverUrl, parsed.roomCode)
                                        }
                                    }
                                }
                            }

                            is QrParseResult.ServerOnly -> {
                                if (handledSuccess.compareAndSet(false, true)) {
                                    mainExecutor.execute {
                                        if (!disposed) {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            currentOnServerUrl(parsed.serverUrl)
                                        }
                                    }
                                }
                            }

                            is QrParseResult.Invalid -> {
                                mainExecutor.execute {
                                    if (!disposed) {
                                        invalidQrHint = parsed.reason
                                    }
                                }
                            }
                        }
                    } finally {
                        reader.reset()
                        imageProxy.close()
                    }
                }

                try {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    activeCamera = camera
                    hasFlash = camera.cameraInfo.hasFlashUnit()
                    isTorchOn = false
                } catch (_: Exception) {
                    cameraError = "Camera unavailable on this device"
                }
            },
            mainExecutor
        )

        onDispose {
            disposed = true
            try {
                activeCamera?.cameraControl?.enableTorch(false)
            } catch (_: Exception) {
            }
            activeCamera = null
            cameraProvider?.unbindAll()
            analyzerExecutor.shutdownNow()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Dark translucent scrim with clear rounded viewfinder cutout & warm amber frame
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(color = Color(0xB30D0E11))

            val frameSizePx = minOf(size.width * 0.68f, 252.dp.toPx())
            val left = (size.width - frameSizePx) / 2f
            val top = (size.height - frameSizePx) / 2f - 28.dp.toPx()
            val cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())

            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(frameSizePx, frameSizePx),
                cornerRadius = cornerRadius,
                blendMode = BlendMode.Clear
            )

            drawRoundRect(
                color = StudioAmber,
                topLeft = Offset(left, top),
                size = Size(frameSizePx, frameSizePx),
                cornerRadius = cornerRadius,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        // Top controls: Close (top-left) and Flashlight toggle (top-right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x99181A20))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close QR scanner",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (hasFlash) {
                IconButton(
                    onClick = {
                        val nextTorch = !isTorchOn
                        activeCamera?.cameraControl?.enableTorch(nextTorch)
                        isTorchOn = nextTorch
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isTorchOn) StudioAmber else Color(0x99181A20)
                        )
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = if (isTorchOn) "Turn flashlight off" else "Turn flashlight on",
                        tint = if (isTorchOn) ObsidianCanvas else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Bottom caption and non-intrusive hint pills
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(
                visible = invalidQrHint != null || cameraError != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val message = cameraError ?: invalidQrHint.orEmpty()
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SurfaceTonalHigh
                ) {
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = StatusAmber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            Text(
                text = "Point at host's room QR",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun extractLuminanceBytes(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    reuse: ByteArray?
): ByteArray {
    val targetSize = width * height
    val out = if (reuse != null && reuse.size == targetSize) reuse else ByteArray(targetSize)
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    if (pixelStride == 1 && rowStride == width) {
        buffer.get(out, 0, targetSize)
        return out
    }

    if (pixelStride == 1) {
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(out, y * width, width)
        }
        return out
    }

    for (y in 0 until height) {
        val rowStart = y * rowStride
        val destOffset = y * width
        for (x in 0 until width) {
            out[destOffset + x] = buffer.get(rowStart + x * pixelStride)
        }
    }
    return out
}

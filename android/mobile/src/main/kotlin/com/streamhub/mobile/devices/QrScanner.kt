package com.streamhub.mobile.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.streamhub.core.QrDecoder
import com.streamhub.core.model.UserCode
import java.util.concurrent.Executors

/**
 * Reading the pairing code off a television with the camera.
 *
 * The decoder is zxing, which is already here for the television's encoder, so
 * the only thing this needs is frames — hence CameraX and nothing else. ML Kit
 * would do the same job by shipping a second copy of a barcode engine and a
 * dependency on Play Services, on a screen used once per television.
 *
 * The permission is asked for here rather than at launch: it is wanted for one
 * button, on one screen, and the code can always be typed instead. A refusal is
 * therefore not a dead end and is not treated as one.
 */
@Composable
fun QrScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Square, because a QR is square; a 16:9 preview spends most of its
            // height on the wall around the television.
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            CameraPreview(onCode = onCode, lifecycleOwner = lifecycleOwner)
        } else {
            Text(
                text = if (asked) {
                    "Camera access was refused. Type the code instead."
                } else {
                    "Waiting for camera access…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onCode: (String) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val context = LocalContext.current
    // Read at the moment a code is found rather than captured when the analyser
    // was built, which may be several recompositions earlier.
    val deliver by rememberUpdatedState(onCode)

    // One thread for decoding, kept off the main one: a QR decode on a 1080p
    // frame is milliseconds, but enough of them on the UI thread is a stutter.
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Latched so a code sitting in front of the lens is delivered once rather
    // than on every frame for as long as it is visible.
    val found = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    // Only the newest frame matters; queueing them adds lag
                    // between moving the phone and the code being read.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (!found.get()) {
                            val text = image.decodeQr()
                            val code = UserCode.fromScan(text)
                            if (code != null && found.compareAndSet(false, true)) {
                                previewView.post { deliver(code) }
                            }
                        }
                    } finally {
                        // Not closing this stalls the pipeline after a couple of
                        // frames and the preview freezes with no error.
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(viewContext))

            previewView
        },
    )
}

/**
 * The Y plane of a YUV_420_888 frame is already a greyscale image, so the bytes
 * go straight to the decoder with no colour conversion. The stride is passed
 * along with them: camera planes are padded, and treating the padding as image
 * shears the frame into noise that never decodes.
 */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return QrDecoder.decode(bytes, plane.rowStride, width, height)
}

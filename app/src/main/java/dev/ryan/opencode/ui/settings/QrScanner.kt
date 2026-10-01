package dev.ryan.opencode.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the pairing QR in-app.
 *
 * The installer prints `http://HOST:PORT/auth/connect/CODE` as a QR. There is a
 * deep link for that, but a deep link still means switching to a browser or camera
 * app and back, and being mid-setup on a phone is exactly when that goes wrong.
 *
 * ## Why decode manually
 *
 * ZXing's `MultiFormatReader` normally wants a `Bitmap`, and converting every
 * frame is far too slow to feel live. The camera hands us a YUV_420_888 plane
 * whose first plane is already the luminance channel, so the frame can be wrapped
 * directly — no copy, no colour conversion, and the decoder only runs on frames
 * that plausibly contain a QR.
 *
 * Analysis is on a single background thread and the analyser is shut down when the
 * composable leaves, so leaving the scanner open does not keep decoding in the
 * background.
 */
@Composable
fun QrScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf<ContextCompatPermission?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = if (ok) ContextCompatPermission.Granted else ContextCompatPermission.Denied }

    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val already = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (already) granted = ContextCompatPermission.Granted else launcher.launch(Manifest.permission.CAMERA)
        requested = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan the pairing code") },
        text = {
            Box(Modifier.fillMaxSize().padding(4.dp)) {
                when {
                    granted == null && requested -> Text("Asking for camera access…")
                    granted == ContextCompatPermission.Denied ->
                        Text(
                            "Camera access was declined. You can still type the code — it is " +
                                "printed under the QR on the server.",
                            textAlign = TextAlign.Center,
                        )
                    else -> CameraPreview(onCode)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class ContextCompatPermission { Granted, Denied }

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val consumed = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { executor.shutdown() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        // Dropping stale frames keeps the viewfinder responsive when
                        // decoding cannot keep up; without this the preview lags.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(executor) { proxy ->
                        decode(proxy, consumed) { text -> currentOnCode(text) }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * Try to decode one frame. Returns the text, or null to mean "not a QR".
 *
 * Always closes the proxy, including on the throw path — a leaked ImageProxy
 * stalls the analyser within a few frames and the preview freezes.
 */
private fun decode(proxy: ImageProxy, consumed: AtomicBoolean, found: (String) -> Unit): String? {
    if (consumed.get()) {
        proxy.close()
        return null
    }
    try {
        val plane = proxy.planes.firstOrNull() ?: return null
        val width = proxy.width
        val height = proxy.height
        if (width <= 0 || height <= 0) return null

        // The camera's row stride is routinely wider than the frame — padding is
        // added for alignment and is commonly width rounded up to 16. Reading the
        // plane linearly and handing it to ZXing as if stride == width shears
        // every row after the first, so the symbol is undecodable. This was the
        // reason scanning never fired: the code was fine, the pixels were not.
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        buffer.position(0)
        val bytes = if (rowStride == width) {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } else {
            ByteArray(width * height).also { tight ->
                val row = ByteArray(rowStride)
                var copied = 0
                for (y in 0 until height) {
                    if (buffer.remaining() < rowStride) break
                    buffer.get(row, 0, rowStride)
                    System.arraycopy(row, 0, tight, y * width, width)
                    copied = y + 1
                }
                if (copied < height) return null // truncated frame, skip it
            }
        }

        val source = PlanarYUVLuminanceSource(
            bytes, width, height,
            0, 0, width, height,
            false,
        )
        val result = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            null,
        )
        val text = result.text
        if (consumed.compareAndSet(false, true)) found(text)
        return text
    } catch (e: Exception) {
        return null // a frame without a QR is the normal case, not an error
    } finally {
        proxy.close()
    }
}
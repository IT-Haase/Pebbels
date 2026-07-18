/*
 * Kamera-Popup zum Scannen der AiDEX-Seriennummer per QR-Code.
 *
 * Liest den QR-Code auf dem Karton (ML-Kit Barcode-Scanning) und füllt damit
 * das SN-Feld. Reines Android (CameraX + ML-Kit). Wird von der geteilten UI
 * über AppState.onScanSerial ausgelöst; die MainActivity hostet den Dialog.
 */
package com.g7monitor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.g7monitor.shared.ui.tr

private val GREEN = Color(0xFF34C759)
private val DIM = Color(0x88FFFFFF)

@Composable
fun SnScanDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    var recognized by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111511)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(tr("aidex_scan_title"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            when {
                !granted -> {
                    Text(tr("aidex_scan_perm"), color = DIM, fontSize = 11.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text(tr("aidex_scan_allow"), color = GREEN) }
                    TextButton(onClick = onDismiss) { Text(tr("common_cancel"), color = DIM) }
                }
                recognized == null -> {
                    Text(tr("aidex_scan_hint"), color = DIM, fontSize = 11.sp, textAlign = TextAlign.Center)
                    SnQrScanner(onSn = { recognized = it })
                    TextButton(onClick = onDismiss) { Text(tr("common_cancel"), color = GREEN) }
                }
                else -> {
                    Text(tr("aidex_scan_recognized"), color = DIM, fontSize = 11.sp)
                    Text(recognized!!, color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { recognized = null }) { Text(tr("aidex_scan_again"), color = DIM) }
                        TextButton(onClick = { onResult(recognized!!) }) { Text(tr("aidex_scan_apply"), color = GREEN) }
                    }
                }
            }
        }
    }
}

/** QR-Code (Barcode) per Kamera lesen und die SN extrahieren. Liefert einmal. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun SnQrScanner(onSn: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val done = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            providerHolder.value?.unbindAll()
            scanner.close()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                providerHolder.value = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    val media = proxy.image
                    if (media == null || done.value) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (bc in barcodes) {
                                val sn = extractSn(bc.rawValue ?: continue) ?: continue
                                if (!done.value) { done.value = true; onSn(sn); break }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (_: Throwable) {}
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/** Aus dem QR-Inhalt die SN ziehen: bevorzugt eine 10-stellige alphanumerische
 *  Kennung (AiDEX-SN); sonst der bereinigte Inhalt. */
private fun extractSn(raw: String): String? {
    val up = raw.uppercase()
    Regex("[0-9A-Z]{10}").find(up)?.let { return it.value }
    val clean = up.filter { it.isLetterOrDigit() }
    return if (clean.length in 6..14) clean else null
}

package com.ocrapp.qr

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads QR codes via ML Kit's bundled barcode scanner — on-device, no network, no Play
 * Services model download (mirrors [com.ocrapp.ocr.MlKitOcrEngine]'s bundled setup).
 */
@Singleton
class QrCodeScanner @Inject constructor() {

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    /** Decodes a single still image. Used for imported/generated images and in tests. */
    suspend fun scan(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { continuation: CancellableContinuation<String?> ->
            val input = InputImage.fromBitmap(bitmap, 0)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    continuation.resume(barcodes.firstOrNull()?.rawValue)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
        }

    /**
     * An [ImageAnalysis.Analyzer] for live camera frames. [onResult] fires on every frame
     * a QR code is found in; callers own debouncing repeated results (e.g. ignore further
     * calls once a value has been accepted) since frames keep arriving until analysis is
     * unbound.
     */
    @OptIn(ExperimentalGetImage::class)
    fun analyzer(onResult: (String) -> Unit): ImageAnalysis.Analyzer =
        ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@Analyzer
            }
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull()?.rawValue?.let(onResult)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
}

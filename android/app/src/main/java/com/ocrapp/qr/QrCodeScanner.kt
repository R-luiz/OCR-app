package com.ocrapp.qr

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** Decodes a single still image; null when it holds no QR code. */
    suspend fun scan(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { continuation ->
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { barcodes ->
                    continuation.resume(barcodes.firstOrNull()?.rawValue)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    /**
     * An [ImageAnalysis.Analyzer] for live camera frames.
     *
     * Frames keep arriving until analysis is unbound. While [isWanted] is false they are
     * dropped unread, so a result already on screen does not keep the detector running;
     * [onResult] fires for every frame a QR code is found in while it is true.
     */
    @OptIn(ExperimentalGetImage::class)
    fun analyzer(isWanted: () -> Boolean, onResult: (String) -> Unit): ImageAnalysis.Analyzer =
        ImageAnalysis.Analyzer { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null || !isWanted()) {
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

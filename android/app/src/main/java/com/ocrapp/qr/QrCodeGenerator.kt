package com.ocrapp.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encodes text as a QR code [Bitmap] using ZXing's pure-Java encoder — entirely
 * on-device, no network involved.
 */
@Singleton
class QrCodeGenerator @Inject constructor() {

    /**
     * Returns a black-on-white QR bitmap for [text], or null when [text] is blank or too
     * long to fit in a QR code (ZXing throws `WriterException` in that case).
     */
    fun generate(text: String, sizePx: Int = DEFAULT_SIZE_PX): Bitmap? {
        if (text.isBlank()) return null

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        }.getOrNull() ?: return null

        // Filled as one array and handed over whole. Bitmap.setPixel is a native call
        // per pixel, which for a 768px code is well over half a million of them.
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 768
        const val QUIET_ZONE_MODULES = 1
    }
}

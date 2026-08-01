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

        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 768
        const val QUIET_ZONE_MODULES = 1
    }
}

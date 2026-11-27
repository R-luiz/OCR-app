package com.ocrapp.ocr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Base64OutputStream
import java.io.ByteArrayOutputStream

private val DATA_URL_PREFIX = "data:image/jpeg;base64,".toByteArray(Charsets.US_ASCII)
private const val JPEG_QUALITY = 85

/**
 * JPEG-encodes this bitmap as a `data:` URL for the worker's `images` array.
 *
 * The JPEG is streamed straight through the base64 encoder into the buffer the string
 * is built from, rather than materialised as a byte array, a base64 string and a
 * concatenated copy in turn.
 */
internal fun Bitmap.toJpegDataUrl(quality: Int = JPEG_QUALITY): String {
    val buffer = ByteArrayOutputStream(32 * 1024)
    buffer.write(DATA_URL_PREFIX)
    Base64OutputStream(buffer, Base64.NO_WRAP).use { base64 ->
        check(compress(Bitmap.CompressFormat.JPEG, quality, base64)) {
            "Could not encode the page as JPEG"
        }
    }
    return buffer.toString(Charsets.US_ASCII.name())
}

package com.ocrapp.ocr

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Prepares bitmaps for upload to the Unlimited-OCR backend.
 *
 * The model's DeepEncoder consumes a 1024px page, so anything larger is wasted bytes on
 * the wire. Downscaling to a 1024px long edge at JPEG q85 keeps a 20-page document in
 * the low single-digit megabytes, which matters because every page of a multi-page job
 * travels in one request body.
 */
@Singleton
class ImageNormalizer @Inject constructor() {

    /** Encodes [bitmap] as a `data:` URL suitable for the handler's `images` array. */
    fun toDataUrl(
        bitmap: Bitmap,
        maxEdge: Int = MAX_EDGE_PX,
        quality: Int = JPEG_QUALITY,
    ): String {
        val scaled = scaleToMaxEdge(bitmap, maxEdge)
        val bytes = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    /**
     * Scales [bitmap] so its longer edge is at most [maxEdge], preserving aspect ratio.
     * Returns the input unchanged when it already fits, so callers must compare by
     * identity before recycling.
     */
    fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int = MAX_EDGE_PX): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap

        val ratio = maxEdge.toDouble() / longEdge
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, /* filter = */ true)
    }

    companion object {
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 85

        /** Long edge for history thumbnails. */
        const val THUMBNAIL_EDGE_PX = 256
    }
}

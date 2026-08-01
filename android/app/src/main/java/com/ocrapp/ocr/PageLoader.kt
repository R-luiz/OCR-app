package com.ocrapp.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Turns the app's three input sources — camera capture, picked images, and PDFs — into
 * the single [PageImage] list that every [OcrEngine] consumes.
 */
@Singleton
class PageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Decodes picked images and/or PDFs, in the order given, into a flat page list. */
    suspend fun load(uris: List<Uri>): Result<List<PageImage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                buildList {
                    for (uri in uris) {
                        if (isPdf(uri)) {
                            addAll(renderPdf(uri, startIndex = size))
                        } else {
                            add(PageImage(index = size, bitmap = decodeImage(uri)))
                        }
                    }
                }
            }
        }

    /** Decodes a file written by CameraX. */
    suspend fun loadCapture(file: File): Result<List<PageImage>> =
        load(listOf(Uri.fromFile(file)))

    private fun isPdf(uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.let { return it == MIME_PDF }
        return uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ML Kit and Bitmap.compress both need CPU-readable pixels, so the
            // hardware allocator that ImageDecoder would otherwise prefer is off.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > CAPTURE_EDGE_PX) {
                val ratio = CAPTURE_EDGE_PX.toDouble() / longEdge
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun renderPdf(uri: Uri, startIndex: Int): List<PageImage> {
        val descriptor: ParcelFileDescriptor = context.contentResolver
            .openFileDescriptor(uri, "r")
            ?: error("Could not open PDF $uri")

        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until renderer.pageCount).map { pageNumber ->
                    renderer.openPage(pageNumber).use { page ->
                        PageImage(
                            index = startIndex + pageNumber,
                            bitmap = renderPage(page),
                        )
                    }
                }
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val longEdge = maxOf(page.width, page.height)
        val scale = (CAPTURE_EDGE_PX.toDouble() / longEdge).coerceAtMost(1.0)
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // PdfRenderer composites onto a transparent bitmap; without an explicit white
        // fill, JPEG encoding flattens the transparency to black and OCR sees nothing.
        Canvas(bitmap).drawColor(Color.WHITE)

        val transform = Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private companion object {
        const val MIME_PDF = "application/pdf"

        /**
         * Decode/render target. Larger than the 1024px the model wants, because ML Kit
         * reads small print better at this size; [ImageNormalizer] downscales again on
         * the upload path.
         */
        const val CAPTURE_EDGE_PX = 1600
    }
}

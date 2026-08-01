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
 * Pages ready for OCR, plus how many the import refused to take on.
 *
 * [droppedPages] is non-zero only when the input exceeded the page ceiling; the UI
 * says so rather than silently scanning part of a document.
 */
data class LoadedPages(
    val pages: List<PageImage>,
    val droppedPages: Int = 0,
)

/**
 * Turns the app's three input sources — camera capture, picked images, and PDFs — into
 * the single [PageImage] list that every [OcrEngine] consumes.
 */
@Singleton
class PageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Decodes picked images and/or PDFs, in the order given, into a flat page list.
     *
     * [onProgress] reports `(pagesDone, pagesTotal)` as rasterizing proceeds. A long PDF
     * takes real time to render — this is what lets the UI say which page it is on
     * instead of showing an unqualified spinner for minutes.
     */
    suspend fun load(
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<LoadedPages> =
        withContext(Dispatchers.IO) {
            runCatching {
                val requested = uris.sumOf { pageCount(it) }
                val total = minOf(requested, MAX_PAGES)
                onProgress(0, total)

                val pages = buildList {
                    for (uri in uris) {
                        if (size >= MAX_PAGES) break
                        if (isPdf(uri)) {
                            addAll(renderPdf(uri, startIndex = size, total = total, onProgress = onProgress))
                        } else {
                            add(PageImage(index = size, bitmap = decodeImage(uri)))
                            onProgress(size, total)
                        }
                    }
                }
                LoadedPages(pages = pages, droppedPages = requested - pages.size)
            }
        }

    /** Decodes a file written by CameraX. */
    suspend fun loadCapture(file: File): Result<LoadedPages> =
        load(listOf(Uri.fromFile(file)))

    /** Page count without rasterizing anything — cheap enough to run before the work. */
    private fun pageCount(uri: Uri): Int {
        if (!isPdf(uri)) return 1
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        }.getOrNull() ?: 1
    }

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

    private fun renderPdf(
        uri: Uri,
        startIndex: Int,
        total: Int,
        onProgress: (Int, Int) -> Unit,
    ): List<PageImage> {
        val descriptor: ParcelFileDescriptor = context.contentResolver
            .openFileDescriptor(uri, "r")
            ?: error("Could not open PDF $uri")

        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val available = minOf(renderer.pageCount, MAX_PAGES - startIndex)
                (0 until available).map { pageNumber ->
                    renderer.openPage(pageNumber).use { page ->
                        PageImage(
                            index = startIndex + pageNumber,
                            bitmap = renderPage(page),
                        ).also { onProgress(startIndex + pageNumber + 1, total) }
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
         * Hard ceiling on pages per scan. Every page is rasterized up front and held in
         * memory at once, and at [CAPTURE_EDGE_PX] an A4 page is ~7 MB of ARGB_8888 — so
         * 40 pages is already ~280 MB, enough to push a mid-range device into GC thrash
         * or an OOM before OCR starts. 64 also matches the backend's own MAX_PAGES, so a
         * request that gets this far is one the worker will accept.
         */
        const val MAX_PAGES = 64

        /**
         * Decode/render target. Larger than the 1024px the model wants, because ML Kit
         * reads small print better at this size; [ImageNormalizer] downscales again on
         * the upload path.
         */
        const val CAPTURE_EDGE_PX = 1600
    }
}

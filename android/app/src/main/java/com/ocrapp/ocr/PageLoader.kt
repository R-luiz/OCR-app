package com.ocrapp.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * An opened document, plus how many pages the import refused to take on.
 *
 * [droppedPages] is non-zero only when the input exceeded the page ceiling; the UI
 * says so rather than silently scanning part of a document.
 */
class LoadedPages(
    val pages: PageSource,
    val droppedPages: Int = 0,
)

/**
 * Turns the app's three input sources — camera captures, picked images, and PDFs — into
 * the single [PageSource] that every [OcrEngine] consumes.
 */
@Singleton
class PageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Opens images and/or PDFs, in the order given, as one document.
     *
     * Only page counts are read here; pixels are produced later, a page at a time, by
     * [PageSource.render]. The caller closes the returned source.
     */
    suspend fun open(uris: List<Uri>): Result<LoadedPages> = runCatchingCancellable {
        val renderers = mutableListOf<PdfRenderer>()
        try {
            withContext(Dispatchers.IO) {
                val refs = mutableListOf<PageRef>()
                var requested = 0
                for (uri in uris) {
                    ensureActive()
                    if (!isPdf(uri)) {
                        requested++
                        if (refs.size < MAX_PAGES) refs += PageRef.Image(uri)
                        continue
                    }
                    val renderer = openPdf(uri)
                    requested += renderer.pageCount
                    val taken = minOf(renderer.pageCount, MAX_PAGES - refs.size)
                    if (taken == 0) {
                        renderer.close()
                        continue
                    }
                    renderers += renderer
                    repeat(taken) { number -> refs += PageRef.PdfPage(renderer, number) }
                }
                LoadedPages(UriPageSource(refs, renderers), droppedPages = requested - refs.size)
            }
        } catch (error: Throwable) {
            // Cancellation can also happen on the dispatcher handoff, after opening.
            closeRenderers(renderers)
            throw error
        }
    }

    private fun isPdf(uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.let { return it == MIME_PDF }
        return uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
    }

    private fun openPdf(uri: Uri): PdfRenderer {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Could not open PDF $uri")
        return try {
            // The renderer owns the descriptor from here on and closes it itself.
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private fun decodeImage(uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ML Kit and Bitmap.compress both need CPU-readable pixels, so the
            // hardware allocator that ImageDecoder would otherwise prefer is off.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            // A photo holds no more detail than its pixels, so it is only ever shrunk.
            val (width, height) = fitSize(info.size.width, info.size.height, maxEdge, upscale = false)
            decoder.setTargetSize(width, height)
        }
    }

    private fun renderPdfPage(renderer: PdfRenderer, number: Int, maxEdge: Int): Bitmap =
        renderer.openPage(number).use { page ->
            // A PDF page is measured in points (1/72 inch), not pixels: an A4 page is
            // 595 x 842 of them. Rendering it at that size gives 72 dpi, where body text
            // is a handful of pixels tall and OCR struggles — so it is scaled up to the
            // requested edge, which for vector content is real resolution.
            val (width, height) = fitSize(page.width, page.height, maxEdge, upscale = true)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val transform = Matrix().apply {
                    setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

    private sealed interface PageRef {
        data class Image(val uri: Uri) : PageRef

        class PdfPage(val renderer: PdfRenderer, val number: Int) : PageRef
    }

    private inner class UriPageSource(
        private val refs: List<PageRef>,
        private val renderers: List<PdfRenderer>,
    ) : PageSource {

        override val pageCount: Int get() = refs.size

        override suspend fun render(index: Int, maxEdge: Int): Bitmap {
            var allocated: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                return withContext(Dispatchers.IO) {
                    when (val ref = refs[index]) {
                        is PageRef.Image -> decodeImage(ref.uri, maxEdge)
                        is PageRef.PdfPage -> renderPdfPage(ref.renderer, ref.number, maxEdge)
                    }.also { allocated = it }
                }
            } catch (error: Throwable) {
                allocated?.recycle()
                throw error
            }
        }

        override fun close() = closeRenderers(renderers)
    }

    private fun closeRenderers(renderers: List<PdfRenderer>) {
        renderers.forEach { renderer -> runCatching { renderer.close() } }
    }

    companion object {
        private const val MIME_PDF = "application/pdf"

        /**
         * Ceiling on pages per scan. Memory is no longer what sets it — pages are
         * rendered one at a time — but a scan still costs time on-device and GPU
         * seconds remotely for every page, so an accidental 400-page import is refused
         * rather than left to run.
         */
        const val MAX_PAGES = 64
    }
}

/**
 * The size [width] x [height] takes when its longer edge is fitted to [maxEdge],
 * preserving aspect ratio. Sources already within [maxEdge] keep their size unless
 * [upscale] is set.
 */
internal fun fitSize(width: Int, height: Int, maxEdge: Int, upscale: Boolean): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || (longEdge <= maxEdge && !upscale)) return width to height

    val ratio = maxEdge.toDouble() / longEdge
    return (width * ratio).roundToInt().coerceAtLeast(1) to
        (height * ratio).roundToInt().coerceAtLeast(1)
}

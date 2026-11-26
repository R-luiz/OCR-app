package com.ocrapp.ocr

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the parts of the pipeline that only exist on a real Android runtime:
 * ML Kit's bundled recognizer, the platform PdfRenderer and ImageDecoder. None of them
 * runs under Robolectric.
 *
 * Assets live in `src/androidTest/assets` and are read from the *instrumentation*
 * context; the app context has no access to them.
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineInstrumentedTest {

    private lateinit var appContext: Context
    private lateinit var testAssets: AssetManager

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        testAssets = instrumentation.context.assets
    }

    @Test
    fun mlKitReadsTextFromAScannedPage() = runBlocking {
        val output = MlKitOcrEngine().recognize(samplePages(1)).getOrThrow()

        val text = output.plainText.uppercase()
        assertTrue("missing INVOICE, got:\n${output.plainText}", text.contains("INVOICE"))
        assertTrue("missing order number, got:\n${output.plainText}", text.contains("4471"))
        assertTrue("missing total, got:\n${output.plainText}", text.contains("128"))

        assertEquals(EngineType.QUICK, output.engine)
        assertEquals(1, output.pageCount)
        // ML Kit has no document structure to report.
        assertNull(output.markdown)
    }

    // The progress overlay is how a user tells the engines apart and sees a long
    // document advance, so on-device work reports its own stage, page by page.
    @Test
    fun mlKitReportsEachPageAsOnDeviceRecognition() = runBlocking {
        val progress = mutableListOf<OcrProgress>()

        MlKitOcrEngine().recognize(samplePages(2), onProgress = { progress += it }).getOrThrow()

        assertEquals(
            listOf(
                OcrProgress(OcrStage.RECOGNIZING, page = 1, pageCount = 2),
                OcrProgress(OcrStage.RECOGNIZING, page = 2, pageCount = 2),
            ),
            progress,
        )
    }

    @Test
    fun mlKitConcatenatesMultiplePages() = runBlocking {
        val output = MlKitOcrEngine().recognize(samplePages(2)).getOrThrow()

        assertEquals(2, output.pageCount)
        // Same page twice, so the marker must appear once per page.
        assertEquals(2, Regex("INVOICE", RegexOption.IGNORE_CASE).findAll(output.plainText).count())
        assertEquals(emptyList<Int>(), output.emptyPageNumbers)
    }

    // A blank page among others must be named: the text reads as complete without it.
    @Test
    fun mlKitNamesAPageWithNoText() = runBlocking {
        val pages = FakePageSource(2) { index ->
            if (index == 0) samplePage() else blankPage()
        }

        val output = MlKitOcrEngine().recognize(pages).getOrThrow()

        assertEquals(listOf(2), output.emptyPageNumbers)
        assertTrue(output.plainText.uppercase().contains("INVOICE"))
    }

    // A PDF page is 595 x 842 points. Rendered at that size (72 dpi) small print is
    // unreadable, so the loader scales vector pages up to exactly the edge asked for.
    @Test
    fun pdfPagesRenderAtTheRequestedResolution() = runBlocking {
        openSamplePdf().pages.use { pages ->
            assertEquals("expected both PDF pages", 2, pages.pageCount)

            for (index in 0 until pages.pageCount) {
                pages.usePage(index, 2048) { large ->
                    assertEquals(2048, maxOf(large.width, large.height))
                }
                pages.usePage(index, 256) { small ->
                    assertEquals(256, maxOf(small.width, small.height))
                }
            }
        }
    }

    @Test
    fun pdfPagesAreCompositedOntoWhiteSoOcrCanReadThem() = runBlocking {
        val loaded = openSamplePdf()
        assertEquals("nothing should be dropped for a 2-page PDF", 0, loaded.droppedPages)

        // PdfRenderer draws onto a transparent bitmap; without the explicit white fill
        // the JPEG encoder flattens it to black and OCR sees nothing at all.
        val output = loaded.pages.use { MlKitOcrEngine().recognize(it).getOrThrow() }
        val text = output.plainText.uppercase()

        assertTrue("no text from PDF, got:\n${output.plainText}", text.contains("INVOICE"))
        assertTrue("second page missing, got:\n${output.plainText}", text.contains("APPENDIX"))
    }

    // A photo holds no more detail than its pixels: it is shrunk to fit, never enlarged.
    @Test
    fun imagesAreShrunkToFitButNeverEnlarged() = runBlocking {
        val image = copyAssetToCache(SAMPLE_PAGE)
        val original = samplePage()
        val originalEdge = maxOf(original.width, original.height)

        PageLoader(appContext).open(listOf(Uri.fromFile(image))).getOrThrow().pages.use { pages ->
            assertEquals(1, pages.pageCount)
            pages.usePage(0, originalEdge / 2) { half ->
                assertEquals(originalEdge / 2, maxOf(half.width, half.height))
            }
            pages.usePage(0, originalEdge * 4) { full ->
                assertEquals(originalEdge, maxOf(full.width, full.height))
            }
        }
    }

    @Test
    fun pagesEncodeToADecodableJpegDataUrl() {
        val dataUrl = samplePage().toJpegDataUrl()

        assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.NO_WRAP)
        val decoded: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertTrue("payload did not decode as an image", decoded != null)
    }

    private fun samplePages(count: Int) = FakePageSource(count) { samplePage() }

    private fun samplePage(): Bitmap =
        testAssets.open(SAMPLE_PAGE).use { BitmapFactory.decodeStream(it) }

    private fun blankPage(): Bitmap =
        Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }

    private suspend fun openSamplePdf(): LoadedPages =
        PageLoader(appContext).open(listOf(Uri.fromFile(copyAssetToCache(SAMPLE_PDF)))).getOrThrow()

    private fun copyAssetToCache(name: String): File {
        val target = File(appContext.cacheDir, name)
        testAssets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val SAMPLE_PAGE = "sample_page.png"
        const val SAMPLE_PDF = "sample_doc.pdf"
    }
}

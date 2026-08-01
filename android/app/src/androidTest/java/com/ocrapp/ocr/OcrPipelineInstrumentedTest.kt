package com.ocrapp.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the parts of the pipeline that only exist on a real Android runtime:
 * ML Kit's bundled recognizer and the platform PdfRenderer. Neither runs under
 * Robolectric, so before this the whole Quick scan path was unverified.
 *
 * Assets live in `src/androidTest/assets` and are read from the *instrumentation*
 * context; the app context has no access to them.
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineInstrumentedTest {

    private lateinit var appContext: Context
    private lateinit var testAssets: android.content.res.AssetManager

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        testAssets = instrumentation.context.assets
    }

    @Test
    fun mlKitReadsTextFromAScannedPage() = runBlocking {
        val bitmap = testAssets.open(SAMPLE_PAGE).use { BitmapFactory.decodeStream(it) }
        val engine = MlKitOcrEngine()

        val output = engine.recognize(listOf(PageImage(index = 0, bitmap = bitmap))).getOrThrow()

        val text = output.plainText.uppercase()
        assertTrue("recognized nothing", output.plainText.isNotBlank())
        assertTrue("missing INVOICE, got:\n${output.plainText}", text.contains("INVOICE"))
        assertTrue("missing order number, got:\n${output.plainText}", text.contains("4471"))
        assertTrue("missing total, got:\n${output.plainText}", text.contains("128"))

        assertEquals(EngineType.QUICK, output.engine)
        assertEquals(1, output.pageCount)
        // ML Kit has no document structure to report.
        assertEquals(null, output.markdown)
    }

    @Test
    fun mlKitReportsOnDeviceRecognitionNotRemoteParsing() = runBlocking {
        val bitmap = testAssets.open(SAMPLE_PAGE).use { BitmapFactory.decodeStream(it) }
        val stages = mutableListOf<OcrStage>()

        MlKitOcrEngine()
            .recognize(listOf(PageImage(0, bitmap)), onStage = { stages += it })
            .getOrThrow()

        // The progress overlay is how a user tells the engines apart, so the on-device
        // path must not claim the remote path's stages.
        assertEquals(listOf(OcrStage.RECOGNIZING), stages)
        assertTrue("must not report remote stages", OcrStage.QUEUED !in stages)
        assertTrue("must not report remote stages", OcrStage.RUNNING !in stages)
    }

    @Test
    fun mlKitConcatenatesMultiplePages() = runBlocking {
        val pages = List(2) { index ->
            val bitmap = testAssets.open(SAMPLE_PAGE).use { BitmapFactory.decodeStream(it) }
            PageImage(index = index, bitmap = bitmap)
        }

        val output = MlKitOcrEngine().recognize(pages).getOrThrow()

        assertEquals(2, output.pageCount)
        // Same page twice, so the marker must appear once per page.
        assertEquals(2, Regex("INVOICE", RegexOption.IGNORE_CASE).findAll(output.plainText).count())
    }

    @Test
    fun pdfImportRendersEveryPageUpright() = runBlocking {
        val pdf = copyAssetToCache(SAMPLE_PDF)
        val loader = PageLoader(appContext)

        val pages = loader.load(listOf(Uri.fromFile(pdf))).getOrThrow().pages

        assertEquals("expected both PDF pages", 2, pages.size)
        assertEquals(listOf(0, 1), pages.map { it.index })
        pages.forEach { page ->
            assertTrue("page ${page.index} is empty", page.bitmap.width > 0 && page.bitmap.height > 0)
            assertTrue(
                "page ${page.index} long edge ${maxOf(page.bitmap.width, page.bitmap.height)}",
                maxOf(page.bitmap.width, page.bitmap.height) <= 1600,
            )
        }
    }

    @Test
    fun pdfPagesAreCompositedOntoWhiteSoOcrCanReadThem() = runBlocking {
        val pdf = copyAssetToCache(SAMPLE_PDF)
        val pages = PageLoader(appContext).load(listOf(Uri.fromFile(pdf))).getOrThrow().pages

        // PdfRenderer draws onto a transparent bitmap; without the explicit white fill
        // the JPEG encoder flattens it to black and OCR sees nothing at all.
        val output = MlKitOcrEngine().recognize(pages).getOrThrow()
        val text = output.plainText.uppercase()

        assertTrue("no text from PDF, got:\n${output.plainText}", text.contains("INVOICE"))
        assertTrue("second page missing, got:\n${output.plainText}", text.contains("APPENDIX"))
    }

    @Test
    fun normalizerProducesADecodableJpegDataUrl() {
        val bitmap = testAssets.open(SAMPLE_PAGE).use { BitmapFactory.decodeStream(it) }

        val dataUrl = ImageNormalizer().toDataUrl(bitmap)

        assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))
        val payload = dataUrl.substringAfter(",")
        val bytes = android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
        val decoded: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertTrue("payload did not decode as an image", decoded != null)
        assertTrue(
            "long edge ${maxOf(decoded!!.width, decoded.height)} exceeds the model's 1024px input",
            maxOf(decoded.width, decoded.height) <= 1024,
        )
    }

    // A long PDF used to sit behind an unqualified spinner for minutes with no way to
    // tell progress from a hang, so the loader now reports which page it is on.
    @Test
    fun pdfImportReportsPerPageProgress() = runBlocking {
        val pdf = copyAssetToCache(SAMPLE_PDF)
        val seen = mutableListOf<Pair<Int, Int>>()

        val loaded = PageLoader(appContext)
            .load(listOf(Uri.fromFile(pdf))) { done, total -> seen += done to total }
            .getOrThrow()

        assertEquals("nothing should be dropped for a 2-page PDF", 0, loaded.droppedPages)
        assertTrue("expected progress callbacks, got none", seen.isNotEmpty())
        assertTrue(
            "every callback must report the real total, got $seen",
            seen.all { (_, total) -> total == loaded.pages.size },
        )
        assertEquals(
            "progress must end on the final page",
            loaded.pages.size,
            seen.last().first,
        )
        assertTrue(
            "progress must not run backwards, got $seen",
            seen.map { it.first }.zipWithNext().all { (a, b) -> b >= a },
        )
    }

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

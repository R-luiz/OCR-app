package com.ocrapp.ocr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil

/** Robolectric only for the data-URL test, which touches Bitmap and Base64. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PageSizingTest {

    @Test
    fun `shrinks the long edge and preserves aspect ratio`() {
        assertEquals(1024 to 512, fitSize(4000, 2000, maxEdge = 1024, upscale = false))
    }

    @Test
    fun `shrinks portrait pages on their height`() {
        val (width, height) = fitSize(1200, 3000, maxEdge = 1024, upscale = false)

        assertEquals(1024, height)
        assertEquals(410, width)
    }

    @Test
    fun `leaves a photo that already fits at its own size`() {
        assertEquals(800 to 600, fitSize(800, 600, maxEdge = 1024, upscale = false))
    }

    // A PDF page is 595 x 842 points. Rendered at that size it is 72 dpi and unreadable
    // to OCR, so a vector source is scaled up to the edge that was asked for.
    @Test
    fun `scales a pdf page up to the requested edge`() {
        assertEquals(1447 to 2048, fitSize(595, 842, maxEdge = 2048, upscale = true))
    }

    @Test
    fun `never scales an edge below one pixel`() {
        val (width, height) = fitSize(2000, 3, maxEdge = 100, upscale = false)

        assertEquals(100, width)
        assertEquals(1, height)
    }

    // A 12 MP photo against a 2048 px edge: a target size alone would decode all of
    // it into a full-size temporary before scaling it down.
    @Test
    fun `subsamples a 12 MP photo inside the decoder`() {
        assertEquals(2, decodeSampleSize(4032, 3024, maxEdge = 2048))
        assertEquals(4, decodeSampleSize(6120, 8160, maxEdge = 2048))
    }

    @Test
    fun `decodes at full size when subsampling would lose more than a tenth of the edge`() {
        // 3000 / 2 = 1500, well short of 2048.
        assertEquals(1, decodeSampleSize(3000, 2250, maxEdge = 2048))
    }

    @Test
    fun `never subsamples an image that already fits`() {
        assertEquals(1, decodeSampleSize(2048, 1536, maxEdge = 2048))
        assertEquals(1, decodeSampleSize(800, 600, maxEdge = 2048))
    }

    @Test
    fun `a subsampled image never exceeds the edge`() {
        for (longEdge in 2049..20_000 step 7) {
            val sample = decodeSampleSize(longEdge, longEdge / 2, maxEdge = 2048)
            val decoded = ceil(longEdge / sample.toDouble()).toInt()
            assertTrue("$longEdge / $sample", sample == 1 || decoded in 1843..2048)
        }
    }

    @Test
    fun `encodes a page as a single-line jpeg data url`() {
        val dataUrl = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).toJpegDataUrl()

        assertTrue(dataUrl, dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue("payload should not be empty", dataUrl.length > "data:image/jpeg;base64,".length)
        // Line breaks would corrupt the JSON body.
        assertTrue("must be single-line", '\n' !in dataUrl && '\r' !in dataUrl)
    }
}

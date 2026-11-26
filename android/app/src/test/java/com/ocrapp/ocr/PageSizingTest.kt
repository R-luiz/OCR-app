package com.ocrapp.ocr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test
    fun `encodes a page as a single-line jpeg data url`() {
        val dataUrl = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).toJpegDataUrl()

        assertTrue(dataUrl, dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue("payload should not be empty", dataUrl.length > "data:image/jpeg;base64,".length)
        // Line breaks would corrupt the JSON body.
        assertTrue("must be single-line", '\n' !in dataUrl && '\r' !in dataUrl)
    }
}

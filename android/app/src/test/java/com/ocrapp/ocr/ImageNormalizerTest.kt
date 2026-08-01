package com.ocrapp.ocr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageNormalizerTest {

    private val normalizer = ImageNormalizer()

    @Test
    fun `downscales the long edge and preserves aspect ratio`() {
        val source = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)

        val scaled = normalizer.scaleToMaxEdge(source, maxEdge = 1024)

        assertEquals(1024, scaled.width)
        assertEquals(512, scaled.height)
    }

    @Test
    fun `downscales portrait pages on their height`() {
        val source = Bitmap.createBitmap(1200, 3000, Bitmap.Config.ARGB_8888)

        val scaled = normalizer.scaleToMaxEdge(source, maxEdge = 1024)

        assertEquals(1024, scaled.height)
        assertTrue("width ${scaled.width}", scaled.width <= 1024)
    }

    @Test
    fun `leaves images that already fit untouched`() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)

        // Identity matters: callers rely on it before deciding whether to recycle.
        assertSame(source, normalizer.scaleToMaxEdge(source, maxEdge = 1024))
    }

    @Test
    fun `never scales an edge below one pixel`() {
        val source = Bitmap.createBitmap(2000, 3, Bitmap.Config.ARGB_8888)

        val scaled = normalizer.scaleToMaxEdge(source, maxEdge = 100)

        assertEquals(100, scaled.width)
        assertTrue("height ${scaled.height}", scaled.height >= 1)
    }

    @Test
    fun `emits a jpeg data url`() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val dataUrl = normalizer.toDataUrl(source)

        assertTrue(dataUrl, dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue("payload should not be empty", dataUrl.length > "data:image/jpeg;base64,".length)
        // NO_WRAP: line breaks would corrupt the JSON body.
        assertTrue("must be single-line", !dataUrl.contains("\n"))
    }
}

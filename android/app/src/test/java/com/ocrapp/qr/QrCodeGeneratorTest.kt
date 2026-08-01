package com.ocrapp.qr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips through ZXing's own decoder rather than ML Kit, so this verifies the
 * generator produces a genuinely valid QR code independent of which reader is used.
 * [com.ocrapp.qr.QrPipelineInstrumentedTest] separately confirms the app's actual
 * on-device reader (ML Kit) can read this generator's output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeGeneratorTest {

    private val generator = QrCodeGenerator()

    @Test
    fun `blank input produces no bitmap`() {
        assertNull(generator.generate(""))
        assertNull(generator.generate("   "))
    }

    @Test
    fun `generated bitmap decodes back to the original text`() {
        val text = "https://github.com/r-luiz/ocr-app"

        val bitmap = generator.generate(text)!!

        assertEquals(text, decode(bitmap))
    }

    @Test
    fun `round-trips plain short text too`() {
        val text = "hello world"

        assertEquals(text, decode(generator.generate(text)!!))
    }

    @Test
    fun `respects the requested pixel size`() {
        val bitmap = generator.generate("hello", sizePx = 256)!!

        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `text too long to fit returns null instead of throwing`() {
        // QR codes cap out under 3KB of byte-mode payload even at the lowest
        // error-correction level and the largest (version 40) size; this comfortably
        // exceeds every version's capacity at the error-correction level we use.
        val tooLong = "x".repeat(5000)

        assertNull(generator.generate(tooLong))
    }

    /** Decodes [bitmap] with ZXing directly, independent of the app's ML Kit reader. */
    private fun decode(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(binaryBitmap).text
    }
}

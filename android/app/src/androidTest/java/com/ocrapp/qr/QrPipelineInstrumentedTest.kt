package com.ocrapp.qr

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Confirms the app's own QR reader can read the app's own QR maker's output. The
 * generator (ZXing) and the scanner (ML Kit) are independent libraries with no shared
 * code, so this is the only test that exercises both halves of the feature together
 * rather than assuming they agree — and it needs a real device/emulator, since neither
 * ML Kit's recognizer nor real camera-format image decoding runs under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class QrPipelineInstrumentedTest {

    private val generator = QrCodeGenerator()
    private val scanner = QrCodeScanner()

    @Test
    fun scannerReadsGeneratorsUrlOutput() = runBlocking {
        val text = "https://github.com/r-luiz/ocr-app"
        val bitmap = generator.generate(text)
        assertNotNull("generator failed to produce a bitmap", bitmap)

        assertEquals(text, scanner.scan(bitmap!!))
    }

    @Test
    fun scannerReadsGeneratorsPlainTextOutput() = runBlocking {
        val text = "hello from the OCR app"
        val bitmap = generator.generate(text)
        assertNotNull("generator failed to produce a bitmap", bitmap)

        assertEquals(text, scanner.scan(bitmap!!))
    }
}

package com.ocrapp.ui.capture

import androidx.test.core.app.ApplicationProvider
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.OcrOutput
import com.ocrapp.ocr.RecognitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ScanNoticeFormatterTest {

    private val formatter = ScanNoticeFormatter(ApplicationProvider.getApplicationContext())

    @Test
    fun `a scan that went as asked has no notice`() {
        assertNull(formatter.format(RecognitionResult(output()), droppedPages = 0))
    }

    // Everything that makes the result differ from what was asked for has to reach the
    // Result screen: which engine really ran and why, and which pages are not in it.
    @Test
    fun `names the fallback, the empty pages and the pages left out`() {
        val result = RecognitionResult(
            output = output(pageCount = 64, emptyPageNumbers = listOf(2, 5)),
            fallbackReason = FallbackReason.BACKEND_ERROR,
            fallbackDetail = "RunPod returned HTTP 401",
        )

        val (fallback, emptyPages, leftOut) = formatter.format(result, droppedPages = 3)!!.split("\n\n")

        // The backend's own words follow the generic sentence about the fallback.
        assertEquals("RunPod returned HTTP 401", fallback.lines().last())
        assertEquals(2, fallback.lines().size)
        assertTrue(emptyPages, "2, 5" in emptyPages)
        assertTrue(leftOut, "64" in leftOut && "3" in leftOut)
    }

    @Test
    fun `passes on the backend's own warnings`() {
        val result = RecognitionResult(output(warnings = listOf("parsed page by page instead")))

        assertEquals("parsed page by page instead", formatter.format(result, droppedPages = 0))
    }

    private fun output(
        pageCount: Int = 1,
        emptyPageNumbers: List<Int> = emptyList(),
        warnings: List<String> = emptyList(),
    ) = OcrOutput(
        plainText = "text",
        markdown = null,
        engine = EngineType.QUICK,
        pageCount = pageCount,
        emptyPageNumbers = emptyPageNumbers,
        warnings = warnings,
    )
}

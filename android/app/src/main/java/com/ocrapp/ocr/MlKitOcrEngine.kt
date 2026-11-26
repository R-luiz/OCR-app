package com.ocrapp.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * On-device recognition via ML Kit Text Recognition v2 (bundled Latin model).
 *
 * Fast (sub-second per page) and works with no network, but returns flat text with no
 * document structure. Multi-page input is recognized page by page and concatenated.
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    override val type = EngineType.QUICK

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(
        pages: PageSource,
        onProgress: (OcrProgress) -> Unit,
    ): Result<OcrOutput> = withContext(Dispatchers.Default) {
        val pageCount = pages.pageCount
        if (pageCount == 0) {
            return@withContext Result.failure(IllegalArgumentException("No pages to recognize"))
        }
        runCatchingCancellable {
            val text = StringBuilder()
            val empty = mutableListOf<Int>()
            for (index in 0 until pageCount) {
                ensureActive()
                onProgress(OcrProgress(OcrStage.RECOGNIZING, page = index + 1, pageCount = pageCount))
                val recognized = pages.usePage(index, PAGE_EDGE_PX) { recognizePage(it).trim() }
                if (recognized.isEmpty()) {
                    empty += index + 1
                } else {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(recognized)
                }
            }
            OcrOutput(text.toString(), null, EngineType.QUICK, pageCount, empty)
        }
    }

    // ML Kit cannot stop a submitted task. Keep the bitmap alive until it finishes
    // reading it; the scan observes cancellation before rendering the next page.
    private suspend fun recognizePage(bitmap: Bitmap): String =
        suspendCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener { task ->
                    when {
                        task.isSuccessful -> continuation.resume(task.result.text)
                        task.isCanceled -> continuation.resumeWithException(CancellationException())
                        else -> continuation.resumeWithException(
                            task.exception ?: IllegalStateException("Text recognition failed"),
                        )
                    }
                }
        }

    private companion object {
        /**
         * ML Kit wants characters around 16-24 px tall. At this edge an A4 page comes
         * out near 175 dpi, which puts ordinary 10 pt body text inside that range.
         */
        const val PAGE_EDGE_PX = 2048
    }
}

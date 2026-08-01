package com.ocrapp.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
        pages: List<PageImage>,
        onStage: (OcrStage) -> Unit,
    ): Result<OcrOutput> = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No pages to recognize"))
        }
        onStage(OcrStage.RECOGNIZING)
        runCatching {
            val perPage = pages.sortedBy { it.index }.map { page ->
                recognizePage(page)
            }
            OcrOutput(
                plainText = perPage.joinToString(separator = "\n\n").trim(),
                markdown = null,
                engine = EngineType.QUICK,
                pageCount = pages.size,
            )
        }
    }

    private suspend fun recognizePage(page: PageImage): String =
        suspendCancellableCoroutine { continuation: CancellableContinuation<String> ->
            // Bitmaps from PageLoader are already rotated upright, so rotation is 0.
            val input = InputImage.fromBitmap(page.bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { text -> continuation.resume(text.text) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
                .addOnCanceledListener { continuation.cancel() }
        }
}

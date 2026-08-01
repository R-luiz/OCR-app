package com.ocrapp.ocr

import javax.inject.Inject
import javax.inject.Singleton

data class RecognitionResult(
    val output: OcrOutput,
    val fallbackReason: FallbackReason? = null,
)

/**
 * Routes a recognition request to the engine the user picked.
 *
 * Document parsing degrades to Quick scan rather than failing outright: an unconfigured
 * or sleeping endpoint should still give the user their text, with the substitution made
 * visible in the UI.
 */
@Singleton
class OcrRepository @Inject constructor(
    private val quickEngine: MlKitOcrEngine,
    private val documentEngine: RemoteOcrEngine,
) {

    suspend fun recognize(
        pages: List<PageImage>,
        requested: EngineType,
        onStage: (OcrStage) -> Unit = {},
    ): Result<RecognitionResult> {
        if (requested == EngineType.QUICK) {
            return quickEngine.recognize(pages, onStage).map { RecognitionResult(it) }
        }

        val remote = documentEngine.recognize(pages, onStage)
        remote.getOrNull()?.let { return Result.success(RecognitionResult(it)) }

        val error = remote.exceptionOrNull()
        if (error !is BackendUnavailableException) {
            return Result.failure(error ?: IllegalStateException("Recognition failed"))
        }
        return quickEngine.recognize(pages, onStage)
            .map { RecognitionResult(it, error.reason) }
    }
}

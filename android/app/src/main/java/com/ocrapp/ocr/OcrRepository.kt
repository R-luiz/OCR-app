package com.ocrapp.ocr

import javax.inject.Inject
import javax.inject.Singleton

data class RecognitionResult(
    val output: OcrOutput,
    val fallbackReason: FallbackReason? = null,
    /**
     * What the backend actually said, when it said anything — the job's error text, an
     * HTTP status, a timeout. Kept alongside [fallbackReason] because the reason alone
     * cannot tell a user (or a maintainer) *why* a paid backend declined the work, and
     * collapsing every failure into one generic sentence made the fallback impossible
     * to diagnose from the device.
     */
    val fallbackDetail: String? = null,
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
        pages: PageSource,
        requested: EngineType,
        onProgress: (OcrProgress) -> Unit = {},
    ): Result<RecognitionResult> {
        if (requested == EngineType.QUICK) {
            return quickEngine.recognize(pages, onProgress).map { RecognitionResult(it) }
        }

        val remote = documentEngine.recognize(pages, onProgress)
        val error = remote.exceptionOrNull() as? BackendUnavailableException
            ?: return remote.map { RecognitionResult(it) }

        return quickEngine.recognize(pages, onProgress)
            .map { RecognitionResult(it, error.reason, error.message) }
    }
}

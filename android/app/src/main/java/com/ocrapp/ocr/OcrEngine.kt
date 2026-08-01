package com.ocrapp.ocr

import android.graphics.Bitmap

/** Which OCR backend produced (or should produce) a result. */
enum class EngineType {
    /** Google ML Kit Text Recognition v2, fully on-device. */
    QUICK,

    /** Baidu Unlimited-OCR running on a RunPod Serverless endpoint. */
    DOCUMENT,
}

/** A single page of input, already decoded and oriented upright. */
data class PageImage(
    val index: Int,
    val bitmap: Bitmap,
)

/**
 * Result of a recognition run.
 *
 * [markdown] is only populated by [EngineType.DOCUMENT]; ML Kit has no notion of
 * document structure, so the UI falls back to rendering [plainText] when it is null.
 */
data class OcrOutput(
    val plainText: String,
    val markdown: String?,
    val engine: EngineType,
    val pageCount: Int,
)

/**
 * Coarse progress states, surfaced so the user knows why a slow run is slow.
 *
 * [RECOGNIZING] is on-device work and [QUEUED]/[RUNNING] are remote, so the stage
 * also tells the user which engine is actually handling the scan — including when a
 * Document-parsing request has quietly fallen back to on-device recognition.
 */
enum class OcrStage {
    PREPARING,
    RECOGNIZING,
    UPLOADING,
    QUEUED,
    RUNNING,
}

/**
 * Recognizes text across one or more pages.
 *
 * Implementations must be safe to call from any dispatcher; each moves work onto an
 * appropriate one internally.
 */
interface OcrEngine {
    val type: EngineType

    /**
     * @param onStage invoked as the run moves between stages, for progress UI.
     * @return the recognized output, or a failure describing why recognition could
     *   not complete. Implementations do not throw for expected conditions.
     */
    suspend fun recognize(
        pages: List<PageImage>,
        onStage: (OcrStage) -> Unit = {},
    ): Result<OcrOutput>
}

/** Why a Document-parsing request ended up served by the on-device engine instead. */
enum class FallbackReason {
    NOT_CONFIGURED,
    UNREACHABLE,
}

/** Raised when the remote engine is selected but not usable, triggering fallback. */
class BackendUnavailableException(
    val reason: FallbackReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

package com.ocrapp.ocr

import android.graphics.Bitmap
import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException

/** Which OCR backend produced (or should produce) a result. */
enum class EngineType {
    /** Google ML Kit Text Recognition v2, fully on-device. */
    QUICK,

    /** Baidu Unlimited-OCR running on a RunPod Serverless endpoint. */
    DOCUMENT,
    ;

    companion object {
        /** Reads a value stored as [name]; an unknown one is treated as on-device. */
        fun fromStored(name: String): EngineType = entries.firstOrNull { it.name == name } ?: QUICK
    }
}

/**
 * A document whose pages are rasterized on demand.
 *
 * Nothing is decoded until [render] is called and every call hands back a bitmap the
 * caller recycles, so a 60-page PDF costs one page of memory at a time rather than sixty.
 * Each consumer also asks for the size it actually needs, instead of everything being
 * decoded at one size and scaled again downstream.
 * Consumers render and close sequentially, with at most one page in use at a time.
 */
interface PageSource : Closeable {
    val pageCount: Int

    /**
     * Rasterizes page [index] upright with its longer edge at most [maxEdge] pixels.
     * The caller owns the returned bitmap.
     */
    suspend fun render(index: Int, maxEdge: Int): Bitmap
}

/** Renders page [index], hands it to [block], and recycles it afterwards. */
suspend inline fun <T> PageSource.usePage(index: Int, maxEdge: Int, block: (Bitmap) -> T): T {
    val bitmap = render(index, maxEdge)
    try {
        return block(bitmap)
    } finally {
        bitmap.recycle()
    }
}

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
    /**
     * Pages no text was recognized on, by page number (1-based).
     *
     * Surfaced because a document that is short by a page still reads as complete:
     * without this the user is handed a result with content missing and nothing to
     * indicate it.
     */
    val emptyPageNumbers: List<Int> = emptyList(),
    /** What the backend had to say about the run, e.g. that it lost cross-page context. */
    val warnings: List<String> = emptyList(),
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
    SAVING,
}

/**
 * Where a run is. [page] of [pageCount] is set for the per-page stages
 * ([OcrStage.PREPARING], [OcrStage.RECOGNIZING]) and zero for the others.
 */
data class OcrProgress(
    val stage: OcrStage,
    val page: Int = 0,
    val pageCount: Int = 0,
)

/**
 * Recognizes text across one or more pages.
 *
 * Implementations are safe to call from any dispatcher; each moves work onto an
 * appropriate one internally.
 */
interface OcrEngine {
    val type: EngineType

    /**
     * @param onProgress invoked as the run advances, possibly off the caller's thread.
     * @return the recognized output, or a failure describing why recognition could
     *   not complete. Implementations do not throw for expected conditions.
     */
    suspend fun recognize(
        pages: PageSource,
        onProgress: (OcrProgress) -> Unit = {},
    ): Result<OcrOutput>
}

/**
 * Why a Document-parsing request ended up served by the on-device engine instead.
 *
 * [BACKEND_ERROR] is deliberately distinct from [UNREACHABLE]: a job that ran and came
 * back with an error means the endpoint answered fine, and reporting that as
 * "unreachable" sends the user to check their network for a problem that is not there.
 */
enum class FallbackReason {
    NOT_CONFIGURED,
    UNREACHABLE,
    BACKEND_ERROR,
}

/** Raised when the remote engine is selected but not usable, triggering fallback. */
class BackendUnavailableException(
    val reason: FallbackReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * [runCatching] that lets coroutine cancellation through.
 *
 * Trapping a [CancellationException] in a [Result] turns "the user cancelled" into an
 * ordinary failure, and the caller then carries on with work nobody is waiting for.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

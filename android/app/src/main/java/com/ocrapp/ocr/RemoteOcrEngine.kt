package com.ocrapp.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs Baidu Unlimited-OCR on a RunPod Serverless endpoint: the managed service's, or
 * the user's own, whichever the settings select.
 *
 * A document normally travels as one job, so the model's long-context decoder can carry
 * structure across page breaks. RunPod caps a `/run` body at 10 MB, though, so a
 * document too large for that is sent as consecutive jobs — context is lost only at
 * those few seams, instead of the whole upload being refused.
 */
@Singleton
class RemoteOcrEngine internal constructor(
    private val backends: OcrBackends,
    private val connections: OcrConnectionProvider,
    private val maxJobChars: Long,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) : OcrEngine {

    @Inject
    constructor(backends: OcrBackends, connections: OcrConnectionProvider) :
        this(backends, connections, MAX_JOB_CHARS)

    override val type = EngineType.DOCUMENT

    override suspend fun recognize(
        pages: PageSource,
        onProgress: (OcrProgress) -> Unit,
    ): Result<OcrOutput> = withContext(Dispatchers.IO) {
        if (pages.pageCount == 0) {
            return@withContext Result.failure(IllegalArgumentException("No pages to recognize"))
        }
        runCatchingCancellable {
            // One connection for the whole scan, however many jobs it takes and whatever
            // the user changes in Settings meanwhile.
            val route = backends.route(selectConnection())
            parse(pages, route, onProgress)
        }
    }

    /** The connection this scan runs on; without one it falls back to on-device recognition. */
    private suspend fun selectConnection(): OcrConnection {
        val lookup = runCatchingCancellable { connections.lookup() }
            .getOrElse { error ->
                throw BackendUnavailableException(
                    FallbackReason.BACKEND_ERROR, "Could not read the saved backend settings", error,
                )
            }
        return when (lookup) {
            is ConnectionLookup.Available -> lookup.connection
            ConnectionLookup.NotConfigured ->
                throw BackendUnavailableException(FallbackReason.NOT_CONFIGURED, "No OCR backend is configured")
            ConnectionLookup.SessionExpired ->
                throw BackendUnavailableException(FallbackReason.SESSION_EXPIRED, "The sign-in has expired")
        }
    }

    private suspend fun parse(
        pages: PageSource,
        route: OcrRoute,
        onProgress: (OcrProgress) -> Unit,
    ): OcrOutput {
        val pageCount = pages.pageCount
        val maxEdge = if (pageCount == 1) SINGLE_PAGE_EDGE_PX else MULTI_PAGE_EDGE_PX
        val managed = route.connection is OcrConnection.Managed
        val jobLimit = if (managed) minOf(maxJobChars, MAX_MANAGED_JOB_CHARS) else maxJobChars

        val parts = mutableListOf<JobResult>()
        var images = mutableListOf<String>()
        var chars = 0L

        // Only the job being assembled is held in memory: pages are encoded one at a
        // time, and a full job is sent and dropped before the next one is started.
        for (index in 0 until pageCount) {
            onProgress(OcrProgress(OcrStage.PREPARING, page = index + 1, pageCount = pageCount))
            val image = pages.usePage(index, maxEdge) { bitmap ->
                val jpeg = bitmap.toJpegDataUrl()
                if (managed && jpeg.length > jobLimit) bitmap.toJpegDataUrl(quality = 65) else jpeg
            }
            if (image.length > jobLimit) {
                throw BackendUnavailableException(FallbackReason.BACKEND_ERROR, "A page exceeds the ${route.service} upload limit")
            }

            val isFull = images.size == MAX_JOB_PAGES || chars + image.length > jobLimit
            if (images.isNotEmpty() && isFull) {
                parts += JobResult(index - images.size, images.size, runJob(route, images, onProgress))
                images = mutableListOf()
                chars = 0
            }
            images += image
            chars += image.length
        }
        parts += JobResult(pageCount - images.size, images.size, runJob(route, images, onProgress))

        return toOutput(parts, pageCount)
    }

    private suspend fun runJob(
        route: OcrRoute,
        images: List<String>,
        onProgress: (OcrProgress) -> Unit,
    ): OcrJobOutput {
        onProgress(OcrProgress(OcrStage.UPLOADING))
        checkSessionOutlastsJob(route)
        val submitted = backendCall(route) { route.submit(RunPodRequest(OcrJobInput(images))) }

        // A worker that was already warm can complete inside the submit call.
        val finished = if (submitted.isTerminal) submitted else await(route, submitted, onProgress)

        val error = finished.errorMessage
        if (!finished.isSuccess || error != null) {
            throw BackendUnavailableException(
                FallbackReason.BACKEND_ERROR,
                error ?: "${route.service} job finished with status ${finished.status}",
            )
        }
        return finished.output
            ?: throw BackendUnavailableException(
                FallbackReason.BACKEND_ERROR,
                "${route.service} job completed with no output",
            )
    }

    /**
     * A managed session must have a whole job ahead of it each time a job is sent, not
     * only when the scan started: a long document runs job after job, and one sent in the
     * session's last minutes could be neither collected nor cancelled. The stored session
     * is kept, since the service still accepts it; the next lookup reads it as expired.
     */
    private fun checkSessionOutlastsJob(route: OcrRoute) {
        val connection = route.connection
        if (connection is OcrConnection.Managed && !connection.isUsableAt(nowSeconds())) {
            throw BackendUnavailableException(
                FallbackReason.SESSION_EXPIRED,
                "The sign-in ends before another job could finish",
            )
        }
    }

    /**
     * Polls [submitted] to a terminal status.
     *
     * However this stops short of one — the user cancelled, the wait timed out, the
     * endpoint stopped answering — the job is cancelled on RunPod too. Left alone it
     * would sit in the queue or keep a GPU busy, billing for a result nobody collects.
     */
    private suspend fun await(
        route: OcrRoute,
        submitted: RunPodJob,
        onProgress: (OcrProgress) -> Unit,
    ): RunPodJob {
        val jobId = submitted.id
            ?: throw BackendUnavailableException(
                FallbackReason.BACKEND_ERROR,
                "${route.service} did not return a job id",
            )

        var finished = false
        try {
            val initialStage = submitted.activeStage()
            onProgress(OcrProgress(initialStage))
            val job = withTimeoutOrNull(JOB_TIMEOUT) { poll(route, jobId, initialStage, onProgress) }
                ?: throw BackendUnavailableException(
                    FallbackReason.UNREACHABLE,
                    "Timed out after ${JOB_TIMEOUT.inWholeMinutes} minutes waiting for job $jobId",
                )
            finished = true
            return job
        } finally {
            if (!finished) cancelRemoteJob(route, jobId)
        }
    }

    private suspend fun poll(
        route: OcrRoute,
        jobId: String,
        initialStage: OcrStage,
        onProgress: (OcrProgress) -> Unit,
    ): RunPodJob {
        var interval = INITIAL_POLL_INTERVAL
        var lastStage = initialStage
        var failures = 0

        while (true) {
            delay(interval)
            interval = minOf(interval * BACKOFF_FACTOR, MAX_POLL_INTERVAL)

            val job = try {
                route.status(jobId)
            } catch (error: Exception) {
                // A phone changes networks and drops requests as a matter of course,
                // and the job runs on regardless. One failed poll is not a reason to
                // abandon a GPU run that has been cold-starting for minutes.
                if (error.isTransient() && ++failures <= MAX_POLL_FAILURES) continue
                throw error.toBackendError(route)
            }
            failures = 0
            if (job.isTerminal) return job

            // Distinguishing "waiting for a GPU" from "actually parsing" matters here:
            // a cold start can sit in IN_QUEUE for minutes while the worker comes up.
            val stage = job.activeStage()
            if (stage != lastStage) {
                onProgress(OcrProgress(stage))
                lastStage = stage
            }
        }
    }

    /** Best effort and bounded: it runs while the caller is already being torn down. */
    private suspend fun cancelRemoteJob(route: OcrRoute, jobId: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_TIMEOUT) {
                runCatchingCancellable {
                    route.cancel(jobId)
                }
            }
        }
    }

    private fun RunPodJob.activeStage(): OcrStage =
        if (status == RunPodJob.IN_PROGRESS) OcrStage.RUNNING else OcrStage.QUEUED

    private fun toOutput(parts: List<JobResult>, pageCount: Int): OcrOutput {
        val markdown = parts.joinToString(PAGE_SEPARATOR) { part ->
            part.output.markdown.ifBlank {
                part.output.pages.sortedBy { it.index }.joinToString(PAGE_SEPARATOR) { it.markdown }
            }
        }.trim()
        val missing = parts.flatMap { it.missingPageNumbers() }

        return OcrOutput(
            // The worker adds explanatory Markdown even when not a single page has text.
            plainText = if (missing.size == pageCount) "" else MarkdownToPlainText.convert(markdown),
            markdown = markdown,
            engine = EngineType.DOCUMENT,
            pageCount = pageCount,
            emptyPageNumbers = missing,
            warnings = parts.flatMap { it.output.warnings }.distinct(),
        )
    }

    /**
     * Best-effort reachability probe used by the Settings screen, on the host of
     * [connection] and with its credential only.
     */
    suspend fun testConnection(connection: OcrConnection): Result<RunPodHealth> =
        runCatchingCancellable {
            val route = backends.route(connection)
            backendCall(route) { route.health() }
        }

    /**
     * Runs a call on [route], translating everything the backend can plausibly do wrong —
     * no network, a wrong endpoint id, a revoked key or session, a body the app cannot
     * read — into [BackendUnavailableException], so the caller can fall back to on-device
     * recognition instead of surfacing a hard error.
     */
    private suspend inline fun <T> backendCall(route: OcrRoute, block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            throw error.toBackendError(route)
        }

    private suspend fun Exception.toBackendError(route: OcrRoute): Exception = when (this) {
        is CancellationException, is BackendUnavailableException -> this

        is IOException -> BackendUnavailableException(
            FallbackReason.UNREACHABLE,
            "Could not reach the ${route.service} endpoint",
            this,
        )

        is HttpException -> {
            val connection = route.connection
            if (connection is OcrConnection.Managed && code() == HTTP_UNAUTHORIZED) {
                // The gateway answers 401 only for a session it does not accept. Forgetting
                // it turns scans that would all fail the same way into a request to sign in
                // again. Its 502 and 503 are about the gateway or its upstream, not about
                // the session, so they stay ordinary backend errors and the session is kept.
                runCatchingCancellable { connections.sessionRejected(connection) }
                BackendUnavailableException(
                    FallbackReason.SESSION_EXPIRED,
                    "The sign-in was rejected (HTTP ${code()})",
                    this,
                )
            } else {
                BackendUnavailableException(
                    FallbackReason.BACKEND_ERROR,
                    "${route.service} returned HTTP ${code()}",
                    this,
                )
            }
        }

        is SerializationException -> BackendUnavailableException(
            FallbackReason.BACKEND_ERROR,
            "${route.service} returned a response the app could not read",
            this,
        )

        else -> this
    }

    /** One job's result, and which slice of the document it covers. */
    private class JobResult(
        val firstPage: Int,
        val pageCount: Int,
        val output: OcrJobOutput,
    ) {
        /**
         * Two ways a page can go missing, and both must be visible rather than left to
         * be inferred from a document that looks complete: the backend recognized
         * nothing on a page and said so, or it described fewer pages than were sent.
         *
         * Neither test applies when the page boundaries are unknown. There the whole
         * document sits in one entry, so a short `pages` list says nothing about lost
         * text, and counting it as lost pages would warn about an intact document.
         */
        fun missingPageNumbers(): List<Int> {
            if (output.isLongContext) return emptyList()
            // Older responses can contain only the document Markdown. When a page
            // list is present, use identities, not its length: a middle page can be missing.
            val present = BooleanArray(pageCount) { output.pages.isEmpty() }
            for (page in output.pages) {
                if (page.index in present.indices && page.markdown.isNotBlank()) present[page.index] = true
            }
            for (index in output.emptyPages) {
                if (index in present.indices) present[index] = false
            }
            return buildList {
                for (index in present.indices) {
                    if (!present[index]) add(firstPage + index + 1)
                }
            }
        }
    }

    private companion object {
        /**
         * One page gets the model's "gundam" mode, which tiles the image into 640px
         * crops (up to 2 x 3 for a portrait page, so about 1280 x 1920) on top of a
         * 1024px overview. Sending less than that only makes the worker upscale.
         */
        const val SINGLE_PAGE_EDGE_PX = 1920

        /** Several pages get the flat 1024px mode; anything larger is wasted upload. */
        const val MULTI_PAGE_EDGE_PX = 1024

        /**
         * RunPod refuses a `/run` body over 10 MB. Counted in characters of image
         * payload (all ASCII, so one byte each), with headroom for the JSON around it.
         */
        const val MAX_JOB_CHARS = 9L * 1024 * 1024

        /** The gateway accepts 2 MiB (MAX_RUN_BODY_BYTES); reserve 1 KiB for the JSON envelope. */
        const val MAX_MANAGED_JOB_CHARS = 2L * 1024 * 1024 - 1024

        /** The worker's own per-job page limit (`MAX_PAGES` in `backend/handler.py`). */
        const val MAX_JOB_PAGES = 64

        const val PAGE_SEPARATOR = "\n\n"

        val INITIAL_POLL_INTERVAL: Duration = 1_000.milliseconds
        val MAX_POLL_INTERVAL: Duration = 5_000.milliseconds
        const val BACKOFF_FACTOR = 1.5

        /** Consecutive failed polls tolerated before the endpoint counts as gone. */
        const val MAX_POLL_FAILURES = 5

        /** A cold start alone is about five minutes; the parse comes on top of it. */
        val JOB_TIMEOUT: Duration = 15.minutes
        val CANCEL_TIMEOUT: Duration = 5.seconds

        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Failures worth polling through: a dropped connection, rate limiting, a 5xx. */
private fun Exception.isTransient(): Boolean = when (this) {
    is CancellationException -> false
    is IOException -> true
    is HttpException -> code() == HTTP_TOO_MANY_REQUESTS || code() >= HTTP_SERVER_ERROR
    else -> false
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

package com.ocrapp.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Runs Baidu Unlimited-OCR on the user's RunPod Serverless endpoint.
 *
 * Submits one job for the whole document — Unlimited-OCR's R-SWA decoder is built to
 * parse many pages in a single 32K-context pass, so splitting pages into separate jobs
 * would both cost more and lose cross-page context.
 */
@Singleton
class RemoteOcrEngine @Inject constructor(
    private val api: RunPodApi,
    private val credentialsProvider: RunPodCredentialsProvider,
    private val normalizer: ImageNormalizer,
) : OcrEngine {

    override val type = EngineType.DOCUMENT

    override suspend fun recognize(
        pages: List<PageImage>,
        onStage: (OcrStage) -> Unit,
    ): Result<OcrOutput> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No pages to recognize"))
        }

        val credentials = credentialsProvider.credentials()
        if (credentials == null || !credentials.isComplete) {
            return@withContext Result.failure(
                BackendUnavailableException(
                    FallbackReason.NOT_CONFIGURED,
                    "RunPod endpoint is not configured",
                ),
            )
        }

        runCatching {
            onStage(OcrStage.PREPARING)
            val ordered = pages.sortedBy { it.index }
            val images = ordered.map { normalizer.toDataUrl(it.bitmap) }

            onStage(OcrStage.UPLOADING)
            val job = api.submit(
                endpointId = credentials.endpointId,
                authorization = credentials.authorizationHeader,
                request = RunPodRequest(input = buildInput(images)),
            )

            // A worker that was already warm can complete inside the submit call.
            val finished = if (job.isTerminal) job else poll(credentials, job, onStage)
            toOutput(finished, ordered.size)
        }.recoverCatching { error ->
            // Anything the endpoint can plausibly do wrong — no network, a wrong
            // endpoint id, a revoked key — is reported as "unavailable" so the caller
            // can fall back to on-device recognition instead of surfacing a hard error.
            throw when (error) {
                is BackendUnavailableException -> error

                is IOException -> BackendUnavailableException(
                    FallbackReason.UNREACHABLE,
                    "Could not reach the RunPod endpoint",
                    error,
                )

                is HttpException -> BackendUnavailableException(
                    FallbackReason.BACKEND_ERROR,
                    "RunPod returned HTTP ${error.code()}",
                    error,
                )

                else -> error
            }
        }
    }

    private fun buildInput(images: List<String>): OcrJobInput =
        if (images.size == 1) {
            // "gundam": 640px crops over a 1024px base, the configuration Baidu
            // documents for best single-image quality.
            OcrJobInput(
                mode = OcrJobInput.MODE_SINGLE,
                images = images,
                baseSize = 1024,
                imageSize = 640,
                cropMode = true,
            )
        } else {
            // "base": multi-page parsing only supports the flat 1024px path.
            OcrJobInput(
                mode = OcrJobInput.MODE_MULTI,
                images = images,
                imageSize = 1024,
                cropMode = false,
            )
        }

    private suspend fun poll(
        credentials: RunPodCredentials,
        submitted: RunPodJob,
        onStage: (OcrStage) -> Unit,
    ): RunPodJob {
        val jobId = submitted.id
            ?: throw BackendUnavailableException(
                FallbackReason.UNREACHABLE,
                "RunPod did not return a job id",
            )

        var interval = INITIAL_POLL_INTERVAL
        var lastStage: OcrStage? = null

        return try {
            withTimeout(OVERALL_TIMEOUT) {
                var job = submitted
                while (!job.isTerminal) {
                    delay(interval)
                    interval = min(
                        (interval.inWholeMilliseconds * BACKOFF_FACTOR).toLong(),
                        MAX_POLL_INTERVAL.inWholeMilliseconds,
                    ).milliseconds

                    job = api.status(
                        endpointId = credentials.endpointId,
                        jobId = jobId,
                        authorization = credentials.authorizationHeader,
                    )

                    // Distinguishing "waiting for a GPU" from "actually parsing" matters
                    // here: a cold start can sit in IN_QUEUE for a minute or more while
                    // the worker pulls ~6.7 GB of weights.
                    //
                    // Terminal statuses report no stage at all: COMPLETED is not
                    // IN_PROGRESS, so treating it like any other status would flash
                    // "waiting for a worker" at the moment the job actually finished.
                    if (!job.isTerminal) {
                        val stage = when (job.status) {
                            RunPodJob.IN_PROGRESS -> OcrStage.RUNNING
                            else -> OcrStage.QUEUED
                        }
                        if (stage != lastStage) {
                            onStage(stage)
                            lastStage = stage
                        }
                    }
                }
                job
            }
        } catch (timeout: TimeoutCancellationException) {
            throw BackendUnavailableException(
                FallbackReason.UNREACHABLE,
                "Timed out after ${OVERALL_TIMEOUT.inWholeMinutes} minutes waiting for job $jobId",
                timeout,
            )
        }
    }

    private fun toOutput(job: RunPodJob, pageCount: Int): OcrOutput {
        if (!job.isSuccess) {
            throw BackendUnavailableException(
                FallbackReason.BACKEND_ERROR,
                job.error ?: "RunPod job finished with status ${job.status}",
            )
        }
        val output = job.output
            ?: throw BackendUnavailableException(
                FallbackReason.BACKEND_ERROR,
                "RunPod job completed with no output",
            )

        val markdown = output.markdown.ifBlank {
            output.pages.sortedBy { it.index }.joinToString("\n\n") { it.markdown }
        }
        // Two ways a page can go missing, and both must be visible rather than left
        // to be inferred from a document that looks complete: the backend recognized
        // nothing on a page and said so, or it described fewer pages than were sent.
        //
        // Neither test applies to long-context output. There the model produces one
        // stream for the whole document and the backend's page split is a guess, so a
        // short `pages` list means the delimiter was not found — the text is all
        // present in the markdown. Counting that as lost pages would warn about a
        // document that is entirely intact.
        val missing = if (output.isLongContext) {
            emptyList()
        } else {
            val describedPages = output.pages.size.takeIf { it > 0 } ?: pageCount
            (output.emptyPages.map { it + 1 } +
                ((describedPages + 1)..pageCount)).distinct().sorted()
        }

        return OcrOutput(
            plainText = MarkdownToPlainText.convert(markdown),
            markdown = markdown,
            engine = EngineType.DOCUMENT,
            pageCount = pageCount,
            emptyPageNumbers = missing,
        )
    }

    /** Best-effort reachability probe used by the Settings screen. */
    suspend fun testConnection(credentials: RunPodCredentials): Result<RunPodHealth> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.health(credentials.endpointId, credentials.authorizationHeader)
            }
        }

    companion object {
        private val INITIAL_POLL_INTERVAL: Duration = 1_000.milliseconds
        private val MAX_POLL_INTERVAL: Duration = 5_000.milliseconds
        private val OVERALL_TIMEOUT: Duration = 10.minutes
        private const val BACKOFF_FACTOR = 1.5
    }
}

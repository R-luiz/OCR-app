package com.ocrapp.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Wire types for the RunPod Serverless REST API.
 *
 * Jobs are submitted asynchronously with `/run` and polled with `/status/{id}`.
 * The synchronous `/runsync` endpoint is deliberately unused: it gives up after 90
 * seconds, and a multi-page parse — especially behind a cold start that has to load
 * ~6.7 GB of weights — routinely runs longer than that.
 */
interface RunPodApi {

    @POST("v2/{endpointId}/run")
    suspend fun submit(
        @Path("endpointId") endpointId: String,
        @Header("Authorization") authorization: String,
        @Body request: RunPodRequest,
    ): RunPodJob

    @GET("v2/{endpointId}/status/{jobId}")
    suspend fun status(
        @Path("endpointId") endpointId: String,
        @Path("jobId") jobId: String,
        @Header("Authorization") authorization: String,
    ): RunPodJob

    /** Stops a queued or running job, so an abandoned scan stops costing GPU time. */
    @POST("v2/{endpointId}/cancel/{jobId}")
    suspend fun cancel(
        @Path("endpointId") endpointId: String,
        @Path("jobId") jobId: String,
        @Header("Authorization") authorization: String,
    ): RunPodJob

    @GET("v2/{endpointId}/health")
    suspend fun health(
        @Path("endpointId") endpointId: String,
        @Header("Authorization") authorization: String,
    ): RunPodHealth
}

@Serializable
data class RunPodRequest(
    val input: OcrJobInput,
)

/**
 * Mirrors the job shape in `backend/handler.py`: the pages, in order, as `data:` URLs.
 *
 * The worker picks the model configuration from the page count — cropped "gundam"
 * tiles for one page, the flat multi-page path for several — so there is nothing else
 * for the client to say.
 */
@Serializable
data class OcrJobInput(
    val images: List<String>,
)

@Serializable
data class RunPodJob(
    val id: String? = null,
    val status: String? = null,
    val output: OcrJobOutput? = null,
    /** Usually a string, but RunPod does not promise that, so any JSON is accepted. */
    val error: JsonElement? = null,
) {
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    val isSuccess: Boolean
        get() = status == COMPLETED

    /** The failure in the backend's own words, when it gave any. */
    val errorMessage: String?
        get() = error.asErrorMessage() ?: output?.error.asErrorMessage()

    companion object {
        const val IN_QUEUE = "IN_QUEUE"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
        const val TIMED_OUT = "TIMED_OUT"

        private val TERMINAL_STATUSES = setOf(COMPLETED, FAILED, CANCELLED, TIMED_OUT)
    }
}

@Serializable
data class OcrJobOutput(
    val markdown: String = "",
    val pages: List<OcrJobPage> = emptyList(),
    /** Pages the model produced no text for, by zero-based index. */
    @SerialName("empty_pages") val emptyPages: List<Int> = emptyList(),
    /**
     * Whether [pages] are exact boundaries or the whole document in one entry.
     *
     * Under `long_context` the model emitted one stream and the worker could not tell
     * where its pages divide, so a page "missing" from the list means the boundary was
     * not found — not that the page went unread. Defaults to the per-page value so an
     * older backend keeps its existing, exact meaning.
     */
    @SerialName("page_mode") val pageMode: String = PAGE_MODE_PER_PAGE,
    val warnings: List<String> = emptyList(),
    val error: JsonElement? = null,
) {
    val isLongContext: Boolean get() = pageMode == PAGE_MODE_LONG_CONTEXT

    companion object {
        const val PAGE_MODE_PER_PAGE = "per_page"
        const val PAGE_MODE_LONG_CONTEXT = "long_context"
    }
}

private fun JsonElement?.asErrorMessage(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}?.takeIf { it.isNotBlank() }

@Serializable
data class OcrJobPage(
    val index: Int,
    val markdown: String,
)

@Serializable
data class RunPodHealth(
    val workers: RunPodWorkers? = null,
)

@Serializable
data class RunPodWorkers(
    val idle: Int = 0,
    val ready: Int = 0,
    val running: Int = 0,
)

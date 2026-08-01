package com.ocrapp.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * Mirrors the handler signature in `backend/handler.py`.
 *
 * The `single`/`multi` split matches Unlimited-OCR's two documented configurations:
 * "gundam" (cropped 640px tiles) for one page, and "base" (flat 1024px) for the
 * long-horizon multi-page path.
 */
@Serializable
data class OcrJobInput(
    val mode: String,
    val images: List<String>,
    @SerialName("base_size") val baseSize: Int? = null,
    @SerialName("image_size") val imageSize: Int? = null,
    @SerialName("crop_mode") val cropMode: Boolean? = null,
) {
    companion object {
        const val MODE_SINGLE = "single"
        const val MODE_MULTI = "multi"
    }
}

@Serializable
data class RunPodJob(
    val id: String? = null,
    val status: String? = null,
    val output: OcrJobOutput? = null,
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    val isSuccess: Boolean
        get() = status == COMPLETED

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
    val model: String? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
)

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

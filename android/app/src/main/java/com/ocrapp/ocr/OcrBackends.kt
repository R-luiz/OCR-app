package com.ocrapp.ocr

/** The hosts the app talks to. Fixed: nothing the user types or a server answers changes them. */
object OcrHosts {
    const val RUNPOD_BASE_URL = "https://api.runpod.ai/"
    const val MANAGED_BASE_URL = "https://ocr.robinluiz.fr/"

    /** The managed service answers on the RunPod paths, with this in the endpoint position. */
    const val MANAGED_ENDPOINT_ID = "ocr"
}

/**
 * The two places a job can run, and the only way to reach either of them.
 *
 * Each [RunPodApi] is built for one fixed host. [route] pairs a connection with the API
 * for its host, so a managed session never reaches RunPod and a RunPod key never reaches
 * the managed service.
 */
class OcrBackends(
    private val runPod: RunPodApi,
    private val managed: RunPodApi,
) {
    fun route(connection: OcrConnection): OcrRoute = when (connection) {
        is OcrConnection.Managed -> OcrRoute(connection, managed, OcrHosts.MANAGED_ENDPOINT_ID, SERVICE_MANAGED)
        is OcrConnection.Direct ->
            OcrRoute(connection, runPod, connection.credentials.endpointId, SERVICE_RUNPOD)
    }

    private companion object {
        const val SERVICE_MANAGED = "OCR service"
        const val SERVICE_RUNPOD = "RunPod"
    }
}

/**
 * One connection bound to its host: the snapshot a whole scan runs on.
 *
 * Every call — submit, poll, cancel, health — goes through here, so they all carry the
 * same credential to the same place.
 */
class OcrRoute internal constructor(
    val connection: OcrConnection,
    private val api: RunPodApi,
    private val endpointId: String,
    /** Who answers, for the messages shown when it does not. */
    val service: String,
) {
    suspend fun submit(request: RunPodRequest): RunPodJob =
        api.submit(endpointId, connection.authorizationHeader, request)

    suspend fun status(jobId: String): RunPodJob =
        api.status(endpointId, jobId, connection.authorizationHeader)

    suspend fun cancel(jobId: String): RunPodJob =
        api.cancel(endpointId, jobId, connection.authorizationHeader)

    suspend fun health(): RunPodHealth =
        api.health(endpointId, connection.authorizationHeader)
}

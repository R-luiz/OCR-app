package com.ocrapp.ocr

/** Endpoint coordinates for the user's own RunPod Serverless deployment. */
data class RunPodCredentials(
    val endpointId: String,
    val apiKey: String,
) {
    val authorizationHeader: String get() = "Bearer $apiKey"

    val isComplete: Boolean get() = endpointId.isNotBlank() && apiKey.isNotBlank()

    /** Left out of logs and crash reports: the key can spend money on the user's account. */
    override fun toString(): String = "RunPodCredentials(endpointId=$endpointId, apiKey=<redacted>)"
}

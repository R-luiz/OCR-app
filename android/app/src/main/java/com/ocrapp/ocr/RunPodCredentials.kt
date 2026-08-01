package com.ocrapp.ocr

/** Endpoint coordinates for the user's own RunPod Serverless deployment. */
data class RunPodCredentials(
    val endpointId: String,
    val apiKey: String,
) {
    val authorizationHeader: String get() = "Bearer $apiKey"

    val isComplete: Boolean get() = endpointId.isNotBlank() && apiKey.isNotBlank()
}

/**
 * Supplies RunPod credentials to [RemoteOcrEngine].
 *
 * Kept as an interface so the engine can be unit-tested against a MockWebServer
 * without pulling in EncryptedSharedPreferences.
 */
fun interface RunPodCredentialsProvider {
    suspend fun credentials(): RunPodCredentials?
}

package com.ocrapp.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The managed service's end of the GitHub sign-in.
 *
 * The app never talks to GitHub's API and never sees a GitHub token: GitHub sends the
 * app a one-time code, and the service trades it, with the PKCE verifier only this app
 * holds, for a session of its own.
 */
interface OcrAuthApi {

    @POST("auth/token")
    suspend fun exchangeCode(@Body request: TokenRequest): TokenResponse

    /** Voids the session in [authorization] at the service, and every older one with it. */
    @POST("auth/signout")
    suspend fun signOut(@Header("Authorization") authorization: String)
}

/** Both values are single-use secrets of one sign-in, and are never logged. */
@Serializable
data class TokenRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
) {
    override fun toString(): String = "TokenRequest(<redacted>)"
}

/** [token] is present only when [status] is [AUTHORIZED]. */
@Serializable
data class TokenResponse(
    val status: String,
    val token: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
) {
    override fun toString(): String = "TokenResponse(status=$status)"

    companion object {
        const val AUTHORIZED = "authorized"

        /** The GitHub account is not one the service accepts. */
        const val FORBIDDEN = "forbidden"

        /** The code was wrong, had expired, was already used, or belongs to another verifier. */
        const val INVALID = "invalid"
    }
}

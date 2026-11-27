package com.ocrapp.ocr

/**
 * Which remote backend Document parsing goes to.
 *
 * The user picks, and nothing switches it behind their back: a session that ran out
 * never turns into a request to the other backend, because that would send their pages
 * to a service they did not choose for that scan.
 */
enum class ConnectionMode {
    /** The managed OCR service ([OcrHosts.MANAGED_BASE_URL]), reached with a GitHub sign-in. */
    MANAGED,

    /** The user's own RunPod endpoint ([OcrHosts.RUNPOD_BASE_URL]), reached with their API key. */
    DIRECT,
}

/**
 * The session the managed service issued once a GitHub sign-in was approved.
 *
 * [expiresAtSeconds] is Unix time, as the service reports it.
 */
data class ManagedSession(
    val token: String,
    val expiresAtSeconds: Long,
) {
    /**
     * Whether a job started at [nowSeconds] can be expected to finish inside the session.
     *
     * A job takes its upload, then up to the engine's 15-minute timeout, then a cancel if it
     * overran; a session that lapses halfway leaves a GPU job nobody can cancel or collect.
     * So a session in its last [MIN_REMAINING_SECONDS] counts as expired and asks for a new
     * sign-in.
     */
    fun isUsableAt(nowSeconds: Long): Boolean = expiresAtSeconds - MIN_REMAINING_SECONDS > nowSeconds

    /** Left out of logs and crash reports. */
    override fun toString(): String = "ManagedSession(expiresAtSeconds=$expiresAtSeconds)"

    companion object {
        const val MIN_REMAINING_SECONDS = 25L * 60
    }
}

/**
 * The credential for one remote backend, as a scan reads it once and keeps it.
 *
 * Which host it is sent to follows from the variant alone ([OcrBackends.route]), so a
 * credential cannot end up at the other backend.
 */
sealed interface OcrConnection {
    val authorizationHeader: String

    /**
     * The managed service; [sessionToken] only ever travels to [OcrHosts.MANAGED_BASE_URL].
     * [expiresAtSeconds] travels with it so that every job of a scan, not only the first,
     * can be checked against [ManagedSession.MIN_REMAINING_SECONDS].
     */
    data class Managed(val sessionToken: String, val expiresAtSeconds: Long) : OcrConnection {
        override val authorizationHeader: String get() = "Bearer $sessionToken"

        /** [ManagedSession.isUsableAt], for the session this connection was made from. */
        fun isUsableAt(nowSeconds: Long): Boolean =
            expiresAtSeconds - ManagedSession.MIN_REMAINING_SECONDS > nowSeconds

        override fun toString(): String = "Managed(sessionToken=<redacted>, expiresAtSeconds=$expiresAtSeconds)"
    }

    /** The user's own RunPod endpoint; the key only ever travels to [OcrHosts.RUNPOD_BASE_URL]. */
    data class Direct(val credentials: RunPodCredentials) : OcrConnection {
        override val authorizationHeader: String get() = credentials.authorizationHeader
    }
}

/** What the stored settings amount to at one moment. */
sealed interface ConnectionLookup {
    data class Available(val connection: OcrConnection) : ConnectionLookup

    /** Nothing usable has been set up for the selected backend. */
    data object NotConfigured : ConnectionLookup

    /** The managed backend is selected and its sign-in has run out. */
    data object SessionExpired : ConnectionLookup
}

/**
 * Everything stored about the remote backends, read together at [readAtSeconds].
 *
 * Working from one snapshot keeps the mode, the keys and the clock consistent with each
 * other, however long the scan that uses it then runs.
 */
data class BackendSettings(
    val mode: ConnectionMode,
    val direct: RunPodCredentials,
    val session: ManagedSession?,
    val readAtSeconds: Long,
) {
    /** The managed backend's state, whichever backend is selected. */
    fun managed(): ConnectionLookup = when {
        session == null -> ConnectionLookup.NotConfigured
        session.isUsableAt(readAtSeconds) ->
            ConnectionLookup.Available(OcrConnection.Managed(session.token, session.expiresAtSeconds))
        else -> ConnectionLookup.SessionExpired
    }

    /** The connection a scan uses: the selected backend's, and never the other one's. */
    fun selected(): ConnectionLookup = when (mode) {
        ConnectionMode.MANAGED -> managed()
        ConnectionMode.DIRECT ->
            if (direct.isComplete) {
                ConnectionLookup.Available(OcrConnection.Direct(direct))
            } else {
                ConnectionLookup.NotConfigured
            }
    }
}

/**
 * Supplies [RemoteOcrEngine] with the connection to use.
 *
 * Kept as an interface so the engine can be unit-tested against a MockWebServer
 * without pulling in EncryptedSharedPreferences.
 */
interface OcrConnectionProvider {
    suspend fun lookup(): ConnectionLookup

    /**
     * The managed service refused [connection]: the session was revoked or has expired.
     * The provider forgets it, unless a newer sign-in has replaced it in the meantime.
     */
    suspend fun sessionRejected(connection: OcrConnection.Managed)
}

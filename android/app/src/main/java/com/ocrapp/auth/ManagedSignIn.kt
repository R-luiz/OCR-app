package com.ocrapp.auth

import com.ocrapp.ocr.ManagedSession
import com.ocrapp.ocr.OcrConnection
import com.ocrapp.ocr.OcrHosts
import com.ocrapp.ocr.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** The GitHub App the managed service signs in with. A client ID is public. */
internal const val GITHUB_CLIENT_ID = "Iv23liB9UIaDsQMJrCwq"

/**
 * Where GitHub sends the browser back: the GitHub App's callback URL, and an App Link
 * that Android hands to [AuthCallbackActivity].
 */
internal const val SIGN_IN_CALLBACK_URL = "${OcrHosts.MANAGED_BASE_URL}auth/callback"

private const val GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"

/** How a sign-in ended. */
sealed interface SignInOutcome {
    data class Authorized(val session: ManagedSession) : SignInOutcome

    /** The sign-in ran out of time, or GitHub's code was refused as expired or already used. */
    data object Expired : SignInOutcome

    /** The user refused it on GitHub. */
    data object Denied : SignInOutcome

    /** The GitHub account is not one the service accepts. */
    data object Forbidden : SignInOutcome

    /** GitHub's answer was for a sign-in this app did not start: its `state` did not match. */
    data object Mismatch : SignInOutcome

    /** The service could not be reached. */
    data object Unreachable : SignInOutcome

    /** The service answered, but not with anything usable. */
    data object ServiceError : SignInOutcome

    /** The service issued a session, and this device could not store it. */
    data object NotSaved : SignInOutcome
}

/** Where the sign-in stands, for whichever screen shows it. */
sealed interface SignInState {
    data object Idle : SignInState

    /** GitHub's page is open in the browser; GitHub sending the user back ends this. */
    data object InBrowser : SignInState

    /** GitHub sent the user back; the service is trading the code for a session. */
    data object Exchanging : SignInState

    /** Kept until [ManagedSignIn.acknowledge], so a screen opened afterwards still shows it. */
    data class Finished(val outcome: SignInOutcome) : SignInState
}

/** A sign-in sent to GitHub and not finished yet; [state] and [verifier] are its secrets. */
data class PendingSignIn(
    val state: String,
    val verifier: String,
    val createdAtSeconds: Long,
) {
    fun isFreshAt(nowSeconds: Long): Boolean = nowSeconds - createdAtSeconds in 0 until PENDING_LIFETIME_SECONDS

    /** Left out of logs and crash reports. */
    override fun toString(): String = "PendingSignIn(createdAtSeconds=$createdAtSeconds)"
}

/**
 * What [ManagedSignIn] keeps where a process the system stopped while the user was on
 * GitHub can find it again.
 */
interface SignInStore {
    /** Records [pending], replacing any earlier sign-in. */
    suspend fun savePendingSignIn(pending: PendingSignIn): Boolean

    /** Removes and returns the sign-in in progress: one callback can use it, once. */
    suspend fun takePendingSignIn(): PendingSignIn?

    suspend fun clearPendingSignIn(): Boolean

    suspend fun saveSession(session: ManagedSession): Boolean
}

/**
 * Runs the GitHub sign-in to the managed service: the authorization-code flow with PKCE,
 * in the user's own browser.
 *
 * [start] records a random `state` and code verifier, and gives the GitHub page to open
 * with the verifier's challenge. GitHub sends the browser back to [SIGN_IN_CALLBACK_URL],
 * which Android opens in the app; [complete] then checks the `state` and has the service
 * trade the code, with the verifier, for a session. Only an app holding the verifier can
 * do that, so a code that leaks on the way back is worth nothing.
 *
 * It lives as long as the process, and the sign-in in progress is stored as well, so the
 * sign-in finishes even if no screen is open, or the process was replaced while the user
 * was on GitHub. A sign-in is good for [PENDING_LIFETIME_SECONDS], and for one callback.
 */
@Singleton
class ManagedSignIn internal constructor(
    private val api: OcrAuthApi,
    private val store: SignInStore,
    private val scope: CoroutineScope,
    private val random: SecureRandom,
    private val nowSeconds: () -> Long,
) {
    @Inject
    constructor(api: OcrAuthApi, store: SignInStore) : this(
        api,
        store,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SecureRandom(),
        { System.currentTimeMillis() / MILLIS_PER_SECOND },
    )

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /** Orders every change to the stored sign-in, so none of them undoes a later one. */
    private val lock = Mutex()

    private var expiry: Job? = null

    /**
     * Starts a sign-in, replacing any earlier one, and returns the GitHub page to open in
     * the browser; null if this device could not record it.
     */
    suspend fun start(): String? = lock.withLock {
        expiry?.cancel()
        val pending = PendingSignIn(randomToken(), randomToken(), nowSeconds())
        if (!runCatchingCancellable { store.savePendingSignIn(pending) }.getOrDefault(false)) {
            return@withLock null
        }
        _state.value = SignInState.InBrowser
        expiry = scope.launch {
            delay(PENDING_LIFETIME_SECONDS.seconds)
            lock.withLock {
                if (_state.value == SignInState.InBrowser) {
                    runCatchingCancellable { store.clearPendingSignIn() }
                    _state.value = SignInState.Finished(SignInOutcome.Expired)
                }
            }
        }
        authorizeUrl(pending)
    }

    /** Abandons the sign-in in progress: GitHub's answer to it is then ignored. */
    suspend fun cancel() {
        lock.withLock {
            expiry?.cancel()
            runCatchingCancellable { store.clearPendingSignIn() }
            _state.value = SignInState.Idle
        }
    }

    /**
     * Takes GitHub's answer, as [AuthCallbackActivity] received it, and returns whether it
     * belongs to the sign-in in progress.
     *
     * An answer without a `state` is not one: it is ignored. One with another `state` is
     * ignored too, but it ends the sign-in in progress, which can no longer be trusted to
     * be the one GitHub answers. The work runs in this object's scope, so the activity
     * finishing does not cut it short.
     */
    suspend fun complete(code: String?, state: String?, error: String?): Boolean {
        if (state == null) return false
        return scope.async {
            lock.withLock {
                val pending = runCatchingCancellable { store.takePendingSignIn() }.getOrNull()
                    ?: return@withLock false
                expiry?.cancel()
                when {
                    !MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray()) -> {
                        _state.value = SignInState.Finished(SignInOutcome.Mismatch)
                        return@withLock false
                    }
                    !pending.isFreshAt(nowSeconds()) -> _state.value = SignInState.Finished(SignInOutcome.Expired)
                    code == null -> _state.value = SignInState.Finished(
                        if (error == GITHUB_ACCESS_DENIED) SignInOutcome.Denied else SignInOutcome.ServiceError,
                    )
                    else -> {
                        _state.value = SignInState.Exchanging
                        scope.launch { _state.value = SignInState.Finished(exchange(code, pending.verifier)) }
                    }
                }
                true
            }
        }.await()
    }

    /** The screen has shown [finished]; the next one starts from [SignInState.Idle]. */
    fun acknowledge(finished: SignInState.Finished) {
        _state.compareAndSet(finished, SignInState.Idle)
    }

    /**
     * Voids [session] at the service, best effort and bounded: the device forgets it
     * either way, and a session nobody holds runs out on its own.
     */
    suspend fun signOut(session: ManagedSession) {
        withTimeoutOrNull(SIGN_OUT_TIMEOUT) {
            runCatchingCancellable {
                api.signOut(OcrConnection.Managed(session.token, session.expiresAtSeconds).authorizationHeader)
            }
        }
    }

    private suspend fun exchange(code: String, verifier: String): SignInOutcome {
        val reply = runCatchingCancellable { api.exchangeCode(TokenRequest(code, verifier)) }
            .getOrElse { return it.toFailure() }
        return when (reply.status) {
            TokenResponse.AUTHORIZED -> authorized(reply)
            TokenResponse.FORBIDDEN -> SignInOutcome.Forbidden
            TokenResponse.INVALID -> SignInOutcome.Expired
            else -> SignInOutcome.ServiceError
        }
    }

    /** Keeps a session only if it is one the app could use and could safely send. */
    private suspend fun authorized(reply: TokenResponse): SignInOutcome {
        val token = reply.token
        val expiresAt = reply.expiresAt
        if (token == null || expiresAt == null || !token.isBearerToken()) return SignInOutcome.ServiceError

        val now = nowSeconds()
        val session = ManagedSession(token, minOf(expiresAt, now + MAX_SESSION_SECONDS))
        if (!session.isUsableAt(now)) return SignInOutcome.ServiceError
        val saved = runCatchingCancellable { store.saveSession(session) }.getOrDefault(false)
        return if (saved) SignInOutcome.Authorized(session) else SignInOutcome.NotSaved
    }

    private fun authorizeUrl(pending: PendingSignIn): String =
        GITHUB_AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", GITHUB_CLIENT_ID)
            .addQueryParameter("redirect_uri", SIGN_IN_CALLBACK_URL)
            .addQueryParameter("state", pending.state)
            .addQueryParameter("code_challenge", codeChallenge(pending.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

    /** 256 random bits, base64url: a valid RFC 7636 verifier of 43 characters, and a `state`. */
    private fun randomToken(): String =
        base64Url.encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))
}

/** RFC 7636 §4.2, `S256`: the verifier's SHA-256, base64url without padding. */
internal fun codeChallenge(verifier: String): String =
    base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

private val base64Url = Base64.getUrlEncoder().withoutPadding()

private const val MILLIS_PER_SECOND = 1_000L

/** GitHub's own codes last 10 minutes; a sign-in waits no longer than one. */
internal const val PENDING_LIFETIME_SECONDS = 10L * 60

private const val RANDOM_BYTES = 32

private const val GITHUB_ACCESS_DENIED = "access_denied"

private val SIGN_OUT_TIMEOUT = 10.seconds

/** The service issues 7 days; a longer claim is clamped rather than believed. */
private const val MAX_SESSION_SECONDS = 30L * 24 * 60 * 60

private const val MAX_TOKEN_LENGTH = 4096
private const val FIRST_VISIBLE_ASCII = 0x21
private const val LAST_VISIBLE_ASCII = 0x7e

/**
 * The token goes into an `Authorization` header, so it must be visible ASCII
 * with no whitespace — nothing that could break out of the header line.
 */
private fun String.isBearerToken(): Boolean =
    length in 1..MAX_TOKEN_LENGTH && all { it.code in FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII }

private fun Throwable.toFailure(): SignInOutcome =
    if (this is IOException) SignInOutcome.Unreachable else SignInOutcome.ServiceError

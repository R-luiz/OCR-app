package com.ocrapp.auth

import com.ocrapp.ocr.ManagedSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.security.SecureRandom

/**
 * The browser sign-in's rules, run in virtual time against a scripted service and an
 * in-memory store: what it sends GitHub, which callbacks it takes, for how long and how
 * often, and which sessions it keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagedSignInTest {

    @Test
    fun `start opens GitHub's authorize page with a PKCE challenge`() = runTest {
        val store = FakeStore()
        val signIn = signIn(FakeAuthApi(), store)

        val page = checkNotNull(signIn.start()).toHttpUrl()

        val pending = checkNotNull(store.pending)
        assertEquals("https://github.com/login/oauth/authorize", page.newBuilder().query(null).build().toString())
        assertEquals("Iv23liB9UIaDsQMJrCwq", page.queryParameter("client_id"))
        assertEquals("https://ocr.robinluiz.fr/auth/callback", page.queryParameter("redirect_uri"))
        assertEquals(pending.state, page.queryParameter("state"))
        assertEquals(codeChallenge(pending.verifier), page.queryParameter("code_challenge"))
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        // RFC 7636: 43 to 128 unreserved characters. The verifier itself never leaves the device.
        assertTrue(pending.verifier, pending.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(pending.state, pending.state.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(page.toString().contains(pending.verifier))
        assertEquals(NOW, pending.createdAtSeconds)
        assertEquals(SignInState.InBrowser, signIn.state.value)
    }

    @Test
    fun `the challenge is RFC 7636's S256`() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `every sign-in gets a new state and verifier`() = runTest {
        val store = FakeStore()
        val signIn = signIn(FakeAuthApi(), store)

        signIn.start()
        val first = checkNotNull(store.pending)
        signIn.start()
        val second = checkNotNull(store.pending)

        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.state, first.verifier)
    }

    @Test
    fun `GitHub's code and the stored verifier make the session`() = runTest {
        // GitHub's answer is held back so the exchange can be seen in progress.
        val reply = CompletableDeferred<TokenResponse>()
        val api = FakeAuthApi(onExchange = { reply.await() })
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()
        val pending = checkNotNull(store.pending)

        assertTrue(signIn.complete(code = "code-1", state = pending.state, error = null))
        runCurrent()
        assertEquals(SignInState.Exchanging, signIn.state.value)
        reply.complete(authorized("session-token", NOW + SEVEN_DAYS))
        runCurrent()

        val session = ManagedSession("session-token", NOW + SEVEN_DAYS)
        val finished = SignInState.Finished(SignInOutcome.Authorized(session))
        assertEquals(finished, signIn.state.value)
        assertEquals(listOf(TokenRequest("code-1", pending.verifier)), api.exchanged)
        assertEquals(session, store.session)
        assertNull(store.pending)

        signIn.acknowledge(finished)
        assertEquals(SignInState.Idle, signIn.state.value)
    }

    // The system may stop the app while the user is on GitHub; the callback then starts
    // a new process, with a new ManagedSignIn and only the store to go on.
    @Test
    fun `a sign-in finishes in a process started after the browser opened`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        signIn(api, store).start()
        val pending = checkNotNull(store.pending)

        val restarted = signIn(api, store)
        assertTrue(restarted.complete(code = "code-1", state = pending.state, error = null))
        runCurrent()

        assertEquals(
            SignInState.Finished(SignInOutcome.Authorized(ManagedSession("session-token", NOW + SEVEN_DAYS))),
            restarted.state.value,
        )
        assertEquals(listOf(TokenRequest("code-1", pending.verifier)), api.exchanged)
    }

    @Test
    fun `a callback with another state is ignored and ends the sign-in`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()
        val pending = checkNotNull(store.pending)

        assertFalse(signIn.complete(code = "planted", state = "forged-state", error = null))
        runCurrent()

        assertEquals(SignInState.Finished(SignInOutcome.Mismatch), signIn.state.value)
        assertNull(store.pending)
        // Nor can the real answer, arriving after it, still be used.
        assertFalse(signIn.complete(code = "code-1", state = pending.state, error = null))
        runCurrent()
        assertEquals(emptyList<TokenRequest>(), api.exchanged)
    }

    @Test
    fun `a callback without a state is ignored and the sign-in goes on`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()

        assertFalse(signIn.complete(code = "code-1", state = null, error = null))

        assertEquals(SignInState.InBrowser, signIn.state.value)
        assertNotNull(store.pending)
        assertEquals(emptyList<TokenRequest>(), api.exchanged)
    }

    @Test
    fun `a sign-in is good for one callback only`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()
        val pending = checkNotNull(store.pending)

        assertTrue(signIn.complete(code = "code-1", state = pending.state, error = null))
        runCurrent()
        assertFalse(signIn.complete(code = "code-2", state = pending.state, error = null))
        runCurrent()

        assertEquals(listOf(TokenRequest("code-1", pending.verifier)), api.exchanged)
    }

    @Test
    fun `a sign-in left in the browser expires after ten minutes`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()
        val pending = checkNotNull(store.pending)

        advanceTimeBy(PENDING_LIFETIME_SECONDS * 1_000 - 1)
        runCurrent()
        assertEquals(SignInState.InBrowser, signIn.state.value)
        assertNotNull(store.pending)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(SignInState.Finished(SignInOutcome.Expired), signIn.state.value)
        assertNull(store.pending)
        assertFalse(signIn.complete(code = "code-1", state = pending.state, error = null))
        assertEquals(emptyList<TokenRequest>(), api.exchanged)
    }

    // No timer survives the process, so the stored age decides on its own.
    @Test
    fun `a stored sign-in older than ten minutes is refused without an exchange`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)

        store.pending = PendingSignIn("state-1", VERIFIER, NOW - PENDING_LIFETIME_SECONDS)
        assertTrue(signIn.complete(code = "code-1", state = "state-1", error = null))
        runCurrent()
        assertEquals(SignInState.Finished(SignInOutcome.Expired), signIn.state.value)
        assertNull(store.pending)
        assertEquals(emptyList<TokenRequest>(), api.exchanged)

        store.pending = PendingSignIn("state-2", VERIFIER, NOW - PENDING_LIFETIME_SECONDS + 1)
        assertTrue(signIn.complete(code = "code-2", state = "state-2", error = null))
        runCurrent()
        assertEquals(listOf(TokenRequest("code-2", VERIFIER)), api.exchanged)
    }

    @Test
    fun `cancelling forgets the sign-in`() = runTest {
        val api = FakeAuthApi()
        val store = FakeStore()
        val signIn = signIn(api, store)
        signIn.start()
        val pending = checkNotNull(store.pending)

        signIn.cancel()

        assertEquals(SignInState.Idle, signIn.state.value)
        assertNull(store.pending)
        assertFalse(signIn.complete(code = "code-1", state = pending.state, error = null))
        advanceTimeBy(PENDING_LIFETIME_SECONDS * 1_000 + 1)
        runCurrent()
        assertEquals(SignInState.Idle, signIn.state.value)
        assertEquals(emptyList<TokenRequest>(), api.exchanged)
    }

    @Test
    fun `a sign-in that cannot be recorded opens nothing`() = runTest {
        val store = FakeStore(savesPending = false)
        val signIn = signIn(FakeAuthApi(), store)

        assertNull(signIn.start())
        assertEquals(SignInState.Idle, signIn.state.value)
    }

    @Test
    fun `GitHub's refusals and the service's answers each have their outcome`() = runTest {
        assertEquals(SignInOutcome.Denied, outcomeOf(FakeAuthApi(), code = null, error = "access_denied"))
        assertEquals(SignInOutcome.ServiceError, outcomeOf(FakeAuthApi(), code = null, error = "server_error"))
        assertEquals(SignInOutcome.Forbidden, outcomeOf(FakeAuthApi { TokenResponse(TokenResponse.FORBIDDEN) }))
        assertEquals(SignInOutcome.Expired, outcomeOf(FakeAuthApi { TokenResponse(TokenResponse.INVALID) }))
        assertEquals(SignInOutcome.ServiceError, outcomeOf(FakeAuthApi { TokenResponse("mystery") }))
        assertEquals(SignInOutcome.Unreachable, outcomeOf(FakeAuthApi { throw IOException("offline") }))
        assertEquals(SignInOutcome.ServiceError, outcomeOf(FakeAuthApi { throw httpError(429) }))
        assertEquals(SignInOutcome.NotSaved, outcomeOf(FakeAuthApi(), FakeStore(savesSession = false)))
    }

    // The token goes into a header and into storage, so only one the app could send
    // safely, and use for a whole scan, is taken.
    @Test
    fun `refuses an approval that carries no usable session`() = runTest {
        val unusable = listOf(
            "no token" to TokenResponse(TokenResponse.AUTHORIZED, expiresAt = NOW + SEVEN_DAYS),
            "no expiry" to TokenResponse(TokenResponse.AUTHORIZED, token = "session-token"),
            "empty token" to authorized("", NOW + SEVEN_DAYS),
            "space in token" to authorized("two words", NOW + SEVEN_DAYS),
            "header injection" to authorized("abc\r\nX-Evil: 1", NOW + SEVEN_DAYS),
            "non-ascii token" to authorized("jeton-\u00e9", NOW + SEVEN_DAYS),
            "oversized token" to authorized("a".repeat(4_097), NOW + SEVEN_DAYS),
            "already expired" to authorized("session-token", NOW - 1),
            "inside the safety margin" to authorized("session-token", NOW + ManagedSession.MIN_REMAINING_SECONDS),
        )
        for ((name, reply) in unusable) {
            val store = FakeStore()

            assertEquals(name, SignInOutcome.ServiceError, outcomeOf(FakeAuthApi { reply }, store))
            assertNull(name, store.session)
        }
    }

    @Test
    fun `accepts the largest token and a session just past the margin`() = runTest {
        val token = "a".repeat(4_096)
        val expiresAt = NOW + ManagedSession.MIN_REMAINING_SECONDS + 1

        assertEquals(
            SignInOutcome.Authorized(ManagedSession(token, expiresAt)),
            outcomeOf(FakeAuthApi { authorized(token, expiresAt) }),
        )
    }

    @Test
    fun `a session is never kept longer than thirty days`() = runTest {
        val outcome = outcomeOf(FakeAuthApi { authorized("session-token", NOW + 365L * 24 * 60 * 60) })

        assertEquals(NOW + 30L * 24 * 60 * 60, (outcome as SignInOutcome.Authorized).session.expiresAtSeconds)
    }

    @Test
    fun `signing out names the session, and gives up rather than wait`() = runTest {
        val session = ManagedSession("session-token", NOW + SEVEN_DAYS)
        val answering = FakeAuthApi()
        val offline = FakeAuthApi(onSignOut = { throw IOException("offline") })
        val silent = FakeAuthApi(onSignOut = { awaitCancellation() })

        signIn(answering, FakeStore()).signOut(session)
        signIn(offline, FakeStore()).signOut(session)
        val startedAt = currentTime
        signIn(silent, FakeStore()).signOut(session)

        assertEquals(listOf("Bearer session-token"), answering.signedOut)
        assertEquals(listOf("Bearer session-token"), offline.signedOut)
        assertEquals(10_000L, currentTime - startedAt)
    }

    @Test
    fun `nothing a sign-in holds prints as a secret`() {
        val pending = PendingSignIn("state-secret", VERIFIER, NOW)
        val approval = authorized("session-token", NOW + SEVEN_DAYS)

        assertTrue(pending.toString(), "state-secret" !in pending.toString() && VERIFIER !in pending.toString())
        assertTrue(approval.toString(), "session-token" !in approval.toString())
        assertTrue("session-token" !in ManagedSession("session-token", 1).toString())
    }

    /** The clock follows virtual time from [NOW]. */
    private fun TestScope.signIn(api: OcrAuthApi, store: SignInStore): ManagedSignIn =
        ManagedSignIn(api, store, backgroundScope, SecureRandom()) { NOW + currentTime / 1_000 }

    /** One whole sign-in, from start to the answer GitHub sends back. */
    private suspend fun TestScope.outcomeOf(
        api: OcrAuthApi,
        store: FakeStore = FakeStore(),
        code: String? = "code-1",
        error: String? = null,
    ): SignInOutcome {
        val signIn = signIn(api, store)
        signIn.start()
        signIn.complete(code, checkNotNull(store.pending).state, error)
        runCurrent()
        return (signIn.state.value as SignInState.Finished).outcome
    }

    private class FakeStore(
        private val savesPending: Boolean = true,
        private val savesSession: Boolean = true,
    ) : SignInStore {
        var pending: PendingSignIn? = null
        var session: ManagedSession? = null

        override suspend fun savePendingSignIn(pending: PendingSignIn): Boolean {
            if (savesPending) this.pending = pending
            return savesPending
        }

        override suspend fun takePendingSignIn(): PendingSignIn? = pending.also { pending = null }

        override suspend fun clearPendingSignIn(): Boolean {
            pending = null
            return true
        }

        override suspend fun saveSession(session: ManagedSession): Boolean {
            if (savesSession) this.session = session
            return savesSession
        }
    }

    private class FakeAuthApi(
        private val onSignOut: suspend () -> Unit = {},
        private val onExchange: suspend () -> TokenResponse = { authorized("session-token", NOW + SEVEN_DAYS) },
    ) : OcrAuthApi {
        val exchanged = mutableListOf<TokenRequest>()
        val signedOut = mutableListOf<String>()

        override suspend fun exchangeCode(request: TokenRequest): TokenResponse {
            exchanged += request
            return onExchange()
        }

        override suspend fun signOut(authorization: String) {
            signedOut += authorization
            onSignOut()
        }
    }

    private companion object {
        const val NOW = 1_800_000_000L
        const val SEVEN_DAYS = 7L * 24 * 60 * 60
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        fun authorized(token: String, expiresAt: Long) =
            TokenResponse(TokenResponse.AUTHORIZED, token = token, expiresAt = expiresAt)

        fun httpError(code: Int) =
            HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))
    }
}

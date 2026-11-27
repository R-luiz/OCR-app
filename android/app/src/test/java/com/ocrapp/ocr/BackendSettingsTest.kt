package com.ocrapp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which connection a scan gets from what is stored. The rule that matters most: the
 * selected backend's, and never the other one's, whatever state the selected one is in.
 */
class BackendSettingsTest {

    private val now = 1_800_000_000L
    private val own = RunPodCredentials("endpoint-1", "key-1")
    private val none = RunPodCredentials("", "")
    private val week = ManagedSession("session-1", now + 7L * 24 * 60 * 60)

    private fun settings(
        mode: ConnectionMode,
        direct: RunPodCredentials = none,
        session: ManagedSession? = null,
    ) = BackendSettings(mode, direct, session, readAtSeconds = now)

    @Test
    fun `managed with a live session uses it`() {
        val lookup = settings(ConnectionMode.MANAGED, session = week).selected()

        assertEquals(ConnectionLookup.Available(OcrConnection.Managed("session-1", week.expiresAtSeconds)), lookup)
    }

    @Test
    fun `managed with no session is not configured`() {
        assertEquals(ConnectionLookup.NotConfigured, settings(ConnectionMode.MANAGED).selected())
    }

    // The user chose the managed service. A key typed into the advanced section for some
    // other day is not a reason to send their pages somewhere else.
    @Test
    fun `managed never falls back to the user's own endpoint`() {
        val noSession = settings(ConnectionMode.MANAGED, direct = own)
        val lapsed = settings(ConnectionMode.MANAGED, direct = own, session = ManagedSession("old", now - 1))

        assertEquals(ConnectionLookup.NotConfigured, noSession.selected())
        assertEquals(ConnectionLookup.SessionExpired, lapsed.selected())
    }

    @Test
    fun `direct with a complete endpoint uses it, whatever sessions exist`() {
        val lookup = settings(ConnectionMode.DIRECT, direct = own, session = week).selected()

        assertEquals(ConnectionLookup.Available(OcrConnection.Direct(own)), lookup)
    }

    @Test
    fun `direct with an incomplete endpoint is not configured, even beside a live session`() {
        for (incomplete in listOf(none, RunPodCredentials("endpoint-1", ""), RunPodCredentials(" ", "key-1"))) {
            assertEquals(
                incomplete.toString(),
                ConnectionLookup.NotConfigured,
                settings(ConnectionMode.DIRECT, direct = incomplete, session = week).selected(),
            )
        }
    }

    @Test
    fun `the managed state is reported whichever backend is selected`() {
        val direct = settings(ConnectionMode.DIRECT, direct = own, session = week)
        val lapsed = settings(ConnectionMode.DIRECT, direct = own, session = ManagedSession("old", now - 1))

        assertEquals(ConnectionLookup.Available(OcrConnection.Managed("session-1", week.expiresAtSeconds)), direct.managed())
        assertEquals(ConnectionLookup.SessionExpired, lapsed.managed())
        assertEquals(ConnectionLookup.NotConfigured, settings(ConnectionMode.DIRECT, direct = own).managed())
    }

    // A job runs for up to a quarter of an hour, after an upload that can itself take
    // minutes; a session that ends inside that would leave a GPU job running with nobody
    // able to cancel or collect it.
    @Test
    fun `a session is live only if it outlasts a whole job`() {
        val margin = ManagedSession.MIN_REMAINING_SECONDS

        assertFalse(ManagedSession("s", now).isUsableAt(now))
        assertFalse(ManagedSession("s", now + margin).isUsableAt(now))
        assertTrue(ManagedSession("s", now + margin + 1).isUsableAt(now))
        assertEquals(25L * 60, margin)
    }

    // The engine checks the connection it was given before each job of a scan, by the same rule.
    @Test
    fun `a managed connection applies the session's margin`() {
        val margin = ManagedSession.MIN_REMAINING_SECONDS

        assertFalse(OcrConnection.Managed("s", now + margin).isUsableAt(now))
        assertTrue(OcrConnection.Managed("s", now + margin + 1).isUsableAt(now))
    }

    @Test
    fun `a session inside the margin counts as expired`() {
        val ending = ManagedSession("s", now + 5 * 60)

        assertEquals(ConnectionLookup.SessionExpired, settings(ConnectionMode.MANAGED, session = ending).selected())
    }

    @Test
    fun `credentials are sent as bearer tokens`() {
        assertEquals("Bearer key-1", OcrConnection.Direct(own).authorizationHeader)
        assertEquals("Bearer session-1", OcrConnection.Managed("session-1", week.expiresAtSeconds).authorizationHeader)
    }

    // None of these may reach a log or a crash report by accident, in a message or a
    // stack trace.
    @Test
    fun `no secret appears when a credential is printed`() {
        val printed = listOf(
            OcrConnection.Managed("session-secret", now).toString(),
            OcrConnection.Direct(RunPodCredentials("endpoint-1", "key-secret")).toString(),
            RunPodCredentials("endpoint-1", "key-secret").toString(),
            ManagedSession("session-secret", now).toString(),
            ConnectionLookup.Available(OcrConnection.Managed("session-secret", now)).toString(),
            settings(ConnectionMode.MANAGED, direct = RunPodCredentials("e", "key-secret"), session = ManagedSession("session-secret", now)).toString(),
        )

        for (text in printed) {
            assertFalse(text, "session-secret" in text || "key-secret" in text)
        }
    }
}

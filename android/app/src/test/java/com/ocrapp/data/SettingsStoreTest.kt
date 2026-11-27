package com.ocrapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ocrapp.BuildConfig
import com.ocrapp.auth.PendingSignIn
import com.ocrapp.ocr.ConnectionLookup
import com.ocrapp.ocr.ConnectionMode
import com.ocrapp.ocr.ManagedSession
import com.ocrapp.ocr.OcrConnection
import com.ocrapp.ocr.RunPodCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [SettingsStore] does with the two backends' settings, over plain in-memory
 * preferences: the encryption underneath is the platform's and is not under test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SettingsStore

    private val own = RunPodCredentials("endpoint-1", "key-1")
    private val week = ManagedSession("session-1", nowSeconds() + 7L * 24 * 60 * 60)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("settings-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SettingsStore { prefs }
    }

    // Nobody has chosen: a fresh install starts on the managed service, and is never
    // asked for an endpoint ID or a key. (Debug builds may carry RunPod defaults.)
    @Test
    fun `a fresh install starts on the managed service`() = runBlocking {
        val debugDefaults = RunPodCredentials(BuildConfig.RUNPOD_ENDPOINT_ID, BuildConfig.RUNPOD_API_KEY)
        val expected = if (debugDefaults.isComplete) ConnectionMode.DIRECT else ConnectionMode.MANAGED

        val settings = store.load()

        assertEquals(expected, settings.mode)
        assertNull(settings.session)
    }

    // What an install that predates the managed service has stored: its own endpoint and
    // key and no mode. It keeps working as it did.
    @Test
    fun `an install with its own endpoint keeps using it`() = runBlocking {
        prefs.edit().putString("runpod_endpoint_id", "endpoint-1").putString("runpod_api_key", "key-1").commit()

        val settings = store.load()

        assertEquals(ConnectionMode.DIRECT, settings.mode)
        assertEquals(ConnectionLookup.Available(OcrConnection.Direct(own)), store.lookup())
    }

    @Test
    fun `a stored mode nobody recognises is treated as not chosen`() = runBlocking {
        prefs.edit().putString("connection_mode", "SOMETHING_ELSE").commit()
        val debugDefaults = RunPodCredentials(BuildConfig.RUNPOD_ENDPOINT_ID, BuildConfig.RUNPOD_API_KEY)

        val mode = store.load().mode

        assertEquals(if (debugDefaults.isComplete) ConnectionMode.DIRECT else ConnectionMode.MANAGED, mode)
    }

    @Test
    fun `a saved session is read back and used`() = runBlocking {
        assertTrue(store.saveSession(week))

        val settings = store.load()

        assertEquals(week, settings.session)
        assertEquals(ConnectionMode.MANAGED, settings.mode)
        assertEquals(ConnectionLookup.Available(OcrConnection.Managed("session-1", week.expiresAtSeconds)), store.lookup())
    }

    // Signing in is the user choosing the managed service; the selection must not lag
    // behind the token by a separate write.
    @Test
    fun `signing in selects the managed service and keeps the user's own endpoint`() = runBlocking {
        store.saveDirect(own, ConnectionMode.DIRECT)

        store.saveSession(week)

        val settings = store.load()
        assertEquals(ConnectionMode.MANAGED, settings.mode)
        assertEquals(own, settings.direct)
    }

    @Test
    fun `the selected backend is the only one a scan can get`() = runBlocking {
        store.saveSession(week)
        store.saveDirect(own, ConnectionMode.DIRECT)

        assertEquals(ConnectionLookup.Available(OcrConnection.Direct(own)), store.lookup())
        // The managed side is still there, for the Settings screen to show.
        assertEquals(
            ConnectionLookup.Available(OcrConnection.Managed("session-1", week.expiresAtSeconds)),
            store.managedLookup(),
        )

        store.saveDirect(own, ConnectionMode.MANAGED)

        assertEquals(ConnectionLookup.Available(OcrConnection.Managed("session-1", week.expiresAtSeconds)), store.lookup())
    }

    @Test
    fun `a session about to lapse asks for a new sign-in instead of using the other backend`() = runBlocking {
        store.saveDirect(own, ConnectionMode.MANAGED)
        store.saveSession(ManagedSession("session-1", nowSeconds() + 60))

        assertEquals(ConnectionLookup.SessionExpired, store.lookup())
    }

    @Test
    fun `signing out removes the session and nothing else`() = runBlocking {
        store.saveDirect(own, ConnectionMode.DIRECT)
        store.saveSession(week)

        assertTrue(store.clearSession())

        val settings = store.load()
        assertNull(settings.session)
        assertEquals(ConnectionMode.MANAGED, settings.mode)
        assertEquals(own, settings.direct)
        assertEquals(ConnectionLookup.NotConfigured, store.lookup())
        assertEquals(null, prefs.getString("managed_session_token", null))
    }

    @Test
    fun `a refused session is forgotten`() = runBlocking {
        store.saveSession(week)

        store.sessionRejected(OcrConnection.Managed("session-1", week.expiresAtSeconds))

        assertNull(store.load().session)
    }

    // A scan that started on the old session may only learn it was refused after the user
    // has signed in again; that report must not sign them out of the new session.
    @Test
    fun `a refusal of an old session leaves a newer sign-in alone`() = runBlocking {
        store.saveSession(week)
        val newer = ManagedSession("session-2", week.expiresAtSeconds + 60)
        store.saveSession(newer)

        store.sessionRejected(OcrConnection.Managed("session-1", week.expiresAtSeconds))

        assertEquals(newer, store.load().session)
    }

    @Test
    fun `a session with a missing expiry is not a session`() = runBlocking {
        prefs.edit().putString("managed_session_token", "session-1").commit()

        assertNull(store.load().session)
    }

    @Test
    fun `saving the user's own endpoint does not touch the session`() = runBlocking {
        store.saveSession(week)

        store.saveDirect(own, ConnectionMode.MANAGED)

        assertEquals(week, store.load().session)
    }

    // Kept in the same file as the session: a process the system stopped while the user
    // was on GitHub finds the sign-in again, and a second callback finds nothing.
    @Test
    fun `a pending sign-in outlives the process, and is read once`() = runBlocking {
        val pending = PendingSignIn("state-1", "verifier-1", 1_800_000_000L)
        assertTrue(store.savePendingSignIn(pending))

        val restarted = SettingsStore { prefs }

        assertEquals(pending, restarted.takePendingSignIn())
        assertNull(restarted.takePendingSignIn())
        assertNull(store.takePendingSignIn())
    }

    @Test
    fun `a new sign-in replaces the pending one, and clearing removes it`() = runBlocking {
        store.savePendingSignIn(PendingSignIn("state-1", "verifier-1", 1L))
        store.savePendingSignIn(PendingSignIn("state-2", "verifier-2", 2L))

        assertEquals(PendingSignIn("state-2", "verifier-2", 2L), store.takePendingSignIn())

        store.savePendingSignIn(PendingSignIn("state-3", "verifier-3", 3L))
        assertTrue(store.clearPendingSignIn())
        assertNull(store.takePendingSignIn())
    }

    @Test
    fun `a pending sign-in leaves the session alone`() = runBlocking {
        store.saveSession(week)
        store.savePendingSignIn(PendingSignIn("state-1", "verifier-1", 1L))

        store.takePendingSignIn()

        assertEquals(week, store.load().session)
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1_000
}

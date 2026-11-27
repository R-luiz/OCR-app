package com.ocrapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ocrapp.BuildConfig
import com.ocrapp.auth.PendingSignIn
import com.ocrapp.auth.SignInStore
import com.ocrapp.ocr.BackendSettings
import com.ocrapp.ocr.ConnectionLookup
import com.ocrapp.ocr.ConnectionMode
import com.ocrapp.ocr.ManagedSession
import com.ocrapp.ocr.OcrConnection
import com.ocrapp.ocr.OcrConnectionProvider
import com.ocrapp.ocr.RunPodCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists how Document parsing reaches its backend: the managed service's session, the
 * user's own RunPod endpoint, and which of the two is selected.
 *
 * Debug builds can supply RunPod defaults from `local.properties`; release builds start
 * empty. A runtime edit takes precedence. Both the RunPod API key and the managed
 * session token can spend money, so they live in [EncryptedSharedPreferences] rather
 * than plain prefs. So do the `state` and code verifier of a sign-in in progress: a
 * process the system stopped while the user was on GitHub can then still finish it.
 * They are removed the first time they are read.
 *
 * Opening that file reaches into the hardware keystore, which takes long enough to drop
 * frames, so every access here is a suspend function that leaves the main thread. A lock
 * keeps each read a consistent snapshot of what a write changed together.
 */
@Singleton
class SettingsStore internal constructor(
    openPrefs: () -> SharedPreferences,
) : OcrConnectionProvider, SignInStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ openEncrypted(context) })

    private val prefs: SharedPreferences by lazy(openPrefs)

    private val lock = Mutex()

    /** Everything stored, read together. */
    suspend fun load(): BackendSettings = withContext(Dispatchers.IO) {
        lock.withLock { read() }
    }

    /** The connection a scan should use, from one snapshot of the settings. */
    override suspend fun lookup(): ConnectionLookup = load().selected()

    /** The managed service's state, whichever backend is selected. */
    suspend fun managedLookup(): ConnectionLookup = load().managed()

    /** Stores the user's own endpoint and selects [mode], together. */
    suspend fun saveDirect(credentials: RunPodCredentials, mode: ConnectionMode): Boolean =
        write {
            putString(KEY_ENDPOINT_ID, credentials.endpointId)
            putString(KEY_API_KEY, credentials.apiKey)
            putString(KEY_MODE, mode.name)
        }

    /** Stores a new managed session and selects the managed service, together. */
    override suspend fun saveSession(session: ManagedSession): Boolean =
        write {
            putString(KEY_SESSION_TOKEN, session.token)
            putLong(KEY_SESSION_EXPIRES_AT, session.expiresAtSeconds)
            putString(KEY_MODE, ConnectionMode.MANAGED.name)
        }

    /** Signs out: removes the managed session from this device. The selected backend stays. */
    suspend fun clearSession(): Boolean =
        write {
            remove(KEY_SESSION_TOKEN)
            remove(KEY_SESSION_EXPIRES_AT)
        }

    override suspend fun savePendingSignIn(pending: PendingSignIn): Boolean =
        write {
            putString(KEY_SIGNIN_STATE, pending.state)
            putString(KEY_SIGNIN_VERIFIER, pending.verifier)
            putLong(KEY_SIGNIN_CREATED_AT, pending.createdAtSeconds)
        }

    override suspend fun takePendingSignIn(): PendingSignIn? = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = prefs.getString(KEY_SIGNIN_STATE, null)
            val verifier = prefs.getString(KEY_SIGNIN_VERIFIER, null)
            val createdAt = prefs.getLong(KEY_SIGNIN_CREATED_AT, 0L)
            // Handed out only once it is gone from the file, so no second callback can use it.
            val removed = prefs.edit().removePendingSignIn().commit()
            if (removed && !state.isNullOrEmpty() && !verifier.isNullOrEmpty()) {
                PendingSignIn(state, verifier, createdAt)
            } else {
                null
            }
        }
    }

    override suspend fun clearPendingSignIn(): Boolean = write { removePendingSignIn() }

    override suspend fun sessionRejected(connection: OcrConnection.Managed) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                // Only the session that was refused. A sign-in that finished while the
                // scan was still running has replaced it, and must survive.
                if (prefs.getString(KEY_SESSION_TOKEN, null) == connection.sessionToken) {
                    prefs.edit()
                        .remove(KEY_SESSION_TOKEN)
                        .remove(KEY_SESSION_EXPIRES_AT)
                        .commit()
                }
            }
        }
    }

    private fun read(): BackendSettings {
        val direct = RunPodCredentials(
            endpointId = prefs.getString(KEY_ENDPOINT_ID, null) ?: BuildConfig.RUNPOD_ENDPOINT_ID,
            apiKey = prefs.getString(KEY_API_KEY, null) ?: BuildConfig.RUNPOD_API_KEY,
        )
        val token = prefs.getString(KEY_SESSION_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRES_AT, 0L)
        val session = if (!token.isNullOrBlank() && expiresAt > 0L) ManagedSession(token, expiresAt) else null

        // Nobody chose yet: an install that already has its own endpoint keeps using it,
        // and a fresh one starts on the managed service.
        val mode = prefs.getString(KEY_MODE, null)
            ?.let { stored -> ConnectionMode.entries.firstOrNull { it.name == stored } }
            ?: if (direct.isComplete) ConnectionMode.DIRECT else ConnectionMode.MANAGED

        return BackendSettings(mode, direct, session, readAtSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND)
    }

    private fun SharedPreferences.Editor.removePendingSignIn(): SharedPreferences.Editor =
        remove(KEY_SIGNIN_STATE)
            .remove(KEY_SIGNIN_VERIFIER)
            .remove(KEY_SIGNIN_CREATED_AT)

    private suspend fun write(edit: SharedPreferences.Editor.() -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock { prefs.edit().apply(edit).commit() }
        }

    private companion object {
        const val FILE_NAME = "ocr_settings"
        const val KEY_ENDPOINT_ID = "runpod_endpoint_id"
        const val KEY_API_KEY = "runpod_api_key"
        const val KEY_MODE = "connection_mode"
        const val KEY_SESSION_TOKEN = "managed_session_token"
        const val KEY_SESSION_EXPIRES_AT = "managed_session_expires_at"
        const val KEY_SIGNIN_STATE = "managed_signin_state"
        const val KEY_SIGNIN_VERIFIER = "managed_signin_verifier"
        const val KEY_SIGNIN_CREATED_AT = "managed_signin_created_at"
        const val MILLIS_PER_SECOND = 1_000L

        fun openEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

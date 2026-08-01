package com.ocrapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ocrapp.BuildConfig
import com.ocrapp.ocr.RunPodCredentials
import com.ocrapp.ocr.RunPodCredentialsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the RunPod endpoint coordinates.
 *
 * Values default to whatever was baked in from `local.properties` at build time, and a
 * runtime edit in Settings overrides that. The API key is a bearer credential that can
 * spend money, so it lives in [EncryptedSharedPreferences] rather than plain prefs.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : RunPodCredentialsProvider {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val endpointId: String
        get() = prefs.getString(KEY_ENDPOINT_ID, null) ?: BuildConfig.RUNPOD_ENDPOINT_ID

    val apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: BuildConfig.RUNPOD_API_KEY

    /** True when Document parsing can be attempted at all. */
    val isBackendConfigured: Boolean
        get() = endpointId.isNotBlank() && apiKey.isNotBlank()

    override suspend fun credentials(): RunPodCredentials? =
        RunPodCredentials(endpointId, apiKey).takeIf { it.isComplete }

    fun save(endpointId: String, apiKey: String) {
        prefs.edit()
            .putString(KEY_ENDPOINT_ID, endpointId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    /** Emits on every change so the UI can react to the backend becoming configured. */
    fun observe(): Flow<RunPodCredentials> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(RunPodCredentials(endpointId, apiKey))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(RunPodCredentials(endpointId, apiKey)) }

    private companion object {
        const val FILE_NAME = "ocr_settings"
        const val KEY_ENDPOINT_ID = "runpod_endpoint_id"
        const val KEY_API_KEY = "runpod_api_key"
    }
}

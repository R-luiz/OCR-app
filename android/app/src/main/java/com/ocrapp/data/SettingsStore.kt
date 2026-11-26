package com.ocrapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ocrapp.BuildConfig
import com.ocrapp.ocr.RunPodCredentials
import com.ocrapp.ocr.RunPodCredentialsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the RunPod endpoint coordinates.
 *
 * Debug builds can supply defaults from `local.properties`; release builds start empty.
 * A runtime edit takes precedence. The API key can spend money, so it lives in
 * [EncryptedSharedPreferences] rather than plain prefs.
 *
 * Opening that file reaches into the hardware keystore, which takes long enough to drop
 * frames, so every access here is a suspend function that leaves the main thread.
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

    /** The stored credentials, complete or not. */
    suspend fun load(): RunPodCredentials = withContext(Dispatchers.IO) {
        RunPodCredentials(
            endpointId = prefs.getString(KEY_ENDPOINT_ID, null) ?: BuildConfig.RUNPOD_ENDPOINT_ID,
            apiKey = prefs.getString(KEY_API_KEY, null) ?: BuildConfig.RUNPOD_API_KEY,
        )
    }

    override suspend fun credentials(): RunPodCredentials? = load().takeIf { it.isComplete }

    suspend fun save(credentials: RunPodCredentials): Boolean = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ENDPOINT_ID, credentials.endpointId)
            .putString(KEY_API_KEY, credentials.apiKey)
            .commit()
    }

    private companion object {
        const val FILE_NAME = "ocr_settings"
        const val KEY_ENDPOINT_ID = "runpod_endpoint_id"
        const val KEY_API_KEY = "runpod_api_key"
    }
}

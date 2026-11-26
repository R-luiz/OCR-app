package com.ocrapp.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.BackendUnavailableException
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.RemoteOcrEngine
import com.ocrapp.ocr.RunPodCredentials
import com.ocrapp.ocr.RunPodWorkers
import com.ocrapp.ocr.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** The outcome of the last connection test, if there was one. */
sealed interface ConnectionState {
    data object Unknown : ConnectionState

    data object Testing : ConnectionState

    data class Reachable(val workers: RunPodWorkers?) : ConnectionState

    /** RunPod answered, and said the key is not valid for this endpoint. */
    data object Unauthorized : ConnectionState

    /** RunPod answered, and has no endpoint with this ID. */
    data object EndpointNotFound : ConnectionState

    /** Nothing answered at all. */
    data object NoNetwork : ConnectionState

    data class Failed(val detail: String?) : ConnectionState
}

data class SettingsUiState(
    /** False until the stored values have been read; the fields are locked until then. */
    val isLoaded: Boolean = false,
    val endpointId: String = "",
    val apiKey: String = "",
    val connection: ConnectionState = ConnectionState.Unknown,
    val isSaving: Boolean = false,
    @StringRes val messageRes: Int? = null,
) {
    val credentials: RunPodCredentials
        get() = RunPodCredentials(endpointId.trim(), apiKey.trim())

    val canEdit: Boolean
        get() = isLoaded && !isSaving && connection != ConnectionState.Testing

    val canTest: Boolean
        get() = canEdit && endpointId.isNotBlank() && apiKey.isNotBlank()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val remoteEngine: RemoteOcrEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = runCatchingCancellable { settings.load() }.getOrElse {
                _state.update { it.copy(messageRes = R.string.settings_read_failed) }
                return@launch
            }
            _state.update {
                it.copy(isLoaded = true, endpointId = stored.endpointId, apiKey = stored.apiKey)
            }
        }
    }

    fun onEndpointChange(value: String) {
        _state.update {
            if (it.canEdit) it.copy(endpointId = value, connection = ConnectionState.Unknown) else it
        }
    }

    fun onApiKeyChange(value: String) {
        _state.update {
            if (it.canEdit) it.copy(apiKey = value, connection = ConnectionState.Unknown) else it
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canEdit) return
        val credentials = current.credentials
        _state.update { it.copy(isSaving = true, messageRes = null) }
        viewModelScope.launch {
            val saved = runCatchingCancellable { settings.save(credentials) }.getOrDefault(false)
            _state.update {
                it.copy(
                    isSaving = false,
                    messageRes = if (saved) R.string.settings_saved else R.string.settings_save_failed,
                )
            }
        }
    }

    fun onMessageShown(@StringRes messageRes: Int) {
        _state.update { if (it.messageRes == messageRes) it.copy(messageRes = null) else it }
    }

    fun testConnection() {
        val current = _state.value
        if (!current.canTest) return
        val credentials = current.credentials

        _state.update { it.copy(connection = ConnectionState.Testing) }
        viewModelScope.launch {
            val connection = remoteEngine.testConnection(credentials).fold(
                onSuccess = { health -> ConnectionState.Reachable(health.workers) },
                onFailure = { error -> error.toConnectionState() },
            )
            _state.update { it.copy(connection = connection) }
        }
    }
}

private fun Throwable.toConnectionState(): ConnectionState {
    val httpCode = (cause as? HttpException)?.code()
    return when {
        httpCode == HTTP_UNAUTHORIZED || httpCode == HTTP_FORBIDDEN -> ConnectionState.Unauthorized
        httpCode == HTTP_NOT_FOUND -> ConnectionState.EndpointNotFound
        this is BackendUnavailableException && reason == FallbackReason.UNREACHABLE ->
            ConnectionState.NoNetwork
        else -> ConnectionState.Failed(message)
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

package com.ocrapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.RemoteOcrEngine
import com.ocrapp.ocr.RunPodCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionState {
    UNKNOWN,
    TESTING,
    REACHABLE,
    UNREACHABLE,
}

data class SettingsUiState(
    val endpointId: String = "",
    val apiKey: String = "",
    val connection: ConnectionState = ConnectionState.UNKNOWN,
    val connectionDetail: String? = null,
    val isSaved: Boolean = false,
) {
    val canTest: Boolean
        get() = endpointId.isNotBlank() && apiKey.isNotBlank() &&
            connection != ConnectionState.TESTING
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val remoteEngine: RemoteOcrEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(endpointId = settings.endpointId, apiKey = settings.apiKey),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onEndpointChange(value: String) {
        _state.update {
            it.copy(endpointId = value, connection = ConnectionState.UNKNOWN, isSaved = false)
        }
    }

    fun onApiKeyChange(value: String) {
        _state.update {
            it.copy(apiKey = value, connection = ConnectionState.UNKNOWN, isSaved = false)
        }
    }

    fun save() {
        val current = _state.value
        settings.save(current.endpointId, current.apiKey)
        _state.update { it.copy(isSaved = true) }
    }

    fun onSavedMessageShown() {
        _state.update { it.copy(isSaved = false) }
    }

    fun testConnection() {
        val current = _state.value
        if (!current.canTest) return

        // Save first: a test that passes against unsaved values would be misleading.
        settings.save(current.endpointId, current.apiKey)
        _state.update { it.copy(connection = ConnectionState.TESTING, connectionDetail = null) }

        viewModelScope.launch {
            val credentials = RunPodCredentials(current.endpointId.trim(), current.apiKey.trim())
            val result = remoteEngine.testConnection(credentials)
            _state.update {
                result.fold(
                    onSuccess = { health ->
                        val workers = health.workers
                        it.copy(
                            connection = ConnectionState.REACHABLE,
                            connectionDetail = workers?.let { w ->
                                "${w.ready} ready, ${w.idle} idle, ${w.running} running"
                            },
                            isSaved = true,
                        )
                    },
                    onFailure = { error ->
                        it.copy(
                            connection = ConnectionState.UNREACHABLE,
                            connectionDetail = error.message,
                        )
                    },
                )
            }
        }
    }
}

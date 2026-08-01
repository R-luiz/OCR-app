package com.ocrapp.ui.qrscan

import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import com.ocrapp.qr.QrCodeScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class QrScanUiState(
    val result: String? = null,
)

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val scanner: QrCodeScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(QrScanUiState())
    val state: StateFlow<QrScanUiState> = _state.asStateFlow()

    /**
     * Frames keep arriving from the camera until analysis is unbound, so this ignores
     * every detection after the first — the result sheet stays showing whatever was
     * found until the user dismisses it with [reset].
     */
    fun createAnalyzer(): ImageAnalysis.Analyzer = scanner.analyzer { value ->
        _state.update { if (it.result == null) it.copy(result = value) else it }
    }

    fun reset() {
        _state.update { it.copy(result = null) }
    }
}

package com.ocrapp.ui.qrgenerate

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.QrImageStore
import com.ocrapp.qr.QrCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether generation produced an image, is waiting for input, or failed — computed in
 * one step per input change so "empty" and "too long" can never be guessed incorrectly
 * from two independently-updating flows racing during the debounce window.
 */
sealed interface QrGenerateResult {
    data object Empty : QrGenerateResult
    data class Success(val bitmap: Bitmap) : QrGenerateResult
    data object TooLong : QrGenerateResult
}

@HiltViewModel
class QrGenerateViewModel @Inject constructor(
    private val generator: QrCodeGenerator,
    private val imageStore: QrImageStore,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val result: StateFlow<QrGenerateResult> = _text
        .debounce { if (it.isBlank()) 0L else GENERATE_DEBOUNCE_MS }
        .map { input ->
            if (input.isBlank()) {
                QrGenerateResult.Empty
            } else {
                generator.generate(input)?.let(QrGenerateResult::Success) ?: QrGenerateResult.TooLong
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QrGenerateResult.Empty)

    private val _messageRes = MutableStateFlow<Int?>(null)
    val messageRes: StateFlow<Int?> = _messageRes.asStateFlow()

    fun onTextChange(value: String) {
        _text.value = value
    }

    fun save() {
        val bitmap = (result.value as? QrGenerateResult.Success)?.bitmap ?: return
        viewModelScope.launch {
            val saved = imageStore.saveToGallery(bitmap)
            _messageRes.value = if (saved) R.string.qr_generate_saved else R.string.qr_generate_save_failed
        }
    }

    /** Stages the current bitmap for the share sheet; null when there is nothing to share. */
    suspend fun prepareShareUri(): Uri? {
        val bitmap = (result.value as? QrGenerateResult.Success)?.bitmap ?: return null
        return imageStore.cacheForSharing(bitmap)
    }

    fun onMessageShown() {
        _messageRes.value = null
    }

    private companion object {
        const val GENERATE_DEBOUNCE_MS = 200L
    }
}

package com.ocrapp.ui.capture

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.ScanRepository
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.OcrRepository
import com.ocrapp.ocr.OcrStage
import com.ocrapp.ocr.PageImage
import com.ocrapp.ocr.PageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class CaptureUiState(
    val mode: EngineType = EngineType.QUICK,
    val isProcessing: Boolean = false,
    val stage: OcrStage? = null,
    val isBackendConfigured: Boolean = false,
    @StringRes val messageRes: Int? = null,
    val savedScanId: Long? = null,
    /** Pages rasterized so far, and how many there are, during [OcrStage.PREPARING]. */
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    /** Pages the import dropped for exceeding the page ceiling; 0 in the normal case. */
    val droppedPages: Int = 0,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pageLoader: PageLoader,
    private val ocrRepository: OcrRepository,
    private val scanRepository: ScanRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CaptureUiState(isBackendConfigured = settings.isBackendConfigured),
    )
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun setMode(mode: EngineType) {
        _state.update { it.copy(mode = mode) }
    }

    fun refreshBackendState() {
        _state.update { it.copy(isBackendConfigured = settings.isBackendConfigured) }
    }

    fun onMessageShown() {
        _state.update { it.copy(messageRes = null) }
    }

    fun onDroppedPagesShown() {
        _state.update { it.copy(droppedPages = 0) }
    }

    fun recognize(uris: List<Uri>) {
        if (uris.isEmpty()) return
        startRecognition { pageLoader.load(uris, ::onPagePrepared) }
    }

    fun recognizeCapture(file: File) {
        startRecognition { pageLoader.loadCapture(file) }
    }

    private fun onPagePrepared(done: Int, total: Int) {
        _state.update { it.copy(pagesDone = done, pagesTotal = total) }
    }

    /** Clears the navigation signal once the Result screen has been opened. */
    fun onNavigated() {
        _state.update { it.copy(savedScanId = null) }
    }

    private fun startRecognition(loadPages: suspend () -> Result<LoadedPages>) {
        if (_state.value.isProcessing) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isProcessing = true,
                    stage = OcrStage.PREPARING,
                    pagesDone = 0,
                    pagesTotal = 0,
                )
            }

            val loaded = loadPages().getOrElse {
                finishWithMessage(R.string.error_load_failed)
                return@launch
            }
            val pages = loaded.pages
            if (pages.isEmpty()) {
                finishWithMessage(R.string.error_load_failed)
                return@launch
            }

            val recognition = ocrRepository.recognize(
                pages = pages,
                requested = _state.value.mode,
                onStage = { stage -> _state.update { it.copy(stage = stage) } },
            )

            val result = recognition.getOrElse {
                pages.forEach { page -> page.bitmap.recycle() }
                finishWithMessage(R.string.error_load_failed)
                return@launch
            }

            if (result.output.plainText.isBlank()) {
                pages.forEach { page -> page.bitmap.recycle() }
                finishWithMessage(R.string.error_no_text)
                return@launch
            }

            val scanId = scanRepository.save(
                output = result.output,
                firstPage = pages.first().bitmap,
                title = titleFor(result.output.plainText),
            )
            pages.forEach { page -> page.bitmap.recycle() }

            _state.update {
                it.copy(
                    isProcessing = false,
                    stage = null,
                    savedScanId = scanId,
                    messageRes = result.fallbackReason?.toMessageRes(),
                    droppedPages = loaded.droppedPages,
                )
            }
        }
    }

    private fun finishWithMessage(@StringRes messageRes: Int) {
        _state.update { it.copy(isProcessing = false, stage = null, messageRes = messageRes) }
    }

    /** Uses the first meaningful line as the title, falling back to a timestamp. */
    private fun titleFor(text: String): String {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length >= MIN_TITLE_LENGTH }
            ?.take(MAX_TITLE_LENGTH)

        return firstLine ?: DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date())
    }

    private companion object {
        const val MIN_TITLE_LENGTH = 3
        const val MAX_TITLE_LENGTH = 60
    }
}

@StringRes
private fun FallbackReason.toMessageRes(): Int = when (this) {
    FallbackReason.NOT_CONFIGURED -> R.string.error_no_backend
    FallbackReason.UNREACHABLE -> R.string.error_backend_unreachable
}

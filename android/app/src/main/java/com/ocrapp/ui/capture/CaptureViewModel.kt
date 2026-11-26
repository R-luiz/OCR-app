package com.ocrapp.ui.capture

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.ScanRepository
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.OcrProgress
import com.ocrapp.ocr.OcrRepository
import com.ocrapp.ocr.OcrStage
import com.ocrapp.ocr.PageLoader
import com.ocrapp.ocr.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** A scan that produced nothing to open, and why. */
data class CaptureError(
    @StringRes val messageRes: Int,
    /** The underlying cause in its own words, when there is one worth showing. */
    val detail: String? = null,
)

data class CaptureUiState(
    val mode: EngineType = EngineType.QUICK,
    val isBackendConfigured: Boolean = false,
    /** When set, the shutter collects pages instead of scanning each photo at once. */
    val multiPage: Boolean = false,
    /** Photos collected in multi-page mode and not scanned yet. */
    val pendingPages: Int = 0,
    val isCapturing: Boolean = false,
    /** Where the running scan is; null when none is running. */
    val progress: OcrProgress? = null,
    val error: CaptureError? = null,
    val savedScanId: Long? = null,
) {
    val isProcessing: Boolean get() = progress != null
    val isBusy: Boolean get() = isCapturing || isProcessing
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pageLoader: PageLoader,
    private val ocrRepository: OcrRepository,
    private val scanRepository: ScanRepository,
    private val settings: SettingsStore,
    private val notices: ScanNoticeFormatter,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** The photos behind [CaptureUiState.pendingPages], in the order they were taken. */
    private val pendingPhotos = mutableListOf<File>()

    private var scan: Job? = null

    fun setMode(mode: EngineType) {
        _state.update { it.copy(mode = mode) }
    }

    fun setMultiPage(enabled: Boolean) {
        _state.update { it.copy(multiPage = enabled) }
    }

    fun refreshBackendState() {
        viewModelScope.launch {
            val stored = runCatchingCancellable { settings.load() }.getOrNull()
            _state.update { it.copy(isBackendConfigured = stored?.isComplete == true) }
        }
    }

    fun onErrorShown() {
        _state.update { it.copy(error = null) }
    }

    /** Clears the navigation signal once the Result screen has been opened. */
    fun onNavigated() {
        _state.update { it.copy(savedScanId = null) }
    }

    fun recognize(uris: List<Uri>) {
        if (uris.isNotEmpty() && !_state.value.isBusy && pendingPhotos.isEmpty()) startScan(uris)
    }

    fun beginCapture(): Boolean {
        val current = _state.value
        if (current.isBusy || current.pendingPages >= PageLoader.MAX_PAGES) return false
        _state.update { it.copy(isCapturing = true, error = null) }
        return true
    }

    /** Takes ownership of [photo], a file CameraX has just written. */
    fun onPhotoTaken(photo: File) {
        if (!viewModelScope.isActive) {
            photo.delete()
            return
        }
        _state.update { it.copy(isCapturing = false) }
        if (_state.value.multiPage) {
            pendingPhotos += photo
            _state.update { it.copy(pendingPages = pendingPhotos.size) }
        } else {
            startScan(listOf(Uri.fromFile(photo))) { photo.delete() }
        }
    }

    fun onCaptureFailed() {
        _state.update { it.copy(isCapturing = false, error = CaptureError(R.string.error_capture_failed)) }
    }

    /**
     * Scans the collected photos as one document. They are kept unless the scan is
     * saved, so cancelling a slow run — or one that found nothing — does not cost the
     * user every page they just photographed.
     */
    fun scanPending() {
        if (pendingPhotos.isEmpty() || _state.value.isBusy) return
        startScan(pendingPhotos.map(Uri::fromFile)) { saved -> if (saved) discardPending() }
    }

    fun discardPending() {
        pendingPhotos.forEach { it.delete() }
        pendingPhotos.clear()
        _state.update { it.copy(pendingPages = 0) }
    }

    /** Stops the running scan; the remote job, if any, is cancelled with it. */
    fun cancel() {
        if (_state.value.progress?.stage != OcrStage.SAVING) scan?.cancel()
    }

    private fun startScan(uris: List<Uri>, onFinished: (saved: Boolean) -> Unit = {}) {
        if (scan?.isCompleted == false || _state.value.savedScanId != null) {
            onFinished(false)
            return
        }
        val mode = _state.value.mode
        scan = viewModelScope.launch {
            _state.update { it.copy(progress = OcrProgress(OcrStage.PREPARING), error = null) }
            var saved = false
            try {
                saved = runScan(uris, mode)
            } finally {
                onFinished(saved)
                _state.update { it.copy(progress = null) }
            }
        }
    }

    /** Returns whether a scan was saved; when not, [CaptureUiState.error] says why. */
    private suspend fun runScan(uris: List<Uri>, mode: EngineType): Boolean {
        val loaded = pageLoader.open(uris).getOrElse { return fail(R.string.error_load_failed) }

        return loaded.pages.use { pages ->
            if (pages.pageCount == 0) return fail(R.string.error_load_failed)

            val result = ocrRepository
                .recognize(pages, mode) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                .getOrElse { error -> return fail(R.string.error_recognition_failed, error.message) }

            val text = result.output.plainText
            if (text.isBlank()) return fail(R.string.error_no_text)

            _state.update { it.copy(progress = OcrProgress(OcrStage.SAVING)) }
            val scanId = runCatchingCancellable {
                withContext(NonCancellable) {
                    scanRepository.save(
                        output = result.output,
                        pages = pages,
                        title = titleFor(text),
                        notice = notices.format(result, loaded.droppedPages),
                    )
                }
            }.getOrElse { return fail(R.string.error_scan_save_failed) }
            _state.update { it.copy(savedScanId = scanId) }
            true
        }
    }

    private fun fail(@StringRes messageRes: Int, detail: String? = null): Boolean {
        _state.update { it.copy(error = CaptureError(messageRes, detail)) }
        return false
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

    override fun onCleared() {
        pendingPhotos.forEach { it.delete() }
    }

    private companion object {
        const val MIN_TITLE_LENGTH = 3
        const val MAX_TITLE_LENGTH = 60
    }
}

package com.ocrapp.ui.capture

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.ScanRepository
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.BackendUnavailableException
import com.ocrapp.ocr.ConnectionLookup
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.OcrProgress
import com.ocrapp.ocr.OcrRepository
import com.ocrapp.ocr.OcrStage
import com.ocrapp.ocr.PageLoader
import com.ocrapp.ocr.RecognitionResult
import com.ocrapp.ocr.RemoteOcrEngine
import com.ocrapp.ocr.runCatchingCancellable
import com.ocrapp.scan.ScanKeepAlive
import com.ocrapp.scan.ScanOutcome
import com.ocrapp.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** A scan or photo that produced nothing to open, and what to do about it. */
data class CaptureError(@StringRes val messageRes: Int)

/** Whether Document parsing can run, as far as the saved settings tell. */
enum class BackendStatus { READY, NOT_SET_UP, SIGN_IN_EXPIRED }

data class CaptureUiState(
    val mode: EngineType = EngineType.QUICK,
    /** Null until the settings have been read. */
    val backendStatus: BackendStatus? = null,
    /** When set, the shutter collects pages instead of scanning each photo at once. */
    val multiPage: Boolean = false,
    /** Photos collected in multi-page mode and not scanned yet, in the order they were taken. */
    val pendingPages: List<File> = emptyList(),
    val isCapturing: Boolean = false,
    /** Where the running scan is; null when none is running. */
    val progress: OcrProgress? = null,
    /** Whether the running scan asked for the Document-parsing server. */
    val isRemoteScan: Boolean = false,
    /**
     * [SystemClock.elapsedRealtime] when the running scan first waited on the server.
     * Held here rather than in the screen, so the clock survives a rotation and counts
     * the whole wait instead of restarting at each stage.
     */
    val remoteWaitStartedAt: Long? = null,
    /** Stays until dismissed, or until the next photo or scan. */
    val error: CaptureError? = null,
    val savedScanId: Long? = null,
    /**
     * Set when this screen runs Document parsing again on the pages kept from that scan,
     * instead of taking new ones.
     */
    val retryScanId: Long? = null,
    /** The kept pages of [retryScanId] are gone, so trying again cannot help. */
    val retryPagesGone: Boolean = false,
) {
    val isProcessing: Boolean get() = progress != null
    val isBusy: Boolean get() = isCapturing || isProcessing
    val isRetry: Boolean get() = retryScanId != null
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pageLoader: PageLoader,
    private val ocrRepository: OcrRepository,
    private val remoteEngine: RemoteOcrEngine,
    private val scanRepository: ScanRepository,
    private val settings: SettingsStore,
    private val notices: ScanNoticeFormatter,
    private val keepAlive: ScanKeepAlive,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CaptureUiState(
            retryScanId = savedState.get<Long>(Routes.ARG_RETRY_SCAN_ID)?.takeIf { it >= 0 },
        ),
    )
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var scan: Job? = null

    /** Whether the screen is on view; a scan that ends out of sight says so in a notification. */
    private var inForeground = false

    init {
        // A retry restored after the process died is not sent again on its own: the
        // first request may still be running, and every request takes a server's time.
        if (_state.value.isRetry && savedState.get<Boolean>(KEY_RETRY_STARTED) != true) retryDocument()
    }

    fun setMode(mode: EngineType) {
        _state.update { it.copy(mode = mode) }
    }

    fun setMultiPage(enabled: Boolean) {
        _state.update { it.copy(multiPage = enabled) }
    }

    fun refreshBackendState() {
        viewModelScope.launch {
            val status = when (runCatchingCancellable { settings.lookup() }.getOrNull()) {
                is ConnectionLookup.Available -> BackendStatus.READY
                ConnectionLookup.SessionExpired -> BackendStatus.SIGN_IN_EXPIRED
                ConnectionLookup.NotConfigured, null -> BackendStatus.NOT_SET_UP
            }
            _state.update { it.copy(backendStatus = status) }
        }
    }

    fun setInForeground(value: Boolean) {
        inForeground = value
        if (value) keepAlive.dismissOutcome()
    }

    internal fun refreshScanNotification() {
        keepAlive.refreshNotification(owner = this)
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Clears the navigation signal once the Result screen has been opened. */
    fun onNavigated() {
        _state.update { it.copy(savedScanId = null) }
    }

    fun recognize(uris: List<Uri>) {
        val current = _state.value
        if (uris.isEmpty() || current.isBusy || current.pendingPages.isNotEmpty() || current.isRetry) return
        val mode = current.mode
        startScan(remote = mode == EngineType.DOCUMENT) { runScan(uris, mode) }
    }

    fun beginCapture(): Boolean {
        val current = _state.value
        if (current.isBusy || current.isRetry || current.pendingPages.size >= PageLoader.MAX_PAGES) return false
        _state.update { it.copy(isCapturing = true, error = null) }
        return true
    }

    /**
     * Takes ownership of [photo], a file CameraX has just written. Returns whether it
     * joined the batch, as opposed to being scanned at once or dropped.
     */
    fun onPhotoTaken(photo: File): Boolean {
        if (!viewModelScope.isActive) {
            photo.delete()
            return false
        }
        _state.update { it.copy(isCapturing = false) }
        if (_state.value.multiPage) {
            _state.update { it.copy(pendingPages = it.pendingPages + photo) }
            return true
        }
        val mode = _state.value.mode
        startScan(remote = mode == EngineType.DOCUMENT, onFinished = { photo.delete() }) {
            runScan(listOf(Uri.fromFile(photo)), mode)
        }
        return false
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
        val photos = _state.value.pendingPages
        if (photos.isEmpty() || _state.value.isBusy) return
        val mode = _state.value.mode
        startScan(remote = mode == EngineType.DOCUMENT, onFinished = { saved -> if (saved) discard(photos) }) {
            runScan(photos.map(Uri::fromFile), mode)
        }
    }

    /** Drops one page of the batch, as long as no scan is reading it. */
    fun removePending(photo: File) {
        if (_state.value.isProcessing) return
        discard(listOf(photo))
    }

    fun discardPending() {
        discard(_state.value.pendingPages)
    }

    /**
     * Runs Document parsing again on the pages kept from [CaptureUiState.retryScanId],
     * and replaces that scan's text only if it succeeds.
     */
    fun retryDocument() {
        val scanId = _state.value.retryScanId ?: return
        if (_state.value.isBusy || _state.value.retryPagesGone) return
        savedState[KEY_RETRY_STARTED] = true
        startScan(remote = true) { runRetry(scanId) }
    }

    /** Stops the running scan; the remote job, if any, is cancelled with it. */
    fun cancel() {
        if (_state.value.progress?.stage != OcrStage.SAVING) scan?.cancel()
    }

    private fun discard(photos: List<File>) {
        photos.forEach { it.delete() }
        _state.update { it.copy(pendingPages = it.pendingPages - photos.toSet()) }
    }

    /**
     * Runs [run], which returns the saved scan's id, or null on failure. A remote scan holds the process
     * in the foreground meanwhile: a cold start keeps it waiting for minutes, long enough
     * for the user to switch apps.
     */
    private fun startScan(
        remote: Boolean,
        onFinished: (saved: Boolean) -> Unit = {},
        run: suspend () -> Long?,
    ) {
        if (scan?.isCompleted == false || _state.value.savedScanId != null) {
            onFinished(false)
            return
        }
        val start = OcrProgress(OcrStage.PREPARING)
        _state.update {
            it.copy(progress = start, isRemoteScan = remote, remoteWaitStartedAt = null, error = null)
        }
        if (remote) keepAlive.start(owner = this, progress = start, onCancel = ::cancel)
        scan = viewModelScope.launch {
            var scanId: Long? = null
            try {
                scanId = run()
            } finally {
                onFinished(scanId != null)
                // Cancelled scans end without a word: the user stopped them, or left.
                if (remote) keepAlive.stop(owner = this@CaptureViewModel, outcome = if (isActive) outcome(scanId) else null)
                _state.update {
                    it.copy(progress = null, isRemoteScan = false, remoteWaitStartedAt = null, savedScanId = scanId)
                }
            }
        }
    }

    /** What to tell a user who is no longer looking: nothing when they are. */
    private fun outcome(scanId: Long?): ScanOutcome? = when {
        inForeground -> null
        scanId != null -> ScanOutcome.Ready(scanId)
        else -> _state.value.error?.let { ScanOutcome.Failed(it.messageRes) }
    }

    /** Called from the engines' threads. */
    private fun onProgress(progress: OcrProgress) {
        val updated = _state.updateAndGet { current ->
            current.copy(
                progress = progress,
                remoteWaitStartedAt = current.remoteWaitStartedAt
                    ?: SystemClock.elapsedRealtime().takeIf { progress.stage.isRemote },
            )
        }
        if (updated.isRemoteScan) keepAlive.update(owner = this, progress, updated.remoteWaitStartedAt)
    }

    /** Returns the saved scan's id, or null and a [CaptureUiState.error]. */
    private suspend fun runScan(uris: List<Uri>, mode: EngineType): Long? {
        val loaded = pageLoader.open(uris).getOrElse { error -> return fail(R.string.error_load_failed, error) }

        return loaded.pages.use { pages ->
            if (pages.pageCount == 0) return fail(R.string.error_load_failed)

            val result = ocrRepository.recognize(pages, mode, ::onProgress)
                .getOrElse { error -> return fail(R.string.error_recognition_failed, error) }

            val text = result.output.plainText
            if (text.isBlank()) return fail(R.string.error_no_text)

            onProgress(OcrProgress(OcrStage.SAVING))
            runCatchingCancellable {
                withContext(NonCancellable) {
                    scanRepository.save(
                        output = result.output,
                        pages = pages,
                        title = titleFor(text),
                        notice = notices.format(result, loaded.droppedPages),
                    ).also { id ->
                        // Its sources are what Document parsing needs to be tried again,
                        // and the photos among them are deleted once this returns. A scan
                        // whose pages could not be kept still stands; it only cannot be retried.
                        if (result.fallbackReason != null && !scanRepository.keepPages(id, uris)) {
                            Log.w(TAG, "Could not keep the pages of scan $id")
                        }
                    }
                }
            }.getOrElse { error -> return fail(R.string.error_scan_save_failed, error) }
        }
    }

    /**
     * Document parsing alone, with no fallback: the scan already holds the Quick-scan
     * text, which a failure here leaves untouched. Returns the id on success.
     */
    private suspend fun runRetry(scanId: Long): Long? {
        val uris = runCatchingCancellable { scanRepository.keptPages(scanId) }.getOrDefault(emptyList())
        if (uris.isEmpty()) return pagesGone()
        val loaded = pageLoader.open(uris).getOrElse { error -> return pagesGone(error) }

        return loaded.pages.use { pages ->
            if (pages.pageCount == 0) return pagesGone()

            val output = remoteEngine.recognize(pages, ::onProgress)
                .getOrElse { error -> return fail(error.retryMessageRes(), error) }
            if (output.plainText.isBlank()) return fail(R.string.retry_error_no_text)

            onProgress(OcrProgress(OcrStage.SAVING))
            runCatchingCancellable {
                withContext(NonCancellable) {
                    scanRepository.replaceContent(
                        scanId = scanId,
                        output = output,
                        // Pages the server skipped, or that could not be read, still
                        // deserve the notice a first scan would have shown.
                        notice = notices.format(RecognitionResult(output), loaded.droppedPages),
                    )
                }
            }.getOrElse { error -> return fail(R.string.error_scan_update_failed, error) }

            // Left to the sweep at the next start if this fails: the scan is updated.
            runCatchingCancellable { withContext(NonCancellable) { scanRepository.dropKeptPages(scanId) } }
                .onFailure { error -> Log.w(TAG, "Could not drop the kept pages of scan $scanId", error) }
            scanId
        }
    }

    private fun pagesGone(cause: Throwable? = null): Long? {
        _state.update { it.copy(retryPagesGone = true) }
        return fail(R.string.retry_error_pages_gone, cause)
    }

    /** The cause goes to the log; the user gets a sentence that says what to do next. */
    private fun fail(@StringRes messageRes: Int, cause: Throwable? = null): Long? {
        if (cause != null) Log.d(TAG, "Scan failed", cause)
        _state.update { it.copy(error = CaptureError(messageRes)) }
        return null
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
        _state.value.pendingPages.forEach { it.delete() }
        // The scan's own cleanup runs too late to be sure of it: the job is cancelled
        // with this view model, and the service must not outlive the screen's scan.
        keepAlive.stop(owner = this, outcome = null)
    }

    private companion object {
        const val TAG = "CaptureViewModel"
        const val KEY_RETRY_STARTED = "retryStarted"
        const val MIN_TITLE_LENGTH = 3
        const val MAX_TITLE_LENGTH = 60
    }
}

@StringRes
private fun Throwable.retryMessageRes(): Int = when ((this as? BackendUnavailableException)?.reason) {
    FallbackReason.NOT_CONFIGURED -> R.string.retry_error_not_set_up
    FallbackReason.SESSION_EXPIRED -> R.string.retry_error_session_expired
    FallbackReason.UNREACHABLE -> R.string.retry_error_unreachable
    FallbackReason.BACKEND_ERROR, null -> R.string.retry_error_failed
}

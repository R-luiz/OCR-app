package com.ocrapp.ui.result

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.data.ScanEntity
import com.ocrapp.data.ScanRepository
import com.ocrapp.ocr.runCatchingCancellable
import com.ocrapp.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ResultUiState(
    val isLoading: Boolean = true,
    val scan: ScanEntity? = null,
    /**
     * The scan's text as last handed over by the Raw editor, or as it opened: the
     * unsaved draft if one was left, else the stored text. For an Unlimited-OCR scan
     * this is the Markdown source, which the Rendered view renders and the Raw view
     * edits; for a Quick scan it is just the recognized text.
     *
     * While the Raw editor is shown, the live text is in the editor and this lags it
     * by up to a second; see `RawTextEditor`.
     */
    val text: String = "",
    /**
     * The text was typed into since it was last saved, or opened on a draft. Set on the
     * first keystroke rather than by comparing texts, so typing never copies the
     * document; Back still compares, and leaves quietly if the edits were undone.
     */
    val hasUnsavedEdits: Boolean = false,
    val showRendered: Boolean = true,
    /** Pages of a fallback scan are kept, so Document parsing can run again on them. */
    val hasKeptPages: Boolean = false,
    val isDeleted: Boolean = false,
    val isWriting: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    /** An emptied Markdown scan can still be edited as Markdown. */
    val hasMarkdown: Boolean get() = scan?.markdown != null
}

/** What the editable buffer starts from: the Markdown source when there is one. */
private val ScanEntity.storedText: String
    get() = markdown ?: plainText

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val repository: ScanRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scanId: Long = savedStateHandle.get<Long>(Routes.ARG_SCAN_ID) ?: -1L

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state.asStateFlow()

    /** Counts keystrokes, so a save knows whether typing went on while it was written. */
    private var editGeneration = 0L

    /**
     * Orders draft writes and saves. A draft only lands if no later draft, save or
     * discard was asked for in the meantime, so a slow write cannot bring back a draft
     * the user has already saved or thrown away.
     */
    private val writes = Mutex()
    private var draftSeq = 0L

    /** Set once the user discarded or deleted: the editor's last flush must not write a draft. */
    private var isLeaving = false

    init {
        viewModelScope.launch {
            val scan = repository.get(scanId)
            val draft = scan?.draftText
            _state.update {
                it.copy(
                    isLoading = false,
                    scan = scan,
                    text = draft ?: scan?.storedText.orEmpty(),
                    hasUnsavedEdits = scan != null && draft != null && draft != scan.storedText,
                    showRendered = scan?.markdown != null,
                )
            }
        }
        viewModelScope.launch {
            repository.observeHasKeptPages(scanId).collect { has ->
                _state.update { it.copy(hasKeptPages = has) }
            }
        }
    }

    /** Called on every keystroke in the Raw editor; does not touch the text. */
    fun onEdited() {
        editGeneration++
        if (!_state.value.hasUnsavedEdits) _state.update { it.copy(hasUnsavedEdits = true) }
    }

    /**
     * Takes the editor's text once typing has paused, or as the editor goes away, and
     * keeps it as a draft in the database: Android can end the process while the app is
     * in the background, and the edits must be there when the user comes back.
     */
    fun onTextHandedOver(text: String) {
        if (isLeaving) return
        _state.update { it.copy(text = text) }
        val stored = _state.value.scan?.storedText ?: return
        val seq = ++draftSeq
        launchWrite {
            if (seq == draftSeq) {
                // A failed draft costs nothing visible yet; the next pause tries again.
                runCatchingCancellable { repository.saveDraft(scanId, text.takeIf { it != stored }) }
            }
        }
    }

    /** True when [text] is exactly what is stored, so leaving loses nothing. */
    fun isStored(text: String): Boolean = text == _state.value.scan?.storedText

    fun toggleRendered(showRendered: Boolean) {
        _state.update { it.copy(showRendered = showRendered) }
    }

    fun save(text: String) {
        if (_state.value.isWriting) return
        val generation = editGeneration
        val hasMarkdown = _state.value.hasMarkdown
        draftSeq++
        _state.update { it.copy(text = text) }
        write {
            writes.withLock { repository.updateContent(scanId, text, hasMarkdown) }
            reload()
            // Typing that went on during the write is still unsaved.
            if (generation == editGeneration) _state.update { it.copy(hasUnsavedEdits = false) }
        }
    }

    /** Drops the unsaved edits and their draft; the screen leaves right after. */
    fun discard() {
        isLeaving = true
        draftSeq++
        launchWrite { runCatchingCancellable { repository.saveDraft(scanId, null) } }
    }

    fun rename(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == _state.value.scan?.title) return

        write {
            repository.rename(scanId, trimmed)
            reload()
        }
    }

    fun delete() {
        write {
            repository.delete(scanId)
            isLeaving = true
            _state.update { it.copy(isDeleted = true) }
        }
    }

    fun onErrorShown() {
        _state.update { it.copy(errorRes = null) }
    }

    private fun write(operation: suspend () -> Unit) {
        if (_state.value.isWriting) return
        _state.update { it.copy(isWriting = true, errorRes = null) }
        viewModelScope.launch {
            val saved = runCatchingCancellable { operation() }.isSuccess
            _state.update {
                it.copy(isWriting = false, errorRes = if (saved) null else R.string.error_scan_update_failed)
            }
        }
    }

    /**
     * Runs [block] under [writes] to completion even when the screen is closed under
     * it: a draft flushed or dropped on the way out must still reach the database.
     */
    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(NonCancellable) { writes.withLock { block() } }
        }
    }

    /**
     * Re-reads the row so the state reflects exactly what was persisted, including the
     * plain-text copy regenerated from edited Markdown. The text is left alone: the
     * user may have kept typing while the write was in flight.
     */
    private suspend fun reload() {
        val scan = repository.get(scanId)
        _state.update { it.copy(scan = scan) }
    }
}

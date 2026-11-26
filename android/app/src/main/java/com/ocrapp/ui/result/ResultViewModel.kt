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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResultUiState(
    val isLoading: Boolean = true,
    val scan: ScanEntity? = null,
    /**
     * The single editable buffer. For an Unlimited-OCR scan this holds the Markdown
     * source, which the rendered view renders and the raw view edits; for a Quick scan
     * it is just the recognized text.
     */
    val editedText: String = "",
    val showRendered: Boolean = true,
    val isDeleted: Boolean = false,
    val isWriting: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    /** An emptied Markdown scan can still be edited as Markdown. */
    val hasMarkdown: Boolean get() = scan?.markdown != null

    val hasUnsavedEdits: Boolean
        get() = scan != null && editedText != scan.storedText
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

    init {
        viewModelScope.launch {
            val scan = repository.get(scanId)
            _state.update {
                it.copy(
                    isLoading = false,
                    scan = scan,
                    editedText = scan?.storedText.orEmpty(),
                    showRendered = scan?.markdown != null,
                )
            }
        }
    }

    fun onTextChange(value: String) {
        _state.update { it.copy(editedText = value) }
    }

    fun toggleRendered(showRendered: Boolean) {
        _state.update { it.copy(showRendered = showRendered) }
    }

    fun save() {
        val current = _state.value
        if (!current.hasUnsavedEdits) return

        write {
            repository.updateContent(scanId, current.editedText, current.hasMarkdown)
            reload()
        }
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
     * Re-reads the row so the state reflects exactly what was persisted, including the
     * plain-text copy regenerated from edited Markdown. The edit buffer is left alone:
     * the user may have kept typing while the write was in flight.
     */
    private suspend fun reload() {
        val scan = repository.get(scanId)
        _state.update { it.copy(scan = scan) }
    }
}

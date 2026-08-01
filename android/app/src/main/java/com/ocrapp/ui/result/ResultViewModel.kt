package com.ocrapp.ui.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.data.ScanEntity
import com.ocrapp.data.ScanRepository
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
) {
    /** Rendered Markdown is only offered when the model actually produced structure. */
    val hasMarkdown: Boolean get() = !scan?.markdown.isNullOrBlank()

    val hasUnsavedEdits: Boolean
        get() = scan != null && editedText != originalText

    private val originalText: String
        get() = scan?.let { it.markdown?.takeIf(String::isNotBlank) ?: it.plainText }.orEmpty()
}

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
            val markdown = scan?.markdown?.takeIf { it.isNotBlank() }
            _state.update {
                it.copy(
                    isLoading = false,
                    scan = scan,
                    editedText = markdown ?: scan?.plainText.orEmpty(),
                    showRendered = markdown != null,
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
        val scan = current.scan ?: return
        if (!current.hasUnsavedEdits) return

        viewModelScope.launch {
            repository.updateContent(scan.id, current.editedText, current.hasMarkdown)
            // Re-read so the state reflects exactly what was persisted, including the
            // plain-text copy regenerated from edited Markdown.
            val updated = repository.get(scan.id)
            _state.update { it.copy(scan = updated) }
        }
    }

    fun delete() {
        val scan = _state.value.scan ?: return
        viewModelScope.launch {
            repository.delete(scan.id)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    /** Text handed to the clipboard and the share sheet. */
    fun shareableText(): String = _state.value.editedText
}

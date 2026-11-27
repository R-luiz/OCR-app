package com.ocrapp.ui.result

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.R
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.MarkdownToPlainText
import com.ocrapp.ui.components.ConfirmDialog
import com.ocrapp.ui.components.DeleteScanDialog
import com.ocrapp.ui.components.copySensitiveText
import com.ocrapp.ui.icons.ContentCopy
import com.ocrapp.ui.icons.Save
import com.ocrapp.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ResultDialog { RENAME, DELETE, DISCARD_EDITS, RETRY_DOCUMENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onBack: () -> Unit,
    onRetryDocument: (Long) -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val shareChooserTitle = stringResource(R.string.result_share_chooser)
    val clipLabel = stringResource(R.string.result_title)

    var dialog by rememberSaveable { mutableStateOf<ResultDialog?>(null) }
    var isMenuOpen by remember { mutableStateOf(false) }

    val editor = remember(viewModel) {
        RawEditor(onEdited = viewModel::onEdited, onHandOver = viewModel::onTextHandedOver)
    }

    /** The text as the user sees it now: the Raw editor holds it while it is shown. */
    fun currentText(): String = editor.text() ?: state.text

    /**
     * Copy and Share follow the view on screen: the Rendered view gives the plain text a
     * reader sees, the Raw view the source being edited, Markdown included.
     */
    fun withPayload(use: (String) -> Unit) {
        val text = currentText()
        if (!(state.hasMarkdown && state.showRendered)) return use(text)
        scope.launch { use(withContext(Dispatchers.Default) { MarkdownToPlainText.convert(text) }) }
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    state.errorRes?.let { res ->
        val message = stringResource(res)
        LaunchedEffect(res) {
            snackbarHostState.showSnackbar(message)
            viewModel.onErrorShown()
        }
    }

    // Corrections are typed by hand; a stray Back must not be able to throw them away.
    val leave: () -> Unit = {
        when {
            state.isWriting -> Unit
            !state.hasUnsavedEdits -> onBack()
            viewModel.isStored(currentText()) -> {
                // Typed, then undone: nothing is lost, but a draft may be left over.
                editor.cancelHandOver()
                viewModel.discard()
                onBack()
            }
            else -> dialog = ResultDialog.DISCARD_EDITS
        }
    }
    BackHandler(enabled = state.isWriting || state.hasUnsavedEdits, onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.scan?.title ?: stringResource(R.string.result_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !state.isWriting) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.scan == null) return@TopAppBar

                    if (state.hasUnsavedEdits) {
                        IconButton(
                            onClick = {
                                val text = currentText()
                                editor.cancelHandOver()
                                viewModel.save(text)
                            },
                            enabled = !state.isWriting,
                        ) {
                            Icon(Icons.Default.Save, stringResource(R.string.action_save))
                        }
                    }
                    IconButton(
                        onClick = {
                            withPayload { payload ->
                                copySensitiveText(context, clipLabel, payload)
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                    }
                    IconButton(
                        onClick = {
                            withPayload { payload ->
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                    putExtra(Intent.EXTRA_SUBJECT, state.scan?.title)
                                }
                                context.startActivity(Intent.createChooser(send, shareChooserTitle))
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, stringResource(R.string.action_share))
                    }
                    Box {
                        IconButton(onClick = { isMenuOpen = true }, enabled = !state.isWriting) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.result_more))
                        }
                        DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.result_rename)) },
                                onClick = {
                                    isMenuOpen = false
                                    dialog = ResultDialog.RENAME
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = {
                                    isMenuOpen = false
                                    dialog = ResultDialog.DELETE
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // imePadding: the window is edge-to-edge, so nothing else keeps the editor's
        // last lines from sitting underneath the keyboard.
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Held in a local: `state` is a delegated property, so the compiler cannot
            // smart-cast state.scan to non-null across the branches below.
            val scan = state.scan
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                scan == null -> Text(
                    text = stringResource(R.string.error_scan_missing),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Which engine ran is not cosmetic: a Document-parsing request falls
                    // back to on-device recognition when the backend is unreachable, and
                    // the result should say so every time it is opened.
                    EngineBadge(engine = scan.engineType)
                    scan.notice?.let { notice ->
                        ScanNotice(
                            text = notice,
                            onRetryDocument = if (state.hasKeptPages && !state.isWriting) {
                                { dialog = ResultDialog.RETRY_DOCUMENT }
                            } else {
                                null
                            },
                        )
                    }

                    if (state.hasMarkdown) {
                        ViewModeChips(
                            showRendered = state.showRendered,
                            onChange = { showRendered ->
                                // The Rendered view draws the view model's copy.
                                editor.handOver()
                                viewModel.toggleRendered(showRendered)
                            },
                        )
                    }

                    if (state.showRendered && state.hasMarkdown) {
                        SelectionContainer(modifier = Modifier.fillMaxSize()) {
                            MarkdownText(
                                markdown = state.text,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        // Editable: OCR is never perfect, and fixing a stray character
                        // in place beats re-scanning the page.
                        RawTextEditor(
                            initialText = state.text,
                            editor = editor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        ResultDialog.RENAME -> RenameDialog(
            currentTitle = state.scan?.title.orEmpty(),
            onRename = { title ->
                dialog = null
                viewModel.rename(title)
            },
            onDismiss = { dialog = null },
        )

        ResultDialog.DELETE -> DeleteScanDialog(
            onConfirm = {
                dialog = null
                viewModel.delete()
            },
            onDismiss = { dialog = null },
        )

        ResultDialog.DISCARD_EDITS -> ConfirmDialog(
            title = stringResource(R.string.result_discard_title),
            body = stringResource(R.string.result_discard_body),
            confirmLabel = stringResource(R.string.result_discard_confirm),
            onConfirm = {
                dialog = null
                editor.cancelHandOver()
                viewModel.discard()
                onBack()
            },
            onDismiss = { dialog = null },
        )

        ResultDialog.RETRY_DOCUMENT -> ConfirmDialog(
            title = stringResource(R.string.result_retry_document_title),
            body = stringResource(R.string.result_retry_document_body),
            confirmLabel = stringResource(R.string.result_retry_document_confirm),
            onConfirm = {
                dialog = null
                state.scan?.let { onRetryDocument(it.id) }
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

/**
 * The Raw view's [EditText] and what links it to the view model.
 *
 * A platform `EditText` re-flows only the paragraph being edited, where a Compose text
 * field lays out the whole text again on every keystroke: seconds of jank per
 * character on a 64-page scan. Its buffer is therefore the live copy of the text. A
 * keystroke only reports that something changed; the text itself is copied out a
 * second after typing stops, when the editor goes away, and when Save, Copy, Share, Back
 * or the switch to Rendered needs it.
 */
private class RawEditor(
    private val onEdited: () -> Unit,
    private val onHandOver: (String) -> Unit,
) {
    private var view: EditText? = null

    /** Typed into since the text was last handed over. */
    private var isDirty = false

    private val handOverRunnable = Runnable { handOver() }

    fun attach(editText: EditText) {
        view = editText
        editText.doAfterTextChanged {
            isDirty = true
            onEdited()
            editText.removeCallbacks(handOverRunnable)
            editText.postDelayed(handOverRunnable, HAND_OVER_DELAY_MS)
        }
    }

    /** The live text, or null when the Raw view is not shown. */
    fun text(): String? = view?.text?.toString()

    /** Copies the text out now if it changed since the last time. */
    fun handOver() {
        val editText = view ?: return
        editText.removeCallbacks(handOverRunnable)
        if (!isDirty) return
        isDirty = false
        onHandOver(editText.text.toString())
    }

    /** Forgets a pending hand-over: the caller has taken the text itself. */
    fun cancelHandOver() {
        view?.removeCallbacks(handOverRunnable)
        isDirty = false
    }

    fun detach() {
        handOver()
        view = null
    }

    private companion object {
        const val HAND_OVER_DELAY_MS = 1_000L
    }
}

@Composable
private fun RawTextEditor(initialText: String, editor: RawEditor, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary
    AndroidView(
        factory = { context ->
            val metrics = context.resources.displayMetrics
            fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
            EditText(context).apply {
                // The text is kept by the view model and the draft column, not in the
                // saved instance state: a long scan would overflow the Bundle.
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                // No spell-checking: on OCR output it underlines half the words and
                // re-checks every paragraph the user scrolls through.
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                gravity = Gravity.TOP or Gravity.START
                background = null
                // MonoTextStyle, in platform units.
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, metrics).roundToInt()
                setTextColor(textColor)
                highlightColor = accentColor.copy(alpha = 0.4f).toArgb()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textCursorDrawable = GradientDrawable().apply {
                        setColor(accentColor.toArgb())
                        setSize(dp(2f).roundToInt(), 0)
                    }
                }
                val padding = dp(16f).roundToInt()
                setPadding(padding, padding, padding, padding)
                setText(initialText)
                // After setText, so loading the text is not taken for an edit.
                editor.attach(this)
            }
        },
        modifier = modifier,
        onRelease = { editor.detach() },
    )
}

@Composable
private fun EngineBadge(engine: EngineType) {
    val label = when (engine) {
        EngineType.QUICK -> R.string.result_engine_quick
        EngineType.DOCUMENT -> R.string.result_engine_document
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * What went differently from what was asked: a fallback, missing pages, a warning.
 * [onRetryDocument] is set while the pages of a fallback are kept for a new attempt.
 */
@Composable
private fun ScanNotice(text: String, onRetryDocument: (() -> Unit)?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (onRetryDocument != null) {
                TextButton(onClick = onRetryDocument, modifier = Modifier.align(Alignment.End)) {
                    Text(
                        text = stringResource(R.string.result_retry_document),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeChips(showRendered: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FilterChip(
            selected = showRendered,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.result_rendered)) },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = !showRendered,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.result_raw)) },
        )
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.result_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.result_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

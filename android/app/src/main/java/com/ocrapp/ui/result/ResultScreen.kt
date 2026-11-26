package com.ocrapp.ui.result

import android.content.Intent
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.R
import com.ocrapp.ocr.EngineType
import com.ocrapp.ui.components.ConfirmDialog
import com.ocrapp.ui.components.DeleteScanDialog
import com.ocrapp.ui.markdown.MarkdownText
import com.ocrapp.ui.theme.MonoTextStyle
import kotlinx.coroutines.launch

private enum class ResultDialog { RENAME, DELETE, DISCARD_EDITS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onBack: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val shareChooserTitle = stringResource(R.string.result_share_chooser)

    var dialog by remember { mutableStateOf<ResultDialog?>(null) }
    var isMenuOpen by remember { mutableStateOf(false) }

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
            state.hasUnsavedEdits -> dialog = ResultDialog.DISCARD_EDITS
            else -> onBack()
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
                        IconButton(onClick = viewModel::save, enabled = !state.isWriting) {
                            Icon(Icons.Default.Save, stringResource(R.string.action_save))
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.editedText))
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                    }
                    IconButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, state.editedText)
                                putExtra(Intent.EXTRA_SUBJECT, state.scan?.title)
                            }
                            context.startActivity(Intent.createChooser(send, shareChooserTitle))
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
                    scan.notice?.let { ScanNotice(text = it) }

                    if (state.hasMarkdown) {
                        ViewModeChips(
                            showRendered = state.showRendered,
                            onChange = viewModel::toggleRendered,
                        )
                    }

                    if (state.showRendered && state.hasMarkdown) {
                        SelectionContainer(modifier = Modifier.fillMaxSize()) {
                            MarkdownText(
                                markdown = state.editedText,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        // Editable: OCR is never perfect, and fixing a stray character
                        // in place beats re-scanning the page.
                        OutlinedTextField(
                            value = state.editedText,
                            onValueChange = viewModel::onTextChange,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            textStyle = MonoTextStyle,
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
                onBack()
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
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

/** What went differently from what was asked: a fallback, missing pages, a warning. */
@Composable
private fun ScanNotice(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
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
    var title by remember { mutableStateOf(currentTitle) }

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

package com.ocrapp.ui.result

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ocrapp.ui.markdown.MarkdownText
import com.ocrapp.ui.theme.MonoTextStyle
import kotlinx.coroutines.launch

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
    val copiedMessage = stringResource(R.string.result_copied)
    val shareChooserTitle = stringResource(R.string.result_share_chooser)

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.hasUnsavedEdits) {
                        IconButton(onClick = viewModel::save) {
                            Icon(Icons.Default.Save, stringResource(R.string.result_save))
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(viewModel.shareableText()))
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.result_copy))
                    }
                    IconButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, viewModel.shareableText())
                                state.scan?.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                            }
                            context.startActivity(Intent.createChooser(send, shareChooserTitle))
                        },
                    ) {
                        Icon(Icons.Default.Share, stringResource(R.string.result_share))
                    }
                    IconButton(onClick = viewModel::delete) {
                        Icon(Icons.Default.Delete, stringResource(R.string.result_delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.scan == null -> Text(
                    text = stringResource(R.string.error_load_failed),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Which engine ran is not cosmetic: a Document-parsing request
                    // silently falls back to on-device recognition when the backend is
                    // unreachable, and the only other hints are a transient snackbar at
                    // scan time and the presence of the Markdown chips below. Reopen a
                    // scan later and both are easy to miss, so state it outright.
                    EngineBadge(engine = state.scan.engineType)

                    if (state.hasMarkdown) {
                        ViewModeChips(
                            showRendered = state.showRendered,
                            onChange = viewModel::toggleRendered,
                        )
                    }

                    if (state.showRendered && state.hasMarkdown) {
                        MarkdownText(
                            markdown = state.editedText,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        )
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

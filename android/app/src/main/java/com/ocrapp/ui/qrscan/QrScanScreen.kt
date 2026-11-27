package com.ocrapp.ui.qrscan

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.R
import com.ocrapp.ui.camera.CameraPermissionPrompt
import com.ocrapp.ui.camera.CameraPreview
import com.ocrapp.ui.camera.rememberCameraPermission
import com.ocrapp.ui.components.copySensitiveText
import com.ocrapp.ui.icons.ContentCopy
import com.ocrapp.ui.icons.OpenInNew
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val cameraPermission = rememberCameraPermission()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val openFailedMessage = stringResource(R.string.qr_result_open_failed)
    val shareChooserTitle = stringResource(R.string.qr_result_share_chooser)
    val clipLabel = stringResource(R.string.qr_scan_title)

    // A buzz when a code is read: the result sheet appears at the bottom, away from where
    // the user is aiming. Saved so a rotation does not buzz again for the same code.
    var feltResult by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.result) {
        val result = state.result
        if (result != null && result != feltResult) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        feltResult = result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (cameraPermission.isGranted) {
                CameraPreview(
                    rememberQrAnalysis(viewModel),
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = stringResource(R.string.qr_scan_hint),
                    // Drawn over the camera image, whatever the theme: white on scrim.
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                CameraPermissionPrompt(
                    rationale = stringResource(R.string.qr_scan_permission_rationale),
                    onRequest = cameraPermission.request,
                    isBlocked = cameraPermission.isBlocked,
                )
            }

            state.result?.let { value ->
                QrResultSheet(
                    value = value,
                    onCopy = {
                        copySensitiveText(context, clipLabel, value)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, value)
                        }
                        context.startActivity(Intent.createChooser(send, shareChooserTitle))
                    },
                    onOpen = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar(openFailedMessage) }
                        }
                    },
                    onScanAgain = viewModel::reset,
                )
            }
        }
    }
}

/** The analysis use case reading QR codes off the preview, on a thread of its own. */
@Composable
private fun rememberQrAnalysis(viewModel: QrScanViewModel): ImageAnalysis {
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) {
        onDispose { executor.shutdown() }
    }
    return remember(viewModel) {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor, viewModel.createAnalyzer()) }
    }
}

/** Recognizes an http(s) link, prefixing a bare scheme-less domain with https. */
private fun String.asOpenableUrl(): String? {
    val trimmed = trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        Patterns.WEB_URL.matcher(trimmed).matches() -> "https://$trimmed"
        else -> null
    }
}

@Composable
private fun BoxScope.QrResultSheet(
    value: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpen: (url: String) -> Unit,
    onScanAgain: () -> Unit,
) {
    val url = remember(value) { value.asOpenableUrl() }

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(16.dp)
            // TalkBack reads the code out as soon as it is found.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, stringResource(R.string.action_share))
                    }
                    if (url != null) {
                        IconButton(onClick = { onOpen(url) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                stringResource(R.string.qr_result_open),
                            )
                        }
                    }
                }
                TextButton(onClick = onScanAgain) {
                    Text(stringResource(R.string.qr_result_scan_again))
                }
            }
        }
    }
}

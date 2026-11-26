package com.ocrapp.ui.capture

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.R
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.OcrProgress
import com.ocrapp.ocr.OcrStage
import com.ocrapp.ocr.PageLoader
import com.ocrapp.ui.camera.CameraPermissionPrompt
import com.ocrapp.ui.camera.CameraPreview
import com.ocrapp.ui.camera.rememberCameraPermission
import com.ocrapp.ui.components.ConfirmDialog
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onScanSaved: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val cameraPermission = rememberCameraPermission()

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var isCameraReady by remember { mutableStateOf<Boolean?>(null) }
    var discardAndLeave by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshBackendState() }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES),
    ) { uris -> viewModel.recognize(uris) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.recognize(listOf(it)) } }

    state.savedScanId?.let { scanId ->
        LaunchedEffect(scanId) {
            viewModel.onNavigated()
            onScanSaved(scanId)
        }
    }

    state.error?.let { error ->
        val message = listOfNotNull(stringResource(error.messageRes), error.detail)
            .joinToString("\n")
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(
                message = message,
                duration = if (error.detail == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            viewModel.onErrorShown()
        }
    }

    val leave: () -> Unit = {
        when {
            state.isProcessing -> viewModel.cancel()
            state.isCapturing -> Unit
            state.pendingPages > 0 -> discardAndLeave = true
            else -> onBack()
        }
    }
    BackHandler(enabled = state.isBusy || state.pendingPages > 0, onBack = leave)

    discardAndLeave?.let { goBack ->
        ConfirmDialog(
            title = stringResource(R.string.capture_discard_pending),
            body = stringResource(R.string.capture_discard_pending_body),
            confirmLabel = stringResource(R.string.result_discard_confirm),
            onConfirm = {
                discardAndLeave = null
                viewModel.discardPending()
                if (goBack) onBack()
            },
            onDismiss = { discardAndLeave = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_title)) },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !state.isCapturing && state.progress?.stage != OcrStage.SAVING) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, enabled = !state.isBusy) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cameraPermission.isGranted) {
                        CameraPreview(
                            imageCapture,
                            modifier = Modifier.fillMaxSize(),
                            onBound = { isCameraReady = it },
                        )
                        if (isCameraReady == false) {
                            Text(
                                text = stringResource(R.string.capture_camera_unavailable),
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        CameraPermissionPrompt(
                            rationale = stringResource(R.string.capture_permission_rationale),
                            onRequest = cameraPermission.request,
                        )
                    }
                }

                ModeSelector(
                    mode = state.mode,
                    isBackendConfigured = state.isBackendConfigured,
                    enabled = !state.isBusy,
                    onModeChange = viewModel::setMode,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )

                MultiPageBar(
                    multiPage = state.multiPage,
                    pendingPages = state.pendingPages,
                    enabled = !state.isBusy,
                    onMultiPageChange = viewModel::setMultiPage,
                    onScan = viewModel::scanPending,
                    onDiscard = { discardAndLeave = false },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                CaptureControls(
                    canImport = !state.isBusy && state.pendingPages == 0,
                    canShoot = cameraPermission.isGranted && isCameraReady == true &&
                        !state.isBusy && state.pendingPages < PageLoader.MAX_PAGES,
                    onPickImages = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickPdf = { pdfPicker.launch(arrayOf(MIME_PDF)) },
                    onShoot = {
                        if (viewModel.beginCapture()) {
                            takePicture(
                                context = context,
                                imageCapture = imageCapture,
                                onCaptured = viewModel::onPhotoTaken,
                                onError = viewModel::onCaptureFailed,
                            )
                        }
                    },
                )

                Spacer(Modifier.height(24.dp))
            }

            state.progress?.let { progress ->
                ProcessingOverlay(progress = progress, onCancel = viewModel::cancel)
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: EngineType,
    isBackendConfigured: Boolean,
    enabled: Boolean,
    onModeChange: (EngineType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == EngineType.QUICK,
                enabled = enabled,
                onClick = { onModeChange(EngineType.QUICK) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.mode_quick))
            }
            SegmentedButton(
                selected = mode == EngineType.DOCUMENT,
                enabled = enabled,
                onClick = { onModeChange(EngineType.DOCUMENT) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.mode_document))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                mode == EngineType.QUICK -> stringResource(R.string.mode_quick_hint)
                isBackendConfigured -> stringResource(R.string.mode_document_hint)
                else -> stringResource(R.string.settings_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Switches the shutter between scanning each photo at once and collecting the pages of
 * one document, and holds the collected pages until they are scanned or discarded.
 */
@Composable
private fun MultiPageBar(
    multiPage: Boolean,
    pendingPages: Int,
    enabled: Boolean,
    onMultiPageChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = multiPage,
            onClick = { onMultiPageChange(!multiPage) },
            // Leaving the mode with pages collected would strand them; they are scanned
            // or discarded first, both of which are one tap away.
            enabled = enabled && pendingPages == 0,
            label = { Text(stringResource(R.string.capture_multi_page)) },
            leadingIcon = if (multiPage) {
                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
            } else {
                null
            },
        )

        if (pendingPages > 0) {
            Spacer(Modifier.weight(1f))

            IconButton(onClick = onDiscard, enabled = enabled) {
                Icon(
                    Icons.Default.DeleteOutline,
                    stringResource(R.string.capture_discard_pending),
                )
            }
            Button(onClick = onScan, enabled = enabled) {
                Text(stringResource(R.string.capture_scan_pending, pendingPages))
            }
        }
    }
}

@Composable
private fun CaptureControls(
    canImport: Boolean,
    canShoot: Boolean,
    onPickImages: () -> Unit,
    onPickPdf: () -> Unit,
    onShoot: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickImages, enabled = canImport) {
            Icon(Icons.Default.PhotoLibrary, stringResource(R.string.capture_import_images))
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (canShoot) 1f else 0.4f),
            modifier = Modifier.size(72.dp),
        ) {
            IconButton(onClick = onShoot, enabled = canShoot) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.capture_shutter),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        IconButton(onClick = onPickPdf, enabled = canImport) {
            Icon(Icons.Default.PictureAsPdf, stringResource(R.string.capture_import_pdf))
        }
    }
}

@Composable
private fun ProcessingOverlay(progress: OcrProgress, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            // Swallows taps, so nothing underneath can be pressed through the scrim.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 12.dp),
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = progress.label(),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // A cold start on a scaled-to-zero GPU endpoint runs into minutes: the
                // worker has to pull an ~8.8 GB image and load 6.7 GB of weights before
                // it can look at the first page. A bare spinner through that is
                // indistinguishable from a hang, so count the time out loud and say what
                // is being waited on.
                val stage = progress.stage
                if (stage == OcrStage.QUEUED || stage == OcrStage.RUNNING) {
                    val elapsed = rememberElapsedSeconds(stage)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatElapsed(elapsed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (stage == OcrStage.QUEUED && elapsed >= COLD_START_HINT_SECONDS) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.progress_cold_start),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                }

                if (stage != OcrStage.SAVING) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Rendering and recognizing a long document takes real time, so those stages name the
 * page being worked on: an unqualified spinner for minutes reads as a hang.
 */
@Composable
private fun OcrProgress.label(): String {
    val hasPages = pageCount > 1
    return when (stage) {
        OcrStage.PREPARING ->
            if (hasPages) {
                stringResource(R.string.progress_preparing_page, page, pageCount)
            } else {
                stringResource(R.string.progress_preparing)
            }

        OcrStage.RECOGNIZING ->
            if (hasPages) {
                stringResource(R.string.progress_recognizing_page, page, pageCount)
            } else {
                stringResource(R.string.progress_recognizing)
            }

        OcrStage.UPLOADING -> stringResource(R.string.progress_uploading)
        OcrStage.QUEUED -> stringResource(R.string.progress_queued)
        OcrStage.RUNNING -> stringResource(R.string.progress_running)
        OcrStage.SAVING -> stringResource(R.string.progress_saving)
    }
}

/** Seconds since [stage] was entered, ticking once a second. */
@Composable
private fun rememberElapsedSeconds(stage: OcrStage): Int {
    var elapsed by remember(stage) { mutableIntStateOf(0) }
    LaunchedEffect(stage) {
        while (true) {
            delay(1_000)
            elapsed += 1
        }
    }
    return elapsed
}

private fun formatElapsed(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit,
    onError: () -> Unit,
) {
    val file = try {
        File.createTempFile("capture_", ".jpg", context.cacheDir)
    } catch (_: IOException) {
        onError()
        return
    }
    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onCaptured(file)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onError()
            }
        },
    )
}

/** How long a queued job may sit before the wait is explained rather than just shown. */
private const val COLD_START_HINT_SECONDS = 15

private const val MIME_PDF = "application/pdf"
private const val MAX_PICKED_IMAGES = 20

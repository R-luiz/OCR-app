package com.ocrapp.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ocrapp.R
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.MlKitOcrEngine
import com.ocrapp.ocr.OcrProgress
import com.ocrapp.ocr.OcrStage
import com.ocrapp.ocr.PageLoader
import com.ocrapp.ui.camera.CameraPermission
import com.ocrapp.ui.camera.CameraPermissionPrompt
import com.ocrapp.ui.camera.CameraPreview
import com.ocrapp.ui.camera.findActivity
import com.ocrapp.ui.camera.rememberCameraPermission
import com.ocrapp.ui.components.ConfirmDialog
import com.ocrapp.ui.icons.CameraAlt
import com.ocrapp.ui.icons.DeleteOutline
import com.ocrapp.ui.icons.PhotoLibrary
import com.ocrapp.ui.icons.PictureAsPdf
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
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraPermission = rememberCameraPermission()

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(PAGE_RESOLUTION)
            .setJpegQuality(CAPTURE_JPEG_QUALITY)
            .build()
    }
    var isCameraReady by remember { mutableStateOf<Boolean?>(null) }
    var discardAndLeave by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    // The path of the batch page open for review.
    var reviewedPage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshBackendState() }
    LifecycleStartEffect(viewModel) {
        viewModel.setInForeground(true)
        onStopOrDispose { viewModel.setInForeground(false) }
    }

    val isProcessing = state.isProcessing
    // The preview is dropped while a scan runs and binds again afterwards; the shutter
    // waits for that.
    LaunchedEffect(isProcessing) {
        if (isProcessing) isCameraReady = null
    }
    // A remote scan can wait for minutes; the screen should not lock in the meantime.
    DisposableEffect(view, isProcessing) {
        view.keepScreenOn = isProcessing
        onDispose { view.keepScreenOn = false }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.refreshScanNotification() }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val isRemoteScan = state.isRemoteScan
    // Asked when a scan first goes to the server, which is when its notification starts
    // to matter. The scan does not wait for the answer.
    LaunchedEffect(isRemoteScan) {
        if (isRemoteScan && !notificationsAsked && context.shouldAskForNotifications()) {
            notificationsAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    // Stopping a scan on the server throws away its wait for a server, which can be
    // minutes long: that is asked first. On the device, stopping costs nothing.
    val stopScan: () -> Unit = {
        if (state.progress?.stage?.isRemote == true) confirmStop = true else viewModel.cancel()
    }
    val leave: () -> Unit = {
        when {
            state.isProcessing -> stopScan()
            state.isCapturing -> Unit
            state.pendingPages.isNotEmpty() -> discardAndLeave = true
            else -> onBack()
        }
    }
    BackHandler(enabled = state.isBusy || state.pendingPages.isNotEmpty(), onBack = leave)

    val canStop = state.progress?.stage.let { it != null && it != OcrStage.SAVING }
    LaunchedEffect(canStop) {
        if (!canStop) confirmStop = false
    }
    if (confirmStop && canStop) {
        ConfirmDialog(
            title = stringResource(R.string.capture_stop_title),
            body = stringResource(R.string.capture_stop_body),
            confirmLabel = stringResource(R.string.capture_stop_confirm),
            onConfirm = {
                confirmStop = false
                viewModel.cancel()
            },
            onDismiss = { confirmStop = false },
        )
    }

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

    val reviewed = state.pendingPages.withIndex().firstOrNull { it.value.path == reviewedPage }
    if (reviewed != null && !state.isProcessing) {
        PageDialog(
            page = reviewed.value,
            number = reviewed.index + 1,
            onDelete = {
                reviewedPage = null
                viewModel.removePending(reviewed.value)
            },
            onDismiss = { reviewedPage = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isRetry) R.string.retry_title else R.string.capture_title))
                },
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
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The scrim covers everything below while a scan runs; screen readers should
            // not reach through it either.
            val underScrim = if (state.isProcessing) Modifier.clearAndSetSemantics {} else Modifier

            if (state.isRetry) {
                RetryPanel(
                    error = state.error,
                    canRetry = !state.isBusy && !state.retryPagesGone,
                    onRetry = viewModel::retryDocument,
                    onDismissError = viewModel::dismissError,
                    modifier = underScrim,
                )
            } else {
                val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
                    Viewfinder(
                        cameraPermission = cameraPermission,
                        // Nobody sees the preview under the scrim, and keeping it bound
                        // keeps the sensor and the image processor running.
                        showCamera = !state.isProcessing,
                        imageCapture = imageCapture,
                        isCameraReady = isCameraReady,
                        onBound = { isCameraReady = it },
                        modifier = modifier,
                    )
                }
                val controls: @Composable ColumnScope.() -> Unit = {
                    state.error?.let { error ->
                        ErrorCard(
                            message = stringResource(error.messageRes),
                            onDismiss = viewModel::dismissError,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        )
                    }

                    ModeSelector(
                        mode = state.mode,
                        backendStatus = state.backendStatus,
                        enabled = !state.isBusy,
                        onModeChange = viewModel::setMode,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )

                    MultiPageBar(
                        multiPage = state.multiPage,
                        pages = state.pendingPages,
                        enabled = !state.isBusy,
                        onMultiPageChange = viewModel::setMultiPage,
                        onScan = viewModel::scanPending,
                        onDiscard = { discardAndLeave = false },
                        onReviewPage = { reviewedPage = it.path },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    CaptureControls(
                        canImport = !state.isBusy && state.pendingPages.isEmpty(),
                        canShoot = cameraPermission.isGranted && isCameraReady == true &&
                            !state.isBusy && state.pendingPages.size < PageLoader.MAX_PAGES,
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
                                    onCaptured = { photo ->
                                        // The shutter gives no other sign that a page
                                        // joined the batch.
                                        if (viewModel.onPhotoTaken(photo)) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onError = viewModel::onCaptureFailed,
                                )
                            }
                        },
                    )
                }

                if (maxWidth > maxHeight) {
                    // In landscape the controls stacked under the preview would leave it
                    // a sliver: they go beside it instead.
                    Row(modifier = underScrim.fillMaxSize()) {
                        viewfinder(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(start = 16.dp, bottom = 16.dp),
                        )
                        Box(
                            modifier = Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 16.dp),
                                content = controls,
                            )
                        }
                    }
                } else {
                    Column(modifier = underScrim.fillMaxSize()) {
                        viewfinder(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                        controls()
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            state.progress?.let { progress ->
                ProcessingOverlay(
                    progress = progress,
                    remoteWaitStartedAt = state.remoteWaitStartedAt,
                    onCancel = stopScan,
                )
            }
        }
    }
}

@Composable
private fun Viewfinder(
    cameraPermission: CameraPermission,
    showCamera: Boolean,
    imageCapture: ImageCapture,
    isCameraReady: Boolean?,
    onBound: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!cameraPermission.isGranted) {
            CameraPermissionPrompt(
                rationale = stringResource(R.string.capture_permission_rationale),
                onRequest = cameraPermission.request,
                isBlocked = cameraPermission.isBlocked,
            )
        } else if (showCamera) {
            CameraPreview(imageCapture, modifier = Modifier.fillMaxSize(), onBound = onBound)
            if (isCameraReady == false) {
                Text(
                    text = stringResource(R.string.capture_camera_unavailable),
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Why the last attempt produced nothing and what to do next. It stays until dismissed or
 * until the next attempt: a snackbar left too little time to read a long message.
 */
@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.capture_error_dismiss))
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: EngineType,
    backendStatus: BackendStatus?,
    enabled: Boolean,
    onModeChange: (EngineType) -> Unit,
    onOpenSettings: () -> Unit,
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

        // Says before the scan, not after it, that Document parsing cannot run, what
        // happens instead, and where to fix it.
        val unavailable = when {
            mode == EngineType.QUICK -> null
            backendStatus == BackendStatus.NOT_SET_UP -> R.string.mode_document_setup_hint
            backendStatus == BackendStatus.SIGN_IN_EXPIRED -> R.string.mode_document_expired_hint
            else -> null
        }
        if (unavailable == null) {
            Text(
                text = stringResource(
                    if (mode == EngineType.QUICK) R.string.mode_quick_hint else R.string.mode_document_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings, enabled = enabled) {
                    Text(stringResource(R.string.mode_document_open_settings))
                }
            }
        }
    }
}

/**
 * Switches the shutter between scanning each photo at once and collecting the pages of
 * one document, and holds the collected pages until they are scanned or discarded.
 */
@Composable
private fun MultiPageBar(
    multiPage: Boolean,
    pages: List<File>,
    enabled: Boolean,
    onMultiPageChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onDiscard: () -> Unit,
    onReviewPage: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = multiPage,
                onClick = { onMultiPageChange(!multiPage) },
                // Leaving the mode with pages collected would strand them; they are
                // scanned or discarded first, both of which are one tap away.
                enabled = enabled && pages.isEmpty(),
                label = { Text(stringResource(R.string.capture_multi_page)) },
                leadingIcon = if (multiPage) {
                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                } else {
                    null
                },
            )

            if (pages.isNotEmpty()) {
                Spacer(Modifier.weight(1f))

                IconButton(onClick = onDiscard, enabled = enabled) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        stringResource(R.string.capture_discard_pending),
                    )
                }
                Button(onClick = onScan, enabled = enabled) {
                    Text(stringResource(R.string.capture_scan_pending, pages.size))
                }
            }
        }

        if (pages.isNotEmpty()) {
            PendingPages(pages = pages, enabled = enabled, onReviewPage = onReviewPage)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (pages.size >= PageLoader.MAX_PAGES) {
                    stringResource(R.string.capture_batch_full, PageLoader.MAX_PAGES)
                } else {
                    stringResource(R.string.capture_import_blocked)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The batch so far, newest last and kept in view, each page a tap away from a closer
 * look. The count is announced as it grows: the shutter makes no other sign that a page
 * joined the batch.
 */
@Composable
private fun PendingPages(pages: List<File>, enabled: Boolean, onReviewPage: (File) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(pages.size) {
        listState.animateScrollToItem(pages.lastIndex.coerceAtLeast(0))
    }

    Column {
        Text(
            text = pluralStringResource(R.plurals.capture_batch_pages, pages.size, pages.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(4.dp))
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, page -> page.path }) { index, page ->
                AsyncImage(
                    model = page,
                    contentDescription = stringResource(R.string.capture_page_number, index + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(
                            enabled = enabled,
                            onClickLabel = stringResource(R.string.capture_page_review),
                        ) { onReviewPage(page) },
                )
            }
        }
    }
}

/** One page of the batch, large enough to check, with the way to drop it. */
@Composable
private fun PageDialog(page: File, number: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_page_number, number)) },
        text = {
            AsyncImage(
                model = page,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_page_keep)) }
        },
    )
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

/**
 * In place of the camera when this screen runs Document parsing again on the pages kept
 * from a scan: what is happening, or why it failed, and the way to try again.
 */
@Composable
private fun RetryPanel(
    error: CaptureError?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (error == null) {
                Text(
                    text = stringResource(R.string.retry_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else {
                ErrorCard(message = stringResource(error.messageRes), onDismiss = onDismissError)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.retry_result_kept),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, enabled = canRetry) {
                Text(stringResource(R.string.retry_try_again))
            }
        }
    }
}

@Composable
private fun ProcessingOverlay(progress: OcrProgress, remoteWaitStartedAt: Long?, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            // Takes the touches, so nothing underneath can be pressed through the scrim,
            // without becoming a button of its own to screen readers.
            .pointerInput(Unit) {},
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                // A cold start on a scaled-to-zero GPU endpoint runs to 10 minutes: the
                // worker has to pull its image and load the model's weights before it can
                // look at the first page. A bare spinner through that is
                // indistinguishable from a hang, so count the whole wait out loud and say
                // what is being waited on.
                val stage = progress.stage
                if (stage.isRemote && remoteWaitStartedAt != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatElapsed(rememberSecondsSince(remoteWaitStartedAt)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stage == OcrStage.QUEUED) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.progress_cold_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
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

@Composable
@ReadOnlyComposable
private fun OcrProgress.label(): String = LocalContext.current.resources.progressLabel(this)

/** Whole seconds since [startedAt], on [SystemClock.elapsedRealtime], ticking once a second. */
@Composable
private fun rememberSecondsSince(startedAt: Long): Long {
    var seconds by remember(startedAt) { mutableLongStateOf(secondsSince(startedAt)) }
    LaunchedEffect(startedAt) {
        while (true) {
            // Wakes on the next whole second of the wait, so the clock never skips one.
            delay(1_000 - (SystemClock.elapsedRealtime() - startedAt) % 1_000)
            seconds = secondsSince(startedAt)
        }
    }
    return seconds
}

private fun secondsSince(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt) / 1_000

private fun formatElapsed(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Asks for notifications unless they are allowed, or the user has already said no to
 * them once: the question is not repeated.
 */
private fun Context.shouldAskForNotifications(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED &&
        findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit,
    onError: () -> Unit,
) {
    val file = try {
        CaptureFiles.create(context)
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

/**
 * Photos at the size the recognizers read a page at, in the sensor's 4:3, rather than at
 * the sensor's full 12 MP or more: those extra pixels were decoded only to be thrown
 * away, and cost a slower shutter and several megabytes on disk per page.
 */
private val PAGE_RESOLUTION = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(MlKitOcrEngine.PAGE_EDGE_PX, MlKitOcrEngine.PAGE_EDGE_PX * 3 / 4),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        ),
    )
    .build()

/** Text survives this compression intact, at about half the size of the default 95. */
private const val CAPTURE_JPEG_QUALITY = 90

/** Wide enough for the mode selector and the shutter row in landscape. */
private val SIDE_PANEL_WIDTH = 320.dp

private const val MIME_PDF = "application/pdf"
private const val MAX_PICKED_IMAGES = 20

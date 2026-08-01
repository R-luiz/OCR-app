package com.ocrapp.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.R
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.OcrStage
import java.io.File
import java.util.concurrent.Executor

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

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        viewModel.refreshBackendState()
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES),
    ) { uris -> viewModel.recognize(uris) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.recognize(listOf(it)) } }

    // Held here so the shutter button can trigger a capture from the composable tree.
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    state.savedScanId?.let { scanId ->
        LaunchedEffect(scanId) {
            viewModel.onNavigated()
            onScanSaved(scanId)
        }
    }

    state.messageRes?.let { messageRes ->
        val message = stringResource(messageRes)
        LaunchedEffect(messageRes, message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
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
                    if (hasCameraPermission) {
                        CameraPreview(onImageCaptureReady = { imageCapture = it })
                    } else {
                        PermissionPrompt(
                            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        )
                    }
                }

                ModeSelector(
                    mode = state.mode,
                    isBackendConfigured = state.isBackendConfigured,
                    onModeChange = viewModel::setMode,
                    modifier = Modifier.padding(16.dp),
                )

                CaptureControls(
                    enabled = !state.isProcessing,
                    canShoot = hasCameraPermission && imageCapture != null,
                    onPickImages = {
                        imagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onPickPdf = { pdfPicker.launch(arrayOf(MIME_PDF)) },
                    onShoot = {
                        val capture = imageCapture ?: return@CaptureControls
                        takePicture(context, capture) { file ->
                            viewModel.recognizeCapture(file)
                        }
                    },
                )

                Spacer(Modifier.height(24.dp))
            }

            if (state.isProcessing) {
                ProcessingOverlay(stage = state.stage)
            }
        }
    }
}

@Composable
private fun CameraPreview(onImageCaptureReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                onImageCaptureReady(capture)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.capture_permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequest) {
            Text(stringResource(R.string.capture_grant_permission))
        }
    }
}

@Composable
private fun ModeSelector(
    mode: EngineType,
    isBackendConfigured: Boolean,
    onModeChange: (EngineType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == EngineType.QUICK,
                onClick = { onModeChange(EngineType.QUICK) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.mode_quick))
            }
            SegmentedButton(
                selected = mode == EngineType.DOCUMENT,
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

@Composable
private fun CaptureControls(
    enabled: Boolean,
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
        IconButton(onClick = onPickImages, enabled = enabled) {
            Icon(Icons.Default.PhotoLibrary, stringResource(R.string.capture_import_images))
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        ) {
            IconButton(onClick = onShoot, enabled = enabled && canShoot) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.capture_shutter),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        IconButton(onClick = onPickPdf, enabled = enabled) {
            Icon(Icons.Default.PictureAsPdf, stringResource(R.string.capture_import_pdf))
        }
    }
}

@Composable
private fun ProcessingOverlay(stage: OcrStage?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(28.dp),
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        when (stage) {
                            OcrStage.PREPARING, null -> R.string.progress_preparing
                            OcrStage.RECOGNIZING -> R.string.progress_recognizing
                            OcrStage.UPLOADING -> R.string.progress_uploading
                            OcrStage.QUEUED -> R.string.progress_queued
                            OcrStage.RUNNING -> R.string.progress_running
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit,
) {
    val file = File.createTempFile("capture_", ".jpg", context.cacheDir)
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    val executor: Executor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onCaptured(file)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
            }
        },
    )
}

private const val MIME_PDF = "application/pdf"
private const val MAX_PICKED_IMAGES = 20

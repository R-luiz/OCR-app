package com.ocrapp.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ocrapp.R

/** Whether the camera may be used, and how to ask when it may not. */
class CameraPermission(
    val isGranted: Boolean,
    /**
     * Android no longer shows its dialog, so [request] opens the app's system settings
     * instead, and the prompt has to say what to do there.
     */
    val isBlocked: Boolean,
    val request: () -> Unit,
)

/**
 * Tracks the camera permission, asking for it on entry.
 *
 * Android stops showing its dialog after repeated refusals. From then on [request]
 * opens the app's system settings instead, the only place left to grant it — otherwise
 * the button would silently do nothing. A refusal from an earlier session is caught by
 * the request on entry, which Android then answers at once without a dialog.
 */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var isBlocked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isGranted = granted
        isBlocked = !granted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }

    LaunchedEffect(Unit) {
        if (!isGranted) launcher.launch(Manifest.permission.CAMERA)
    }
    // Picks up a grant made in the system settings while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isGranted = context.hasCameraPermission()
    }

    return CameraPermission(isGranted, isBlocked) {
        if (isBlocked) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

/**
 * Shows the back camera, with [useCases] bound to it for as long as this is on screen.
 *
 * [onBound] reports whether binding worked; a device with no back camera fails it.
 */
@Composable
fun CameraPreview(
    vararg useCases: UseCase,
    modifier: Modifier = Modifier,
    onBound: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val currentOnBound by rememberUpdatedState(onBound)

    DisposableEffect(lifecycleOwner, *useCases) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var isDisposed = false

        providerFuture.addListener({
            if (isDisposed) return@addListener
            val isBound = runCatching {
                val cameraProvider = providerFuture.get().also { provider = it }
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    *useCases,
                )
            }.isSuccess
            currentOnBound(isBound)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            isDisposed = true
            provider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Shown in place of the camera while its permission is missing. When it is [isBlocked],
 * [onRequest] leads to the system settings, so the prompt says what to change there.
 */
@Composable
fun CameraPermissionPrompt(rationale: String, onRequest: () -> Unit, isBlocked: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (isBlocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.camera_permission_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequest) {
                Text(
                    stringResource(
                        if (isBlocked) R.string.camera_open_settings else R.string.capture_grant_permission,
                    ),
                )
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

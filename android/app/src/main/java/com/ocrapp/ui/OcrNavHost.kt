package com.ocrapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ocrapp.ui.capture.CaptureScreen
import com.ocrapp.ui.home.HomeScreen
import com.ocrapp.ui.qrgenerate.QrGenerateScreen
import com.ocrapp.ui.qrscan.QrScanScreen
import com.ocrapp.ui.result.ResultScreen
import com.ocrapp.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    /** A new scan. Matches [CAPTURE_PATTERN] with no retry. */
    const val CAPTURE = "capture"
    const val SETTINGS = "settings"
    const val RESULT = "result/{scanId}"
    const val QR_SCAN = "qr_scan"
    const val QR_GENERATE = "qr_generate"

    const val ARG_SCAN_ID = "scanId"

    /** On Capture: the scan whose kept pages go to Document parsing again; -1 for a new scan. */
    const val ARG_RETRY_SCAN_ID = "retryScanId"

    const val CAPTURE_PATTERN = "$CAPTURE?$ARG_RETRY_SCAN_ID={$ARG_RETRY_SCAN_ID}"

    fun result(scanId: Long) = "result/$scanId"

    fun captureRetry(scanId: Long) = "$CAPTURE?$ARG_RETRY_SCAN_ID=$scanId"
}

@Composable
internal fun OcrNavHost(pendingScanId: Long?, onScanOpened: () -> Unit) {
    val navController = rememberNavController()

    // Home remains the back destination, including when a notification starts a new process.
    fun openResult(scanId: Long) {
        val current = navController.currentBackStackEntry
        if (current?.destination?.route == Routes.RESULT && current.arguments?.getLong(Routes.ARG_SCAN_ID) == scanId) return
        navController.navigate(Routes.result(scanId)) { popUpTo(Routes.HOME) }
    }

    // Every tap that navigates goes through dropUnlessResumed (or ifResumed when it
    // carries an argument). A second tap on a back arrow lands while the screen is
    // already leaving, and without it popped Home too and left a blank window.
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { entry ->
            HomeScreen(
                onNewScan = dropUnlessResumed { navController.navigate(Routes.CAPTURE) },
                onOpenScan = { scanId -> entry.ifResumed { navController.navigate(Routes.result(scanId)) } },
                onScanQr = dropUnlessResumed { navController.navigate(Routes.QR_SCAN) },
                onCreateQr = dropUnlessResumed { navController.navigate(Routes.QR_GENERATE) },
                onOpenSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.QR_SCAN) {
            QrScanScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }

        composable(Routes.QR_GENERATE) {
            QrGenerateScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }

        composable(
            route = Routes.CAPTURE_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_RETRY_SCAN_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            CaptureScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                // A scan can end while the app is in the background.
                onScanSaved = ::openResult,
                onOpenSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument(Routes.ARG_SCAN_ID) { type = NavType.LongType }),
        ) { entry ->
            ResultScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onRetryDocument = { scanId ->
                    entry.ifResumed { navController.navigate(Routes.captureRetry(scanId)) }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }

    LaunchedEffect(pendingScanId) {
        if (pendingScanId != null) {
            openResult(pendingScanId)
            onScanOpened()
        }
    }
}

/** [dropUnlessResumed] for callbacks that carry an argument. */
private inline fun NavBackStackEntry.ifResumed(navigate: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate()
}

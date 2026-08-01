package com.ocrapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ocrapp.ui.capture.CaptureScreen
import com.ocrapp.ui.home.HomeScreen
import com.ocrapp.ui.result.ResultScreen
import com.ocrapp.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val SETTINGS = "settings"
    const val RESULT = "result/{scanId}"

    fun result(scanId: Long) = "result/$scanId"

    const val ARG_SCAN_ID = "scanId"
}

@Composable
fun OcrNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewScan = { navController.navigate(Routes.CAPTURE) },
                onOpenScan = { scanId -> navController.navigate(Routes.result(scanId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CAPTURE) {
            CaptureScreen(
                onBack = { navController.popBackStack() },
                onScanSaved = { scanId ->
                    // Replace Capture in the back stack so Back from Result lands on
                    // Home rather than reopening the camera.
                    navController.navigate(Routes.result(scanId)) {
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument(Routes.ARG_SCAN_ID) { type = NavType.LongType }),
        ) {
            ResultScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

package com.ocrapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ocrapp.scan.ScanNotifications.EXTRA_SCAN_ID
import com.ocrapp.ui.OcrNavHost
import com.ocrapp.ui.theme.OcrTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingScanId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingScanId = if (savedInstanceState == null) {
            intent.resultScanId()
        } else {
            savedInstanceState.getLong(EXTRA_SCAN_ID).takeIf { it > 0L }
        }
        enableEdgeToEdge()
        setContent {
            OcrTheme {
                OcrNavHost(pendingScanId, onScanOpened = { pendingScanId = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.resultScanId()?.let { pendingScanId = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingScanId?.let { outState.putLong(EXTRA_SCAN_ID, it) }
    }

    private fun Intent.resultScanId(): Long? =
        if (action == Intent.ACTION_MAIN) getLongExtra(EXTRA_SCAN_ID, 0L).takeIf { it > 0L } else null
}

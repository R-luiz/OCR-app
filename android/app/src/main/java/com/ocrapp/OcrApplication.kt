package com.ocrapp

import android.app.Application
import android.util.Log
import com.ocrapp.data.ScanRepository
import com.ocrapp.ocr.runCatchingCancellable
import com.ocrapp.ui.capture.CaptureFiles
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OcrApplication : Application() {

    /** Lazy, so the database is first built on the sweep's thread rather than at launch. */
    @Inject
    lateinit var scanRepository: Lazy<ScanRepository>

    override fun onCreate() {
        val startedAt = System.currentTimeMillis()
        super.onCreate()
        // What a process death leaves behind: photos of a batch nobody can scan any more,
        // and pages kept for a Document-parsing retry that was never made.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            CaptureFiles.sweep(this@OcrApplication, startedAt)
            runCatchingCancellable { scanRepository.get().sweepKeptPages() }
                .onFailure { error -> Log.w(TAG, "Could not sweep the kept pages", error) }
        }
    }

    private companion object {
        const val TAG = "OcrApplication"
    }
}

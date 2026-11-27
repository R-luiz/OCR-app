package com.ocrapp.ui.capture

import android.content.res.Resources
import com.ocrapp.R
import com.ocrapp.ocr.OcrProgress
import com.ocrapp.ocr.OcrStage

/**
 * What the scan is doing, in words: the processing overlay and the notification of a
 * scan running in the background both say it.
 *
 * Rendering and recognizing a long document takes real time, so those stages name the
 * page being worked on: an unqualified spinner for minutes reads as a hang.
 */
internal fun Resources.progressLabel(progress: OcrProgress): String {
    val hasPages = progress.pageCount > 1
    return when (progress.stage) {
        OcrStage.PREPARING ->
            if (hasPages) {
                getString(R.string.progress_preparing_page, progress.page, progress.pageCount)
            } else {
                getString(R.string.progress_preparing)
            }

        OcrStage.RECOGNIZING ->
            if (hasPages) {
                getString(R.string.progress_recognizing_page, progress.page, progress.pageCount)
            } else {
                getString(R.string.progress_recognizing)
            }

        OcrStage.UPLOADING -> getString(R.string.progress_uploading)
        OcrStage.QUEUED -> getString(R.string.progress_queued)
        OcrStage.RUNNING -> getString(R.string.progress_running)
        OcrStage.SAVING -> getString(R.string.progress_saving)
    }
}

/**
 * The stages spent on the Document-parsing server. A cold start keeps a scan in them for
 * minutes, and stopping one there throws that wait away.
 */
internal val OcrStage.isRemote: Boolean
    get() = this == OcrStage.UPLOADING || this == OcrStage.QUEUED || this == OcrStage.RUNNING

package com.ocrapp.ui.capture

import android.content.Context
import androidx.annotation.StringRes
import com.ocrapp.R
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.RecognitionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Words what the user should know about how a scan came out.
 *
 * These used to be snackbars on the Capture screen, which navigates to the result the
 * moment a scan is saved — so they were dismissed before anyone could read them. The
 * text is stored with the scan and shown on the Result screen instead.
 */
class ScanNoticeFormatter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Null when the scan went exactly as asked. */
    fun format(result: RecognitionResult, droppedPages: Int): String? {
        val output = result.output
        val resources = context.resources

        return buildList {
            result.fallbackReason?.let { reason ->
                // The backend's own words go after the generic sentence: on a paid
                // backend the specific cause is the only actionable part.
                add(
                    listOfNotNull(
                        context.getString(reason.messageRes),
                        result.fallbackDetail?.takeIf { it.isNotBlank() },
                    ).joinToString("\n"),
                )
            }
            // A page nothing was read on is missing content, and the result still reads
            // as a complete document, so it has to be said rather than inferred.
            if (output.emptyPageNumbers.isNotEmpty()) {
                add(
                    resources.getQuantityString(
                        R.plurals.pages_empty,
                        output.emptyPageNumbers.size,
                        output.emptyPageNumbers.size,
                        output.emptyPageNumbers.joinToString(", "),
                    ),
                )
            }
            if (droppedPages > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.pages_dropped,
                        droppedPages,
                        droppedPages,
                        output.pageCount,
                    ),
                )
            }
            addAll(output.warnings)
        }.joinToString("\n\n").ifEmpty { null }
    }
}

@get:StringRes
private val FallbackReason.messageRes: Int
    get() = when (this) {
        FallbackReason.NOT_CONFIGURED -> R.string.error_no_backend
        FallbackReason.SESSION_EXPIRED -> R.string.error_session_expired
        FallbackReason.UNREACHABLE -> R.string.error_backend_unreachable
        FallbackReason.BACKEND_ERROR -> R.string.error_backend_failed
    }

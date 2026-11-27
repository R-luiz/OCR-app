package com.ocrapp.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Puts [text] on the clipboard flagged as sensitive. Scans hold invoices, IDs and
 * other private documents: with the flag, Android 13+ hides the content in its copy
 * preview and keyboards leave it out of their clipboard history and suggestions.
 */
fun copySensitiveText(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/**
 * The platform constant exists from API 33; earlier versions ignore the extra, but
 * keyboards that backport the convention read the same key.
 */
private val EXTRA_IS_SENSITIVE =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }

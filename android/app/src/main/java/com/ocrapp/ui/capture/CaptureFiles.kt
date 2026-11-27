package com.ocrapp.ui.capture

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Where camera photos wait to be scanned: a directory of their own in the app's cache.
 *
 * Their deletion normally follows the scan or the batch they belong to, but a process
 * death skips it, and these are full photos of documents (IDs, invoices). Keeping them
 * apart from the rest of the cache lets [sweep] remove exactly those orphans.
 */
internal object CaptureFiles {

    private const val DIRECTORY = "captures"

    /** A new, empty file for the next photo. */
    @Throws(IOException::class)
    fun create(context: Context): File {
        val directory = File(context.cacheDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create $directory")
        }
        return File.createTempFile("capture_", ".jpg", directory)
    }

    /**
     * Deletes the photos written before [startedAt], the time this process started.
     *
     * Pending photos live only in a view model's memory, so none written by an earlier
     * process can still be scanned. Blocking: call it off the main thread.
     */
    fun sweep(context: Context, startedAt: Long) {
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { file ->
            if (file.lastModified() < startedAt) file.delete()
        }
    }
}

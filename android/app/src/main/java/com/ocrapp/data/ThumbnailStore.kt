package com.ocrapp.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores history thumbnails as JPEGs in app-private storage. Only the file path goes
 * into Room — keeping images out of the database keeps queries and backups small.
 */
@Singleton
class ThumbnailStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** Writes [bitmap], already sized to [EDGE_PX], and returns the file's path. */
    suspend fun save(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        val saved = runCatching {
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
        }.getOrDefault(false)
        if (!saved) {
            file.delete()
            return@withContext null
        }
        file.absolutePath
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching { File(path).delete() }
        Unit
    }

    companion object {
        /** Long edge of a history thumbnail. */
        const val EDGE_PX = 256

        private const val DIRECTORY = "thumbs"
        private const val QUALITY = 80
    }
}

package com.ocrapp.data

import android.content.Context
import android.graphics.Bitmap
import com.ocrapp.ocr.ImageNormalizer
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
    private val normalizer: ImageNormalizer,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    suspend fun save(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = normalizer.scaleToMaxEdge(bitmap, ImageNormalizer.THUMBNAIL_EDGE_PX)
            val file = File(directory, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            file.absolutePath
        }.getOrNull()
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching { File(path).delete() }
        Unit
    }

    private companion object {
        const val DIRECTORY = "thumbs"
        const val THUMBNAIL_QUALITY = 80
    }
}

package com.ocrapp.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Persists generated QR code images: to the shared gallery, or to cache for sharing. */
@Singleton
class QrImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Writes [bitmap] into the shared Pictures/OCR App collection. Returns success. */
    suspend fun saveToGallery(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val name = "qrcode_${System.currentTimeMillis()}.png"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, name)
            } else {
                saveLegacy(bitmap, name)
            }
        }.getOrDefault(false)
    }

    /** Writes [bitmap] to app cache and returns a FileProvider content Uri for sharing. */
    suspend fun cacheForSharing(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "qrcodes").apply { mkdirs() }
            val file = File(dir, "qr_${UUID.randomUUID()}.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun saveViaMediaStore(bitmap: Bitmap, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OCR App")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
    }

    // API 28 only: scoped storage does not apply yet, so this writes directly to the
    // public Pictures directory — it requires WRITE_EXTERNAL_STORAGE, declared with
    // maxSdkVersion=28 in the manifest since minSdk is already 28 — plus a media-scanner
    // pass so the file shows up in the gallery immediately.
    @Suppress("DEPRECATION")
    private fun saveLegacy(bitmap: Bitmap, name: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "OCR App",
        ).apply { mkdirs() }
        val file = File(dir, name)
        val compressed = file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (compressed) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        }
        return compressed
    }
}

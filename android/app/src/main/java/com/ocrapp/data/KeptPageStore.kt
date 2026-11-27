package com.ocrapp.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies of the pages behind a scan whose Document-parsing request fell back to Quick
 * scan, so that Document parsing can run again on them without re-photographing every
 * page. Each scan's pages sit in a directory of their own, numbered in page order.
 */
@Singleton
class KeptPageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Bumped on every change, so [observeHasPages] re-reads the directory. */
    private val changes = MutableStateFlow(0)

    private val root: File get() = File(context.filesDir, DIRECTORY)

    private fun directory(scanId: Long) = File(root, scanId.toString())

    /**
     * Copies [sources], in order, as the pages of [scanId], replacing any it had.
     * A file `PageLoader` takes for a PDF keeps the `.pdf` extension it goes by; the
     * other pages are images. Throws when a source cannot be read, leaving nothing kept.
     */
    suspend fun keep(scanId: Long, sources: List<Uri>) {
        val directory = directory(scanId)
        try {
            withContext(Dispatchers.IO) {
                directory.deleteRecursively()
                if (!directory.mkdirs()) throw FileNotFoundException("Could not create $directory")
                sources.forEachIndexed { index, source ->
                    ensureActive()
                    val target = File(directory, "$index.${extensionOf(source)}")
                    val input = context.contentResolver.openInputStream(source)
                        ?: throw FileNotFoundException("Could not open $source")
                    input.use { from -> target.outputStream().use { to -> from.copyTo(to) } }
                }
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
            throw error
        } finally {
            changes.update { it + 1 }
        }
    }

    /** The kept pages of [scanId] as `file://` URIs in page order; empty if none. */
    suspend fun pages(scanId: Long): List<Uri> = withContext(Dispatchers.IO) {
        listPages(scanId).map(Uri::fromFile)
    }

    fun observeHasPages(scanId: Long): Flow<Boolean> =
        changes
            .map { listPages(scanId).isNotEmpty() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    suspend fun drop(scanId: Long) {
        withContext(Dispatchers.IO) { directory(scanId).deleteRecursively() }
        changes.update { it + 1 }
    }

    /**
     * Drops the pages kept longer ago than [maxAgeMillis]: a fallback nobody retried
     * within that time is not going to be, and the copies are full-size photos.
     */
    suspend fun sweep(maxAgeMillis: Long) {
        withContext(Dispatchers.IO) {
            val keptBefore = System.currentTimeMillis() - maxAgeMillis
            root.listFiles()?.forEach { if (it.lastModified() < keptBefore) it.deleteRecursively() }
        }
        changes.update { it + 1 }
    }

    private fun listPages(scanId: Long): List<File> =
        directory(scanId).listFiles()
            ?.mapNotNull { file -> file.nameWithoutExtension.toIntOrNull()?.let { it to file } }
            ?.sortedBy { it.first }
            ?.map { it.second }
            .orEmpty()

    private fun extensionOf(source: Uri): String {
        val type = context.contentResolver.getType(source)
        val name = source.lastPathSegment.orEmpty()
        return when {
            type == MIME_PDF || (type == null && name.endsWith(".pdf", ignoreCase = true)) -> "pdf"
            else -> type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: name.substringAfterLast('.', "").lowercase().takeIf { it.matches(IMAGE_EXTENSION) }
                ?: "jpg"
        }
    }

    private companion object {
        const val DIRECTORY = "kept_pages"
        const val MIME_PDF = "application/pdf"
        val IMAGE_EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}

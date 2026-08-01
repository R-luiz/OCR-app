package com.ocrapp.data

import android.graphics.Bitmap
import com.ocrapp.ocr.MarkdownToPlainText
import com.ocrapp.ocr.OcrOutput
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepository @Inject constructor(
    private val dao: ScanDao,
    private val thumbnails: ThumbnailStore,
) {

    fun observe(query: String): Flow<List<ScanEntity>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query.trim())

    suspend fun get(id: Long): ScanEntity? = dao.getById(id)

    suspend fun save(output: OcrOutput, firstPage: Bitmap?, title: String): Long {
        val thumbnailPath = firstPage?.let { thumbnails.save(it) }
        return dao.insert(
            ScanEntity(
                createdAt = System.currentTimeMillis(),
                title = title,
                engine = output.engine.name,
                plainText = output.plainText,
                markdown = output.markdown,
                thumbnailPath = thumbnailPath,
                pageCount = output.pageCount,
            ),
        )
    }

    /**
     * Persists user edits.
     *
     * For an Unlimited-OCR scan the edited buffer *is* the Markdown source, so the
     * plain-text copy is regenerated from it to keep history search in sync.
     */
    suspend fun updateContent(id: Long, text: String, isMarkdown: Boolean) {
        val existing = dao.getById(id) ?: return
        dao.update(
            if (isMarkdown) {
                existing.copy(markdown = text, plainText = MarkdownToPlainText.convert(text))
            } else {
                existing.copy(plainText = text)
            },
        )
    }

    suspend fun rename(id: Long, title: String) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(title = title))
    }

    /** Deletes the row and its thumbnail file together, so nothing is orphaned. */
    suspend fun delete(id: Long) {
        val existing = dao.getById(id) ?: return
        dao.delete(existing)
        thumbnails.delete(existing.thumbnailPath)
    }
}

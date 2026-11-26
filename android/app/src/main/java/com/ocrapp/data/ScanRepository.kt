package com.ocrapp.data

import com.ocrapp.ocr.MarkdownToPlainText
import com.ocrapp.ocr.OcrOutput
import com.ocrapp.ocr.PageSource
import com.ocrapp.ocr.runCatchingCancellable
import com.ocrapp.ocr.usePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepository @Inject constructor(
    private val dao: ScanDao,
    private val thumbnails: ThumbnailStore,
) {

    fun observe(query: String): Flow<List<ScanSummary>> =
        dao.observeSummaries(if (query.isBlank()) "" else "%${escapeLike(query.trim())}%")

    suspend fun get(id: Long): ScanEntity? = dao.getById(id)

    /** Stores a finished scan, with a thumbnail of the first of its [pages]. */
    suspend fun save(output: OcrOutput, pages: PageSource, title: String, notice: String?): Long {
        // A scan without a thumbnail is still a scan; the list has a placeholder for it.
        val thumbnailPath = runCatchingCancellable {
            pages.usePage(0, ThumbnailStore.EDGE_PX) { thumbnails.save(it) }
        }.getOrNull()

        return try {
            dao.insert(
                ScanEntity(
                    createdAt = System.currentTimeMillis(),
                    title = title,
                    engine = output.engine.name,
                    plainText = output.plainText,
                    markdown = output.markdown,
                    thumbnailPath = thumbnailPath,
                    pageCount = output.pageCount,
                    notice = notice,
                ),
            )
        } catch (error: Exception) {
            thumbnails.delete(thumbnailPath)
            throw error
        }
    }

    /**
     * Persists user edits.
     *
     * For an Unlimited-OCR scan the edited buffer *is* the Markdown source, so the
     * plain-text copy is regenerated from it to keep history search in sync.
     */
    suspend fun updateContent(id: Long, text: String, isMarkdown: Boolean) {
        if (isMarkdown) {
            val plainText = withContext(Dispatchers.Default) { MarkdownToPlainText.convert(text) }
            dao.updateMarkdown(id, markdown = text, plainText = plainText)
        } else {
            dao.updatePlainText(id, text)
        }
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title)

    /** Deletes the row and its thumbnail file together, so nothing is orphaned. */
    suspend fun delete(id: Long) {
        val thumbnailPath = dao.thumbnailPath(id)
        dao.delete(id)
        thumbnails.delete(thumbnailPath)
    }

    private companion object {
        val LIKE_SPECIAL = Regex("""[\\%_]""")

        /** Makes [text] match literally in a `LIKE ... ESCAPE '\'` pattern. */
        fun escapeLike(text: String): String = text.replace(LIKE_SPECIAL) { "\\" + it.value }
    }
}

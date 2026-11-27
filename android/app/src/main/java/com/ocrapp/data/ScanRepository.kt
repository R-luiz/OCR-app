package com.ocrapp.data

import android.net.Uri
import com.ocrapp.ocr.MarkdownToPlainText
import com.ocrapp.ocr.OcrOutput
import com.ocrapp.ocr.PageSource
import com.ocrapp.ocr.runCatchingCancellable
import com.ocrapp.ocr.usePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepository @Inject constructor(
    private val dao: ScanDao,
    private val thumbnails: ThumbnailStore,
    private val keptPages: KeptPageStore,
) {

    /** The history list, filtered by [query] when it is not blank; see [searchKey]. */
    fun observe(query: String): Flow<List<ScanSummary>> {
        val key = searchKey(query.trim())
        return if (key.isEmpty()) dao.observeSummaries() else dao.searchSummaries("%${escapeLike(key)}%")
    }

    suspend fun get(id: Long): ScanEntity? = dao.getById(id)

    /** Stores a finished scan, with a thumbnail of the first of its [pages]. */
    suspend fun save(output: OcrOutput, pages: PageSource, title: String, notice: String?): Long {
        // A scan without a thumbnail is still a scan; the list has a placeholder for it.
        val thumbnailPath = runCatchingCancellable {
            pages.usePage(0, ThumbnailStore.EDGE_PX) { thumbnails.save(it) }
        }.getOrNull()

        return try {
            val scan = withContext(Dispatchers.Default) {
                ScanEntity(
                    createdAt = System.currentTimeMillis(),
                    title = title,
                    engine = output.engine.name,
                    plainText = output.plainText,
                    markdown = output.markdown,
                    thumbnailPath = thumbnailPath,
                    pageCount = output.pageCount,
                    notice = notice,
                )
            }
            dao.insert(scan)
        } catch (error: Exception) {
            thumbnails.delete(thumbnailPath)
            throw error
        }
    }

    /**
     * Persists user edits and drops the draft they came from.
     *
     * For an Unlimited-OCR scan the edited buffer *is* the Markdown source, so the
     * plain-text copy is regenerated from it to keep history search in sync.
     */
    suspend fun updateContent(id: Long, text: String, isMarkdown: Boolean) {
        val (plainText, searchText) = withContext(Dispatchers.Default) {
            val plainText = if (isMarkdown) MarkdownToPlainText.convert(text) else text
            plainText to searchKey(plainText)
        }
        dao.updateText(
            id = id,
            markdown = text.takeIf { isMarkdown },
            plainText = plainText,
            preview = previewOf(plainText),
            searchText = searchText,
        )
    }

    /**
     * Replaces the result of [scanId] with [output], a new run on the same pages: its
     * text, engine, page count and [notice]. A draft of the old text is dropped with it.
     */
    suspend fun replaceContent(scanId: Long, output: OcrOutput, notice: String?) {
        val searchText = withContext(Dispatchers.Default) { searchKey(output.plainText) }
        dao.replaceContent(
            id = scanId,
            engine = output.engine.name,
            markdown = output.markdown,
            plainText = output.plainText,
            pageCount = output.pageCount,
            notice = notice,
            preview = previewOf(output.plainText),
            searchText = searchText,
        )
    }

    /** Keeps unsaved corrections to [id] across process death; null drops them. */
    suspend fun saveDraft(id: Long, text: String?) = dao.updateDraft(id, text)

    suspend fun rename(id: Long, title: String) = dao.rename(id, title, searchKey(title))

    /** Deletes the row, its thumbnail file and its kept pages together, so nothing is orphaned. */
    suspend fun delete(id: Long) {
        val thumbnailPath = dao.thumbnailPath(id)
        dao.delete(id)
        thumbnails.delete(thumbnailPath)
        keptPages.drop(id)
    }

    /**
     * Keeps a copy of [sources], the pages of [scanId] in order, for a later
     * Document-parsing retry (see [KeptPageStore]). The scan stands without them, so a
     * copy that fails is not an error: it returns false and keeps nothing.
     */
    suspend fun keepPages(scanId: Long, sources: List<Uri>): Boolean =
        runCatchingCancellable { keptPages.keep(scanId, sources) }.isSuccess

    /** The pages kept for [scanId] as `file://` URIs in page order; empty if none. */
    suspend fun keptPages(scanId: Long): List<Uri> = keptPages.pages(scanId)

    fun observeHasKeptPages(scanId: Long): Flow<Boolean> = keptPages.observeHasPages(scanId)

    suspend fun dropKeptPages(scanId: Long) = keptPages.drop(scanId)

    /** Drops pages kept longer than [maxAgeMillis] ago; see [KeptPageStore.sweep]. */
    suspend fun sweepKeptPages(maxAgeMillis: Long = KEPT_PAGES_LIFETIME_MS) = keptPages.sweep(maxAgeMillis)

    private companion object {
        val KEPT_PAGES_LIFETIME_MS = TimeUnit.DAYS.toMillis(7)

        val LIKE_SPECIAL = Regex("""[\\%_]""")

        /** Makes [text] match literally in a `LIKE ... ESCAPE '\'` pattern. */
        fun escapeLike(text: String): String = text.replace(LIKE_SPECIAL) { "\\" + it.value }
    }
}

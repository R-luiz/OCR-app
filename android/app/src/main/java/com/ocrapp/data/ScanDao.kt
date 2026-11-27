package com.ocrapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    /**
     * The whole history list, newest first. Every column it reads is in the scans
     * index, so it never loads a text body.
     */
    @Query(
        """
        SELECT id, createdAt, title, engine, thumbnailPath, pageCount, preview
        FROM scans
        ORDER BY createdAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ScanSummary>>

    /**
     * The history entries whose title or text contains a search, newest first.
     *
     * [pattern] is a `LIKE` pattern over the folded columns (see [searchKey]), with `\`
     * as its escape character. A `LIKE` scan is plenty at the scale a personal scan
     * history reaches; FTS would only add a shadow table to maintain.
     */
    @Query(
        """
        SELECT id, createdAt, title, engine, thumbnailPath, pageCount, preview
        FROM scans
        WHERE searchTitle LIKE :pattern ESCAPE '\'
           OR searchText LIKE :pattern ESCAPE '\'
        ORDER BY createdAt DESC
        """,
    )
    fun searchSummaries(pattern: String): Flow<List<ScanSummary>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: Long): ScanEntity?

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("UPDATE scans SET title = :title, searchTitle = :searchTitle WHERE id = :id")
    suspend fun rename(id: Long, title: String, searchTitle: String)

    /** Stores edited text; the draft it came from is spent. */
    @Query(
        """
        UPDATE scans
        SET markdown = :markdown, plainText = :plainText, preview = :preview,
            searchText = :searchText, draftText = NULL
        WHERE id = :id
        """,
    )
    suspend fun updateText(id: Long, markdown: String?, plainText: String, preview: String, searchText: String)

    /** Replaces a scan's result with a new run on the same pages; any draft was of the old one. */
    @Query(
        """
        UPDATE scans
        SET engine = :engine, markdown = :markdown, plainText = :plainText, pageCount = :pageCount,
            notice = :notice, preview = :preview, searchText = :searchText, draftText = NULL
        WHERE id = :id
        """,
    )
    suspend fun replaceContent(
        id: Long,
        engine: String,
        markdown: String?,
        plainText: String,
        pageCount: Int,
        notice: String?,
        preview: String,
        searchText: String,
    )

    @Query("UPDATE scans SET draftText = :draftText WHERE id = :id")
    suspend fun updateDraft(id: Long, draftText: String?)

    @Query("SELECT thumbnailPath FROM scans WHERE id = :id")
    suspend fun thumbnailPath(id: Long): String?

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: Long)
}

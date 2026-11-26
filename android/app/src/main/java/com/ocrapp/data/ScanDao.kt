package com.ocrapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    /**
     * The history list, newest first, optionally filtered.
     *
     * [pattern] is a `LIKE` pattern matched against title and body, with `\` as its
     * escape character; an empty one lists everything. A `LIKE` scan is plenty at the
     * scale a personal scan history reaches; FTS would only add a shadow table to
     * maintain.
     */
    @Query(
        """
        SELECT id, createdAt, title, engine, thumbnailPath, pageCount,
               substr(ltrim(plainText, char(9, 10, 13, 32)), 1, 200) AS preview
        FROM scans
        WHERE :pattern = ''
           OR title LIKE :pattern ESCAPE '\'
           OR plainText LIKE :pattern ESCAPE '\'
        ORDER BY createdAt DESC
        """,
    )
    fun observeSummaries(pattern: String): Flow<List<ScanSummary>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: Long): ScanEntity?

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("UPDATE scans SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE scans SET plainText = :plainText WHERE id = :id")
    suspend fun updatePlainText(id: Long, plainText: String)

    @Query("UPDATE scans SET markdown = :markdown, plainText = :plainText WHERE id = :id")
    suspend fun updateMarkdown(id: Long, markdown: String, plainText: String)

    @Query("SELECT thumbnailPath FROM scans WHERE id = :id")
    suspend fun thumbnailPath(id: Long): String?

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: Long)
}

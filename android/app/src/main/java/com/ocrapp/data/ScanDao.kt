package com.ocrapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Query("SELECT * FROM scans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScanEntity>>

    /**
     * Substring search over title and body. A `LIKE` scan is plenty at the scale a
     * personal scan history reaches; FTS would only add a shadow table to maintain.
     */
    @Query(
        """
        SELECT * FROM scans
        WHERE title LIKE '%' || :query || '%'
           OR plainText LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        """,
    )
    fun search(query: String): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: Long): ScanEntity?

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Update
    suspend fun update(scan: ScanEntity)

    @Delete
    suspend fun delete(scan: ScanEntity)
}

package com.ocrapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ocrapp.ocr.EngineType

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val title: String,
    /** Stored as [EngineType.name]; see [engineType]. */
    val engine: String,
    val plainText: String,
    /** Only set for Unlimited-OCR results; ML Kit has no structured output. */
    val markdown: String?,
    val thumbnailPath: String?,
    val pageCount: Int,
) {
    val engineType: EngineType
        get() = runCatching { EngineType.valueOf(engine) }.getOrDefault(EngineType.QUICK)

    /** First non-blank line, used as the list subtitle. */
    val snippet: String
        get() = plainText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

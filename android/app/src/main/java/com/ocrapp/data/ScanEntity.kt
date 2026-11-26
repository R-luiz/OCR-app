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
    /**
     * What the user should know about how this scan came out: a fallback to on-device
     * recognition, pages with no text, pages left out. Kept with the scan because the
     * Result screen is where it is read, and it stays true when the scan is reopened.
     */
    val notice: String? = null,
) {
    val engineType: EngineType get() = EngineType.fromStored(engine)
}

/**
 * A scan as the history list shows it: everything but the text bodies.
 *
 * The list used to load whole rows, so opening the app read every page of every scan
 * into memory to draw a title and one line of each.
 */
data class ScanSummary(
    val id: Long,
    val createdAt: Long,
    val title: String,
    val engine: String,
    /** The opening of the recognized text, for [snippet]. */
    val preview: String,
    val thumbnailPath: String?,
    val pageCount: Int,
) {
    val engineType: EngineType get() = EngineType.fromStored(engine)

    /**
     * The list subtitle: how the text goes on after the title. A title is normally the
     * text's own first line, and showing that line twice says nothing.
     */
    val snippet: String
        get() = preview.replace(WHITESPACE, " ").trim().removePrefix(title).trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

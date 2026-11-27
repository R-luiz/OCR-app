package com.ocrapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ocrapp.ocr.EngineType
import java.text.Normalizer
import java.util.Locale

@Entity(
    tableName = "scans",
    // Holds every column of ScanSummary (the row id is part of any SQLite index), in
    // createdAt order: the unfiltered history list is read from this index alone and
    // never touches the text bodies.
    indices = [Index(value = ["createdAt", "title", "engine", "thumbnailPath", "pageCount", "preview"])],
)
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
    /** The opening of [plainText] the history list shows; see [previewOf]. */
    @ColumnInfo(defaultValue = "") val preview: String = previewOf(plainText),
    /** [title] folded for search; see [searchKey]. */
    @ColumnInfo(defaultValue = "") val searchTitle: String = searchKey(title),
    /** [plainText] folded for search; see [searchKey]. */
    @ColumnInfo(defaultValue = "") val searchText: String = searchKey(plainText),
    /**
     * Unsaved corrections from the Result editor, written a second after typing stops.
     * Android may end the process while the app is in the background; the editor
     * reopens on this rather than on the stored text, until the user saves or discards.
     */
    val draftText: String? = null,
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

/** Characters of the recognized text the history list keeps in [ScanEntity.preview]. */
internal const val PREVIEW_LENGTH = 200

/**
 * The opening of [plainText] for the history list: leading blank lines and spaces
 * dropped, cut at [PREVIEW_LENGTH]. Matches the SQL that filled the column for scans
 * saved before it existed (see [OcrDatabase.Migration2To3]).
 */
internal fun previewOf(plainText: String): String {
    val opening = plainText.trimStart { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        .take(PREVIEW_LENGTH)
    // A cut between the two halves of a surrogate pair would store half a character.
    return if (opening.lastOrNull()?.isHighSurrogate() == true) opening.dropLast(1) else opening
}

private val COMBINING_MARKS = Regex("\\p{M}+")

/**
 * [text] as history search compares it: accents stripped and case folded, so "ecole"
 * finds "École". SQLite's `LIKE` folds neither accents nor the case of non-ASCII
 * letters, so the folding is done here, on the stored copy and on the query alike.
 */
internal fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
